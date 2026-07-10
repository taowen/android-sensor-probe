package io.github.sensorprobe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.*
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity(), UsbGlassesReader.Listener {
    private lateinit var reader: UsbGlassesReader
    private lateinit var devicesBox: LinearLayout
    private lateinit var reading: TextView
    private lateinit var status: TextView
    private lateinit var cameras: TextView
    private lateinit var cameraPreviews: LinearLayout
    private lateinit var slamBox:LinearLayout
    private lateinit var slamLeft:ImageView
    private lateinit var slamRight:ImageView
    private lateinit var slamStatus:TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title="AR 眼镜传感器探针"
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,32,32,32); setBackgroundColor(Color.rgb(16,20,24)) }
        fun label(text:String,size:Float=16f)=TextView(this).apply { this.text=text; textSize=size; setTextColor(Color.WHITE); setPadding(0,12,0,12) }
        root.addView(label("AR 眼镜传感器探针",26f))
        status=label("正在扫描 USB…",14f).also(root::addView)
        root.addView(label("Type-C / USB 设备",19f)); devicesBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; root.addView(devicesBox)
        val refresh=Button(this).apply { text="重新扫描"; setOnClickListener { reader.scan(); enumerateCameras() } }; root.addView(refresh)
        root.addView(label("MCU / 显示模式",19f))
        root.addView(label("会直接向已连接眼镜发送模式命令；仅已实现的型号会执行。XBX a01 暂不写入未公开的私有命令。",13f))
        val modeBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        listOf(
            "2D 镜像" to UsbGlassesReader.DisplayMode.MIRROR_2D,
            "3D Full SBS" to UsbGlassesReader.DisplayMode.FULL_SBS_3D,
            "3D Half SBS" to UsbGlassesReader.DisplayMode.HALF_SBS,
            "2D 高刷" to UsbGlassesReader.DisplayMode.HIGH_REFRESH,
            "3D SBS 高刷" to UsbGlassesReader.DisplayMode.HIGH_REFRESH_SBS
        ).chunked(2).forEach { rowModes ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            rowModes.forEach { (caption,mode) ->
                row.addView(Button(this).apply { text=caption; setOnClickListener { reader.setDisplayMode(mode) } },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
            }
            modeBox.addView(row)
        }
        root.addView(modeBox)
        root.addView(label("实时读数",19f)); reading=label("连接支持的眼镜后显示 IMU 数据",15f).also(root::addView)
        root.addView(label("眼镜/USB 外接摄像头（不含手机内置）",19f)); cameras=label("正在枚举…",14f).also(root::addView)
        cameraPreviews=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(cameraPreviews)
        root.addView(label("原始 SLAM 相机（JNI/OV580）",19f))
        slamStatus=label("未连接支持的 OV580设备",14f).also(root::addView)
        slamBox=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        slamLeft=ImageView(this).apply{adjustViewBounds=true};slamRight=ImageView(this).apply{adjustViewBounds=true}
        slamBox.addView(slamLeft,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));slamBox.addView(slamRight,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));root.addView(slamBox)
        val scroll=ScrollView(this).apply { addView(root) }; setContentView(scroll)
        reader=UsbGlassesReader(this,this); reader.scan(); enumerateCameras()
    }
    override fun onDestroy(){ reader.close(); (0 until cameraPreviews.childCount).map{cameraPreviews.getChildAt(it)}.filterIsInstance<ExternalCameraPreview>().forEach{it.close()}; super.onDestroy() }
    override fun onDevicesChanged(devices: List<UsbDevice>)=runOnUiThread {
        devicesBox.removeAllViews()
        if(devices.isEmpty()) devicesBox.addView(text("未发现 USB Host 设备。请确认手机支持 OTG，且转接链路保留 USB 数据。"))
        devices.forEach { d ->
            val m=ModelCatalog.identify(d)
            val b=Button(this).apply { text="${m.displayName}  ${d.vendorId.hex4()}:${d.productId.hex4()}"; setOnClickListener { reader.connect(d) } }
            devicesBox.addView(b); devicesBox.addView(text("能力：${m.capabilities}\n${describe(d)}"))
        }
    }
    override fun onStatus(message:String)=runOnUiThread { status.text=message }
    override fun onReading(r:SensorReading)=runOnUiThread {
        fun vec(name:String,v:FloatArray?,unit:String) = v?.let { "$name  ${it.joinToString("  "){x->"%+.4f".format(x)}} $unit\n" } ?: ""
        reading.text=buildString {
            append("${r.source}\n")
            r.timestamp?.let{append("时间戳  $it\n")}; append(vec("加速度",r.accel,"g")); append(vec("角速度",r.gyro,"°/s")); append(vec("磁场",r.magnet,"raw")); append(vec("姿态 R/P/Y",r.orientation,"°"))
            r.temperature?.let{append("温度  %.2f\n".format(it))}; r.proximity?.let{append("接近  %.3f\n".format(it))}; r.ambientLight?.let{append("环境光  %.3f\n".format(it))}; append("原始帧  ${r.rawHex}")
        }
    }
    override fun onSlamFrame(left:android.graphics.Bitmap,right:android.graphics.Bitmap,timestamp:Long)=runOnUiThread {slamLeft.setImageBitmap(left);slamRight.setImageBitmap(right);slamStatus.text="双目 SLAM 640×480 · timestamp $timestamp µs"}
    private fun text(s:String)=TextView(this).apply { text=s; textSize=13f; setTextColor(Color.LTGRAY); setPadding(8,4,8,16) }
    private fun describe(d:UsbDevice)=buildString {
        append("${d.interfaceCount} 个接口")
        for(i in 0 until d.interfaceCount){ val f=d.getInterface(i); append("\n  #${f.id} ${className(f.interfaceClass)} sub=${f.interfaceSubclass} proto=${f.interfaceProtocol}")
            for(e in 0 until f.endpointCount){ val p=f.getEndpoint(e); append("\n    ep ${p.address.hex4()} ${if(p.direction==UsbConstants.USB_DIR_IN)"IN" else "OUT"} ${typeName(p.type)} max=${p.maxPacketSize}") }
        }
    }
    private fun className(c:Int)=when(c){UsbConstants.USB_CLASS_HID->"HID（传感器/按键候选）";UsbConstants.USB_CLASS_VIDEO->"UVC 视频";UsbConstants.USB_CLASS_AUDIO->"USB 音频";UsbConstants.USB_CLASS_VENDOR_SPEC->"厂商自定义";else->"class $c"}
    private fun typeName(t:Int)=when(t){UsbConstants.USB_ENDPOINT_XFER_INT->"interrupt";UsbConstants.USB_ENDPOINT_XFER_BULK->"bulk";UsbConstants.USB_ENDPOINT_XFER_ISOC->"isochronous";else->"control"}
    private fun enumerateCameras(){
        if(ActivityCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),7)
        val cm=getSystemService(CameraManager::class.java)
        val externalIds=cm.cameraIdList.filter { id ->
            val c=cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_EXTERNAL
        }
        cameras.text=externalIds.joinToString("\n") { id ->
            val c=cm.getCameraCharacteristics(id)
            val sizes=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            "Camera2 ID $id · USB/外接 · ${sizes?.joinToString { "${it.width}×${it.height}" } ?: "无 YUV 输出"}"
        }.ifEmpty { "未发现 Camera2 外接摄像头。当前眼镜 USB 描述符也没有 UVC Video 接口。" }
        cameraPreviews.removeAllViews()
        externalIds.forEach { cameraPreviews.addView(ExternalCameraPreview(this,it)) }
    }
}
