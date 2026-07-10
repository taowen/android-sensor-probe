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

class UsbGlassesReader(private val context: Context, private val listener: Listener) : Closeable {
    enum class DisplayMode { MIRROR_2D, FULL_SBS_3D, HALF_SBS, HIGH_REFRESH, HIGH_REFRESH_SBS }
    interface Listener {
        fun onDevicesChanged(devices: List<UsbDevice>)
        fun onStatus(message: String)
        fun onReading(reading: SensorReading)
        fun onSlamFrame(left:Bitmap,right:Bitmap,timestamp:Long)
    }
    companion object { private const val ACTION_PERMISSION = "io.github.sensorprobe.USB_PERMISSION" }
    private val manager = context.getSystemService(UsbManager::class.java)
    private var session: MultiSession? = null
    private var slamReader:Ov580SlamReader?=null
    private var activeConnection:UsbDeviceConnection?=null
    private var activeDevice:UsbDevice?=null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERMISSION -> i.usbDevice()?.let { if (manager.hasPermission(it)) open(it) else listener.onStatus("USB 权限被拒绝") }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> { if(i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) stop(); scan() }
            }
        }
    }
    init {
        val f=IntentFilter().apply { addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        context.registerReceiver(receiver, f, if(Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0)
    }
    fun scan() { listener.onDevicesChanged(manager.deviceList.values.sortedBy { it.deviceName }) }
    fun connect(device: UsbDevice) {
        stop()
        if (!manager.hasPermission(device)) {
            val pi=PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.requestPermission(device, pi); listener.onStatus("等待 USB 授权…")
        } else open(device)
    }
    private fun open(device: UsbDevice) {
        val connection=manager.openDevice(device) ?: return listener.onStatus("无法打开 USB 设备")
        activeConnection=connection;activeDevice=device
        val model=ModelCatalog.identify(device)
        if(device.vendorId==0x05a9 && device.productId==0x0680) {
            slamReader=Ov580SlamReader(connection.fileDescriptor,listener::onSlamFrame)
            listener.onStatus(if(slamReader?.started==true)"OV580 双 SLAM 相机已启动" else "OV580 SLAM JNI启动失败")
        }
        val protocol=when(model.protocol){ GlassesModel.Protocol.XREAL_AIR->if(device.productId==0x0440) XbxA01Protocol else XrealProtocol; GlassesModel.Protocol.XREAL_LIGHT_MCU->XrealLightMcuProtocol; GlassesModel.Protocol.XREAL_LIGHT_OV580->XrealLightOv580Protocol; GlassesModel.Protocol.ROKID->RokidProtocol; GlassesModel.Protocol.GRAWOOW_MCU,GlassesModel.Protocol.MAD_GAZE->RawUsbProtocol; GlassesModel.Protocol.GRAWOOW_OV580->GrawoowOv580Protocol; GlassesModel.Protocol.VITURE->VitureProtocol; GlassesModel.Protocol.RAYNEO->RayneoProtocol; else->null }
        if(protocol == null){ connection.close(); return listener.onStatus("已识别接口，但该型号尚无已验证的传感器协议") }
        val allInterfaces=(0 until device.interfaceCount).map { device.getInterface(it) }
        val allHid=allInterfaces.filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        val candidates=if(device.productId==0x0440) allHid.filter { intf ->
            (0 until intf.endpointCount).map { intf.getEndpoint(it) }.any { it.address==0x84 }
        } else when(model.protocol) {
            GlassesModel.Protocol.ROKID -> allInterfaces.filter { i -> (0 until i.endpointCount).any { i.getEndpoint(it).address==0x82 || i.getEndpoint(it).address==0x83 } }
            GlassesModel.Protocol.GRAWOOW_OV580 -> allInterfaces.filter { i -> (0 until i.endpointCount).any { i.getEndpoint(it).address==0x89 } }
            GlassesModel.Protocol.XREAL_LIGHT_OV580 -> allInterfaces.filter { i -> i.id!=1 && (0 until i.endpointCount).any { i.getEndpoint(it).direction==UsbConstants.USB_DIR_IN } }
            GlassesModel.Protocol.XREAL_LIGHT_MCU,GlassesModel.Protocol.GRAWOOW_MCU,GlassesModel.Protocol.MAD_GAZE -> allInterfaces.filter { i -> (0 until i.endpointCount).any { i.getEndpoint(it).direction==UsbConstants.USB_DIR_IN } }
            else -> allHid
        }
        val sessions=mutableListOf<Session>()
        for(intf in candidates) {
            val input=(0 until intf.endpointCount).map{intf.getEndpoint(it)}.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            if(input == null || !connection.claimInterface(intf, true)) continue
            val output=(0 until intf.endpointCount).map{intf.getEndpoint(it)}.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            // XREAL IMU is identified by its stable interrupt endpoints (IN 0x84,
            // OUT 0x05). xbx a01 renumbered interfaces but retained these endpoints.
            val isXrealImu = input.address == 0x84 && output?.address == 0x05
            val passive = device.productId == 0x0440 && !isXrealImu
            sessions += Session(connection,intf,input,output,protocol,listener, passiveOnly = passive)
        }
        if(sessions.isEmpty()){ connection.close(); listener.onStatus("未找到可读取的 HID IN 接口"); return }
        session=MultiSession(connection,sessions).also { it.start() }
        listener.onStatus("正在监听 ${model.displayName} · HID interfaces ${sessions.joinToString { it.intf.id.toString() }}")
    }
    fun stop(){ slamReader?.close();slamReader=null;session?.close(); session=null;activeConnection=null;activeDevice=null }
    fun setDisplayMode(mode:DisplayMode) {
        val c=activeConnection?:return listener.onStatus("请先连接眼镜")
        val d=activeDevice?:return
        val model=ModelCatalog.identify(d)
        val ok=when(model.protocol) {
            GlassesModel.Protocol.XREAL_AIR -> if(d.productId==0x0440) false else sendXrealMcu(c,d,mode)
            GlassesModel.Protocol.XREAL_LIGHT_MCU -> sendLightMcu(c,d,mode)
            GlassesModel.Protocol.ROKID -> sendRokid(c,mode)
            GlassesModel.Protocol.GRAWOOW_MCU -> sendGrawoow(c,mode)
            GlassesModel.Protocol.MAD_GAZE -> sendMadGaze(c,d,mode)
            else -> false
        }
        listener.onStatus(if(ok)"${model.displayName}：${mode.name} 命令已发送" else "${model.displayName}：该模式未实现或发送失败")
    }
    private fun sendXrealMcu(c:UsbDeviceConnection,d:UsbDevice,mode:DisplayMode):Boolean {
        val value=when(mode){DisplayMode.MIRROR_2D->1;DisplayMode.FULL_SBS_3D->3;DisplayMode.HALF_SBS->8;DisplayMode.HIGH_REFRESH->11;DisplayMode.HIGH_REFRESH_SBS->9}
        val p=ByteArray(64);p[0]=0xfd.toByte();p[5]=18;p[7]=0x37;p[8]=0x13;p[15]=8;p[22]=value.toByte()
        val crc=java.util.zip.Adler32().apply{update(p,5,18)}.value;p[1]=crc.toByte();p[2]=(crc shr 8).toByte();p[3]=(crc shr 16).toByte();p[4]=(crc shr 24).toByte()
        val endpoints=(0 until d.interfaceCount).flatMap{i->val f=d.getInterface(i);(0 until f.endpointCount).map{f to f.getEndpoint(it)}}
        val out=endpoints.firstOrNull{it.second.direction==UsbConstants.USB_DIR_OUT&&it.second.address==0x07}
            ?:endpoints.firstOrNull{it.second.direction==UsbConstants.USB_DIR_OUT&&it.second.address==0x05}?:return false
        c.claimInterface(out.first,true);return c.bulkTransfer(out.second,p,p.size,500)==p.size
    }
    private fun sendLightMcu(c:UsbDeviceConnection,d:UsbDevice,mode:DisplayMode):Boolean {
        val v=when(mode){DisplayMode.MIRROR_2D->'1';DisplayMode.HALF_SBS->'2';DisplayMode.FULL_SBS_3D->'3';DisplayMode.HIGH_REFRESH_SBS->'4';DisplayMode.HIGH_REFRESH->return false}
        val p=XrealLightMcuProtocol.displayMode(v);return sendAnyOut(c,d,p)
    }
    private fun sendRokid(c:UsbDeviceConnection,mode:DisplayMode):Boolean {val v=when(mode){DisplayMode.MIRROR_2D->0;DisplayMode.FULL_SBS_3D->1;DisplayMode.HIGH_REFRESH->3;DisplayMode.HIGH_REFRESH_SBS->4;DisplayMode.HALF_SBS->return false};val b=byteArrayOf(0);return c.controlTransfer(0x40,1,v,1,b,1,500)>=0}
    private fun sendGrawoow(c:UsbDeviceConnection,mode:DisplayMode):Boolean {val v=when(mode){DisplayMode.MIRROR_2D->0;DisplayMode.FULL_SBS_3D->1;else->return false};val p=byteArrayOf(0xaa.toByte(),0xbb.toByte(),0x80.toByte(),8,0,1,v.toByte(),(0x80+8+1+v).toByte());return c.controlTransfer(0x21,9,0x201,0,p,p.size,1000)>=0}
    private fun sendMadGaze(c:UsbDeviceConnection,d:UsbDevice,mode:DisplayMode):Boolean {val v=when(mode){DisplayMode.MIRROR_2D->0;DisplayMode.FULL_SBS_3D->1;else->return false};val cmd=byteArrayOf(':'.code.toByte(),'S'.code.toByte(),'3'.code.toByte(),'D'.code.toByte(),6,0xab.toByte(),0xcd.toByte(),v.toByte(),0,0,0xff.toByte());return sendAnyOut(c,d,cmd)}
    private fun sendAnyOut(c:UsbDeviceConnection,d:UsbDevice,p:ByteArray):Boolean {for(i in 0 until d.interfaceCount){val f=d.getInterface(i);for(j in 0 until f.endpointCount){val e=f.getEndpoint(j);if(e.direction==UsbConstants.USB_DIR_OUT){c.claimInterface(f,true);if(c.bulkTransfer(e,p,p.size,500)>=0)return true}}};return false}
    override fun close(){ stop(); context.unregisterReceiver(receiver) }

    @Suppress("DEPRECATION") private fun Intent.usbDevice(): UsbDevice? = if(Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private class MultiSession(private val connection: UsbDeviceConnection, private val sessions: List<Session>): Closeable {
        fun start()=sessions.forEach { it.start() }
        override fun close(){ sessions.forEach { it.stop() }; connection.close() }
    }
    private class Session(val connection: UsbDeviceConnection, val intf: UsbInterface, val input: UsbEndpoint, val output: UsbEndpoint?, val protocol: GlassesProtocol, val listener: Listener, val passiveOnly:Boolean): Closeable {
        private val running=AtomicBoolean(true)
        private val thread=Thread({ run() }, "glasses-usb-reader")
        fun start(){ thread.start() }
        private fun run(){
            if(!passiveOnly) protocol.startCommand()?.let { cmd ->
                val sent=output?.let { connection.bulkTransfer(it,cmd,cmd.size,500) } ?: connection.controlTransfer(0x21,0x09,0x0200,intf.id,cmd,cmd.size,500)
                if(sent < 0) listener.onStatus("启动 IMU 命令发送失败；继续监听被动报告")
            }
            if(protocol===XrealLightMcuProtocol) {
                sendOut(XrealLightMcuProtocol.ambientEnable())
                sendOut(XrealLightMcuProtocol.vsyncEnable())
            }
            val data=ByteArray(maxOf(64,input.maxPacketSize))
            var misses=0; var packets=0
            while(running.get()){
                val n=connection.bulkTransfer(input,data,data.size,500)
                if(n > 0){
                    misses=0; packets++
                    val decoded=protocol.decode(data,n)
                    if(decoded != null) listener.onReading(decoded)
                    else if(packets <= 8 || packets % 100 == 0) listener.onReading(SensorReading("HID interface ${intf.id} · 原始报告 #$packets · ${n} bytes", rawHex=data.hex(n)))
                }
                else if(++misses == 6) listener.onStatus("interface ${intf.id} 暂无 HID 报告；请摇动眼镜或按眼镜按键")
            }
        }
        private fun sendOut(cmd:ByteArray) {
            output?.let { connection.bulkTransfer(it,cmd,cmd.size,500) }
                ?: connection.controlTransfer(0x21,0x09,0x0200,intf.id,cmd,cmd.size,500)
        }
        fun stop(){ running.set(false); connection.releaseInterface(intf); thread.interrupt() }
        override fun close()=stop()
    }
}
