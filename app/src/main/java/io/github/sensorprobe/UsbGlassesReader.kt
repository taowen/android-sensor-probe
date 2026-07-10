package io.github.sensorprobe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbGlassesReader(private val context: Context, private val listener: Listener) : Closeable {
    enum class DisplayMode { MIRROR_2D, FULL_SBS_3D, HALF_SBS, HIGH_REFRESH, HIGH_REFRESH_SBS }
    interface Listener {
        fun onDevicesChanged(devices: List<UsbDevice>)
        fun onStatus(message: String)
        fun onReading(reading: SensorReading)
        fun onSlamFrame(left:Bitmap,right:Bitmap,timestamp:Long)
        fun onUvcFrame(frame:Bitmap)
    }
    companion object { private const val ACTION_PERMISSION = "io.github.sensorprobe.USB_PERMISSION" }
    private val manager = context.getSystemService(UsbManager::class.java)
    private var session: MultiSession? = null
    private var slamReader:Ov580SlamReader?=null
    private var uvcReader:UvcCameraReader?=null
    private var helenTcpReader:XrealOneTcpReader?=null
    private var activeConnection:UsbDeviceConnection?=null
    private var activeLibusbHandle:Long=0
    private var activeDevice:UsbDevice?=null
    private var pendingPermissionDevice:UsbDevice?=null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERMISSION -> {
                    val device=i.usbDevice() ?: pendingPermissionDevice
                    pendingPermissionDevice=null
                    if(device != null && manager.hasPermission(device)) open(device)
                    else listener.onStatus("USB 权限被拒绝或授权结果无效")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> { if(i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) stop(); scan() }
            }
        }
    }
    init {
        val f=IntentFilter().apply { addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        context.registerReceiver(receiver, f, if(Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)
    }
    fun scan() { listener.onDevicesChanged(manager.deviceList.values.sortedBy { it.deviceName }) }
    fun connect(device: UsbDevice) {
        stop()
        if (!manager.hasPermission(device)) {
            pendingPermissionDevice=device
            // UsbManager uses fill-in extras for the device and grant result.
            val pi=PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.requestPermission(device, pi); listener.onStatus("等待 USB 授权…")
        } else open(device)
    }
    private fun open(device: UsbDevice) {
        val connection=manager.openDevice(device) ?: return listener.onStatus("无法打开 USB 设备")
        if(device.vendorId==0x0c45 && (device.productId==0x6368 || device.productId==0x636b)) {
            activeConnection=connection;activeDevice=device
            uvcReader=UvcCameraReader(connection.fileDescriptor,listener::onUvcFrame)
            listener.onStatus(if(uvcReader?.started==true)"${ModelCatalog.identify(device).displayName} · ${uvcReader?.transport} · 1920×1080@30 MJPEG 已启动" else "UVC/libusb 启动失败")
            return
        }
        val nativeHandle=LibusbNative.open(connection.fileDescriptor)
        if(nativeHandle==0L){connection.close();return listener.onStatus("libusb_wrap_sys_device 失败")}
        activeConnection=connection;activeLibusbHandle=nativeHandle;activeDevice=device
        val model=ModelCatalog.identify(device)
        if(model.xreal!=null && !model.xreal.bootloader && model.xreal.imuInterface==null) {
            helenTcpReader=XrealOneTcpReader(listener::onReading) { listener.onStatus(it) }.also { it.start() }
            listener.onStatus("正在连接 ${model.displayName} 的 USB Ethernet IMU；MCU interface ${model.xreal.mcuInterface}")
            return
        }
        if(device.vendorId==0x05a9 && device.productId==0x0680) {
            slamReader=Ov580SlamReader(connection.fileDescriptor,listener::onSlamFrame)
            listener.onStatus(if(slamReader?.started==true)"OV580 双 SLAM 相机已启动" else "OV580 SLAM JNI启动失败")
        }
        val protocol=when(model.protocol){ GlassesModel.Protocol.XREAL_AIR->if(model.xreal?.driverFamily?.startsWith("Helen")==true) XbxA01Protocol else XrealProtocol; GlassesModel.Protocol.XREAL_LIGHT_MCU->XrealLightMcuProtocol; GlassesModel.Protocol.XREAL_LIGHT_OV580->XrealLightOv580Protocol; GlassesModel.Protocol.ROKID->RokidProtocol; GlassesModel.Protocol.GRAWOOW_MCU,GlassesModel.Protocol.MAD_GAZE->RawUsbProtocol; GlassesModel.Protocol.VITURE_PASSIVE->VitureGen2RawProtocol; GlassesModel.Protocol.GRAWOOW_OV580->GrawoowOv580Protocol; GlassesModel.Protocol.VITURE->VitureProtocol; GlassesModel.Protocol.RAYNEO->RayneoProtocol; else->null }
        if(protocol == null){ LibusbNative.close(nativeHandle);connection.close(); return listener.onStatus("已识别接口，但该型号尚无已验证的传感器协议") }
        val nativeHelenInit=protocol===XbxA01Protocol
        if(nativeHelenInit) listener.onReading(SensorReading(LibusbNative.initializeXrealHelen(nativeHandle)))
        val allInterfaces=(0 until device.interfaceCount).map { device.getInterface(it) }
        val allHid=allInterfaces.filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        val candidates=if(model.protocol==GlassesModel.Protocol.XREAL_AIR) {
            val official=model.xreal?.imuInterface
            val byOfficial=official?.let { id->allHid.filter { it.id==id } }.orEmpty()
            val byEndpoint=allHid.filter { intf->(0 until intf.endpointCount).any { intf.getEndpoint(it).address==0x84 } }
            byOfficial.ifEmpty { byEndpoint.ifEmpty { allHid } }
        } else when(model.protocol) {
            GlassesModel.Protocol.ROKID -> allInterfaces.filter { i -> (0 until i.endpointCount).any { i.getEndpoint(it).address==0x82 || i.getEndpoint(it).address==0x83 } }
            GlassesModel.Protocol.GRAWOOW_OV580 -> allInterfaces.filter { i -> (0 until i.endpointCount).any { i.getEndpoint(it).address==0x89 } }
            GlassesModel.Protocol.VITURE_PASSIVE -> allHid
            GlassesModel.Protocol.XREAL_LIGHT_OV580 -> allInterfaces.filter { i -> i.id!=1 && (0 until i.endpointCount).any { i.getEndpoint(it).direction==UsbConstants.USB_DIR_IN } }
            GlassesModel.Protocol.XREAL_LIGHT_MCU,GlassesModel.Protocol.GRAWOOW_MCU,GlassesModel.Protocol.MAD_GAZE -> allInterfaces.filter { i -> (0 until i.endpointCount).any { i.getEndpoint(it).direction==UsbConstants.USB_DIR_IN } }
            else -> allHid
        }
        val sessions=mutableListOf<Session>()
        for(intf in candidates) {
            val input=(0 until intf.endpointCount).map{intf.getEndpoint(it)}.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            if(input == null || (!nativeHelenInit && LibusbNative.claim(nativeHandle,intf.id)!=0)) continue
            val output=(0 until intf.endpointCount).map{intf.getEndpoint(it)}.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            sessions += Session(nativeHandle,intf,input,output,protocol,listener, passiveOnly = nativeHelenInit)
        }
        if(sessions.isEmpty()){ LibusbNative.close(nativeHandle);connection.close(); listener.onStatus("未找到可读取的 HID IN 接口"); return }
        session=MultiSession(nativeHandle,connection,sessions).also { it.start() }
        listener.onStatus("正在监听 ${model.displayName} · HID interfaces ${sessions.joinToString { it.intf.id.toString() }}")
    }
    fun stop(){
        pendingPermissionDevice=null
        helenTcpReader?.close();helenTcpReader=null;slamReader?.close();slamReader=null;uvcReader?.close();uvcReader=null
        val managedBySession=session!=null;session?.close();session=null
        if(!managedBySession){if(activeLibusbHandle!=0L)LibusbNative.close(activeLibusbHandle);activeConnection?.close()}
        activeConnection=null;activeLibusbHandle=0;activeDevice=null
    }
    fun setDisplayMode(mode:DisplayMode) {
        val h=activeLibusbHandle.takeIf{it!=0L}?:return listener.onStatus("请先连接眼镜")
        val d=activeDevice?:return
        val model=ModelCatalog.identify(d)
        val ok=when(model.protocol) {
            GlassesModel.Protocol.XREAL_AIR -> if(model.xreal?.bootloader==true) false else sendXrealMcu(h,d,mode,model.xreal)
            GlassesModel.Protocol.XREAL_LIGHT_MCU -> sendLightMcu(h,d,mode)
            GlassesModel.Protocol.ROKID -> sendRokid(h,mode)
            GlassesModel.Protocol.GRAWOOW_MCU -> sendGrawoow(h,mode)
            GlassesModel.Protocol.MAD_GAZE -> sendMadGaze(h,d,mode)
            else -> false
        }
        listener.onStatus(if(ok)"${model.displayName}：${mode.name} 命令已发送" else "${model.displayName}：该模式未实现或发送失败")
    }
    fun queryVitureBeastMode(){
        if(activeDevice?.let{it.vendorId==0x35ca&&(it.productId==0x1201||it.productId==0x1211)}!=true)return listener.onStatus("请先连接 VITURE Beast 主控")
        val ok=session?.send(VitureGen2RawProtocol.command(0x3140))==true && session?.send(VitureGen2RawProtocol.command(0x3142))==true
        listener.onStatus(if(ok)"已发送 Beast 模式查询，等待 MCU 响应" else "Beast 模式查询发送失败")
    }
    fun setVitureBeastDimension(is3d:Boolean){
        if(activeDevice?.let{it.vendorId==0x35ca&&(it.productId==0x1201||it.productId==0x1211)}!=true)return listener.onStatus("请先连接 VITURE Beast 主控")
        val value=if(is3d)0x37 else 0x31
        val ok=session?.send(VitureGen2RawProtocol.command(0x0142,byteArrayOf(value.toByte())))==true
        listener.onStatus(if(ok)"已发送 Beast ${if(is3d)"3D" else "2D"} 切换命令" else "Beast 显示模式命令发送失败")
    }
    fun queryXrealDisplayMode() {
        val d=activeDevice?:return
        val model=ModelCatalog.identify(d)
        val profile=model.xreal?:return listener.onStatus("当前设备不是已收录的 XREAL USB 眼镜")
        val intf=(0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.id==profile.mcuInterface }
            ?:return listener.onStatus("${model.displayName} 没有可用的 MCU 接口")
        val output=(0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull { it.direction==UsbConstants.USB_DIR_OUT }
            ?:return listener.onStatus("MCU OUT endpoint 不存在")
        val input=(0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull { it.direction==UsbConstants.USB_DIR_IN }
            ?:return listener.onStatus("MCU IN endpoint 不存在")
        Thread({
            val c=manager.openDevice(d)?:return@Thread listener.onStatus("无法为 MCU 查询打开独立 USB connection")
            val h=LibusbNative.open(c.fileDescriptor)
            if(h==0L||LibusbNative.claim(h,intf.id)!=0) return@Thread listener.onStatus("无法 claim MCU interface ${intf.id}")
            val requestId=0x1337
            val command=xrealSdkPacket(0x07,byteArrayOf(),requestId)
            if(nativeTransfer(h,output,command,command.size,500)!=command.size) return@Thread listener.onStatus("XREAL MCU 显示模式查询发送失败")
            val response=ByteArray(maxOf(64,input.maxPacketSize))
            var n=-1
            for(attempt in 0 until 10) {
                val received=nativeTransfer(h,input,response,response.size,300)
                if(received>=17 && response[0]==0xfd.toByte()) {
                    val responseId=ByteBuffer.wrap(response,7,4).order(ByteOrder.LITTLE_ENDIAN).int
                    val responseCommand=(response[15].toInt() and 255) or ((response[16].toInt() and 255) shl 8)
                    if(responseId==requestId && responseCommand==0x07) { n=received; break }
                }
            }
            if(n<23) return@Thread listener.onStatus("XREAL MCU 查询未收到匹配的 0x07 响应")
            val cmd=(response[15].toInt() and 255) or ((response[16].toInt() and 255) shl 8)
            val status=response[22].toInt() and 255
            val value=if(n>=27) ByteBuffer.wrap(response,23,4).order(ByteOrder.LITTLE_ENDIAN).int else null
            listener.onReading(SensorReading("${model.displayName} MCU · cmd=${cmd.hex4()} · status=$status · displayRaw=${value ?: "无"}",rawHex=response.hex(n)))
            LibusbNative.release(h,intf.id);LibusbNative.close(h);c.close()
        },"xreal-mcu-query").start()
    }
    private fun sendXrealMcu(h:Long,d:UsbDevice,mode:DisplayMode,profile:XrealUsbProfile?):Boolean {
        val value=when(mode){DisplayMode.MIRROR_2D->1;DisplayMode.HALF_SBS->2;DisplayMode.FULL_SBS_3D->3;DisplayMode.HIGH_REFRESH_SBS->4;DisplayMode.HIGH_REFRESH->return false}
        val p=xrealSdkPacket(0x08,byteArrayOf(value.toByte()),0x1337)
        val endpoints=(0 until d.interfaceCount).flatMap{i->val f=d.getInterface(i);(0 until f.endpointCount).map{f to f.getEndpoint(it)}}
        val out=endpoints.firstOrNull{it.first.id==profile?.mcuInterface&&it.second.direction==UsbConstants.USB_DIR_OUT}
            ?:endpoints.firstOrNull{it.second.direction==UsbConstants.USB_DIR_OUT}?:return false
        LibusbNative.claim(h,out.first.id);return nativeTransfer(h,out.second,p,p.size,500)==p.size
    }
    private fun xrealSdkPacket(command:Int,data:ByteArray,requestId:Int):ByteArray {
        val bodyLength=17+data.size
        return ByteArray(bodyLength+5).also { p ->
            p[0]=0xfd.toByte();p[5]=bodyLength.toByte();p[6]=(bodyLength shr 8).toByte()
            p[7]=requestId.toByte();p[8]=(requestId shr 8).toByte();p[9]=(requestId shr 16).toByte();p[10]=(requestId shr 24).toByte()
            p[15]=command.toByte();p[16]=(command shr 8).toByte();data.copyInto(p,22)
            val crc=java.util.zip.CRC32().apply{update(p,5,bodyLength)}.value
            p[1]=crc.toByte();p[2]=(crc shr 8).toByte();p[3]=(crc shr 16).toByte();p[4]=(crc shr 24).toByte()
        }
    }
    private fun sendLightMcu(h:Long,d:UsbDevice,mode:DisplayMode):Boolean {
        val v=when(mode){DisplayMode.MIRROR_2D->'1';DisplayMode.HALF_SBS->'2';DisplayMode.FULL_SBS_3D->'3';DisplayMode.HIGH_REFRESH_SBS->'4';DisplayMode.HIGH_REFRESH->return false}
        val p=XrealLightMcuProtocol.displayMode(v);return sendAnyOut(h,d,p)
    }
    private fun sendRokid(h:Long,mode:DisplayMode):Boolean {val v=when(mode){DisplayMode.MIRROR_2D->0;DisplayMode.FULL_SBS_3D->1;DisplayMode.HIGH_REFRESH->3;DisplayMode.HIGH_REFRESH_SBS->4;DisplayMode.HALF_SBS->return false};val b=byteArrayOf(0);return LibusbNative.controlTransfer(h,0x40,1,v,1,b,1,500)>=0}
    private fun sendGrawoow(h:Long,mode:DisplayMode):Boolean {val v=when(mode){DisplayMode.MIRROR_2D->0;DisplayMode.FULL_SBS_3D->1;else->return false};val p=byteArrayOf(0xaa.toByte(),0xbb.toByte(),0x80.toByte(),8,0,1,v.toByte(),(0x80+8+1+v).toByte());return LibusbNative.controlTransfer(h,0x21,9,0x201,0,p,p.size,1000)>=0}
    private fun sendMadGaze(h:Long,d:UsbDevice,mode:DisplayMode):Boolean {val v=when(mode){DisplayMode.MIRROR_2D->0;DisplayMode.FULL_SBS_3D->1;else->return false};val cmd=byteArrayOf(':'.code.toByte(),'S'.code.toByte(),'3'.code.toByte(),'D'.code.toByte(),6,0xab.toByte(),0xcd.toByte(),v.toByte(),0,0,0xff.toByte());return sendAnyOut(h,d,cmd)}
    private fun sendAnyOut(h:Long,d:UsbDevice,p:ByteArray):Boolean {for(i in 0 until d.interfaceCount){val f=d.getInterface(i);for(j in 0 until f.endpointCount){val e=f.getEndpoint(j);if(e.direction==UsbConstants.USB_DIR_OUT){LibusbNative.claim(h,f.id);if(nativeTransfer(h,e,p,p.size,500)>=0)return true}}};return false}
    private fun nativeTransfer(h:Long,e:UsbEndpoint,p:ByteArray,length:Int,timeout:Int)=if(e.type==UsbConstants.USB_ENDPOINT_XFER_INT)LibusbNative.interruptTransfer(h,e.address,p,length,timeout)else LibusbNative.bulkTransfer(h,e.address,p,length,timeout)
    override fun close(){ stop(); context.unregisterReceiver(receiver) }

    @Suppress("DEPRECATION") private fun Intent.usbDevice(): UsbDevice? = if(Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private class MultiSession(private val nativeHandle:Long,private val connection: UsbDeviceConnection, private val sessions: List<Session>): Closeable {
        fun start()=sessions.forEach { it.start() }
        fun send(command:ByteArray)=sessions.firstOrNull{it.hasOutput}?.send(command)==command.size
        override fun close(){ sessions.forEach { it.stop() };LibusbNative.close(nativeHandle);connection.close() }
    }
    private class Session(val nativeHandle:Long, val intf: UsbInterface, val input: UsbEndpoint, val output: UsbEndpoint?, val protocol: GlassesProtocol, val listener: Listener, val passiveOnly:Boolean): Closeable {
        private val running=AtomicBoolean(true)
        private val thread=Thread({ run() }, "glasses-usb-reader")
        val hasOutput get()=output!=null
        @Synchronized fun send(command:ByteArray)=output?.let{transfer(it,command,command.size,500)}?:-1
        fun start(){
            if(!(protocol===XbxA01Protocol&&passiveOnly)) {
                val rc=LibusbNative.startEndpointReader(nativeHandle,input.address,input.type,maxOf(64,input.maxPacketSize))
                if(rc!=0)listener.onStatus("interface ${intf.id} 原生异步 reader 启动失败：$rc")
            }
            thread.start()
        }
        private fun run(){
            if(!passiveOnly) protocol.startCommand()?.let { cmd ->
                val sent=output?.let { transfer(it,cmd,cmd.size,500) } ?: LibusbNative.controlTransfer(nativeHandle,0x21,0x09,0x0200,intf.id,cmd,cmd.size,500)
                if(sent < 0) listener.onStatus("启动 IMU 命令发送失败；继续监听被动报告")
            }
            if(protocol===XrealLightMcuProtocol) {
                sendOut(XrealLightMcuProtocol.ambientEnable())
                sendOut(XrealLightMcuProtocol.vsyncEnable())
            }
            val data=ByteArray(maxOf(64,input.maxPacketSize))
            var misses=0; var packets=0
            while(running.get()){
                val n=readInput(data)
                if(n > 0){
                    misses=0; packets++
                    val decoded=protocol.decode(data,n)
                    if(decoded != null) listener.onReading(decoded)
                    else if(packets <= 8 || packets % 100 == 0) listener.onReading(SensorReading("HID interface ${intf.id} · 原始报告 #$packets · ${n} bytes", rawHex=data.hex(n)))
                }
                else if(++misses == 6) listener.onStatus("interface ${intf.id} 暂无 HID 报告；请摇动眼镜或按眼镜按键")
            }
        }
        private fun readInput(target:ByteArray):Int {
            return if(protocol===XbxA01Protocol && passiveOnly) LibusbNative.readXrealHelen(nativeHandle,target,750)
            else LibusbNative.readEndpoint(nativeHandle,input.address,target,750)
        }
        private fun transfer(endpoint:UsbEndpoint,data:ByteArray,length:Int,timeout:Int)=
            if(endpoint.type==UsbConstants.USB_ENDPOINT_XFER_INT)LibusbNative.interruptTransfer(nativeHandle,endpoint.address,data,length,timeout)
            else LibusbNative.bulkTransfer(nativeHandle,endpoint.address,data,length,timeout)
        private fun sendOut(cmd:ByteArray) {
            output?.let { transfer(it,cmd,cmd.size,500) }
                ?: LibusbNative.controlTransfer(nativeHandle,0x21,0x09,0x0200,intf.id,cmd,cmd.size,500)
        }
        fun stop(){ running.set(false);thread.interrupt();if(Thread.currentThread()!==thread)thread.join(1200) }
        override fun close()=stop()
    }
}
