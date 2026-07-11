package io.github.sensorprobe

import android.hardware.usb.UsbDevice

data class XrealUsbProfile(
    val officialTypeCode:Int,
    val driverFamily:String,
    val bootloader:Boolean,
    val imuInterface:Int?,
    val mcuInterface:Int?
)

data class GlassesModel(val brand: String, val model: String, val protocol: Protocol, val capabilities:String="USB 接口探测",val xreal:XrealUsbProfile?=null) {
    enum class Protocol { XREAL_AIR, XREAL_LIGHT_MCU, XREAL_LIGHT_OV580, ROKID, GRAWOOW_MCU, GRAWOOW_OV580, MAD_GAZE, VITURE, VITURE_PASSIVE, RAYNEO, GENERIC }
    val displayName get() = "$brand $model"
}

object ModelCatalog {
    private data class XrealEntry(val name:String,val type:Int,val family:String,val boot:Boolean,val imu:Int?,val mcu:Int?)
    private enum class VitureKind { GEN1, GEN2, CARINA, COMPANION }
    private data class VitureEntry(val name:String,val generation:String,val capabilities:String,val kind:VitureKind)
    // Extracted from the official Beam Pro 2.1.0 driver tables. Odd PIDs are
    // bootloader identities; even PIDs are the normal application identities.
    private val xreal = mapOf(
        0x0423 to XrealEntry("Air Bootloader",2,"Air",true,null,null), 0x0424 to XrealEntry("Air",2,"Air",false,3,4),
        0x0425 to XrealEntry("Air 2 Ultra Bootloader",5,"Flora",true,null,null), 0x0426 to XrealEntry("Air 2 Ultra",5,"Flora",false,1,0),
        0x0427 to XrealEntry("Air 2 Bootloader",4,"P55",true,null,null), 0x0428 to XrealEntry("Air 2",4,"P55",false,3,4),
        0x0431 to XrealEntry("Air 2 Pro Bootloader",3,"P55E",true,null,null), 0x0432 to XrealEntry("Air 2 Pro",3,"P55E",false,3,4),
        0x0435 to XrealEntry("One Pro Bootloader",6,"Gina",true,null,null), 0x0436 to XrealEntry("One Pro",6,"Gina",false,null,0),
        0x0437 to XrealEntry("One Bootloader",7,"GF",true,null,null), 0x0438 to XrealEntry("One",7,"GF",false,null,0),
        0x0439 to XrealEntry("Hylla Bootloader",8,"Hylla",true,null,null), 0x043a to XrealEntry("Hylla",8,"Hylla",false,1,0),
        0x043d to XrealEntry("One S Bootloader",9,"GS",true,null,null), 0x043e to XrealEntry("One S",9,"GS",false,null,0),
        0x043f to XrealEntry("XBX A01 Bootloader",10,"Helen",true,null,null), 0x0440 to XrealEntry("XBX A01",10,"Helen",false,1,0),
        0x0441 to XrealEntry("XBX A01 Plus Bootloader",11,"Helen Pro",true,null,null), 0x0442 to XrealEntry("XBX A01 Plus",11,"Helen Pro",false,1,0)
    )
    // VITURE SDK 2.3.2: get_market_name()/is_product_id_valid() and the
    // official Android demo USB filter. 0x1301 (Pro 2) is accepted by the
    // library although it is missing from the demo's older filter XML.
    private val viture = mapOf(
        0x1011 to VitureEntry("One","Gen1","3DoF pose · 原始陀螺仪/加速度计 · VSync · 设备控制",VitureKind.GEN1),
        0x1013 to VitureEntry("One","Gen1","3DoF pose · 原始陀螺仪/加速度计 · VSync · 设备控制",VitureKind.GEN1),
        0x1017 to VitureEntry("One","Gen1","3DoF pose · 原始陀螺仪/加速度计 · VSync · 设备控制",VitureKind.GEN1),
        0x1015 to VitureEntry("Lite","Gen1","3DoF pose · 原始陀螺仪/加速度计 · VSync · 设备控制",VitureKind.GEN1),
        0x101b to VitureEntry("Lite","Gen1","3DoF pose · 原始陀螺仪/加速度计 · VSync · 设备控制",VitureKind.GEN1),
        0x1019 to VitureEntry("Pro","Gen1","3DoF pose · 原始陀螺仪/加速度计 · VSync · 设备控制",VitureKind.GEN1),
        0x101d to VitureEntry("Pro","Gen1","3DoF pose · 原始陀螺仪/加速度计 · VSync · 设备控制",VitureKind.GEN1),
        0x1131 to VitureEntry("Luma","Gen2","3DoF pose · 9轴原始 IMU · VSync · 设备控制",VitureKind.GEN2),
        0x1121 to VitureEntry("Luma Pro","Gen2","3DoF pose · 9轴原始 IMU · VSync · 双目相机 0C45:636B",VitureKind.GEN2),
        0x1141 to VitureEntry("Luma Pro","Gen2","3DoF pose · 9轴原始 IMU · VSync · 双目相机 0C45:636B",VitureKind.GEN2),
        0x1151 to VitureEntry("Luma Cyber","Gen2","3DoF pose · 9轴原始 IMU · VSync · 双目相机 0C45:636B",VitureKind.GEN2),
        0x1101 to VitureEntry("Luma Ultra","Carina","原生 3DoF/6DoF · IMU · VSync · 四路灰度 SLAM · 双目相机 0C45:636B",VitureKind.CARINA),
        // Beast enumerates this composite audio/HID companion separately from
        // the 1201/1211 glasses controller and the 0C45:6368 UVC camera.
        0x1102 to VitureEntry("Beast 伴生音频/HID","Companion","USB 麦克风 · 三组 HID 端点 · Beast 设备控制/传感器伴生接口",VitureKind.COMPANION),
        0x1104 to VitureEntry("Luma Ultra","Carina","原生 3DoF/6DoF · IMU · VSync · 四路灰度 SLAM · 双目相机 0C45:636B",VitureKind.CARINA),
        0x1201 to VitureEntry("Beast","Gen2 Native DOF","眼镜端原生 3DoF · 9轴 IMU · VSync · 单目相机 0C45:6368 · 设备控制",VitureKind.GEN2),
        0x1211 to VitureEntry("Beast","Gen2 Native DOF","眼镜端原生 3DoF · 9轴 IMU · VSync · 单目相机 0C45:6368 · 设备控制",VitureKind.GEN2),
        0x1301 to VitureEntry("Pro 2","Gen2","3DoF pose · 原始 IMU · VSync · 设备控制",VitureKind.GEN2)
    )

    fun identify(d: UsbDevice): GlassesModel = when (d.vendorId) {
        0x3318 -> xreal[d.productId]?.let { e ->
            val profile=XrealUsbProfile(e.type,e.family,e.boot,e.imu,e.mcu)
            GlassesModel("XREAL",e.name,if(e.boot)GlassesModel.Protocol.GENERIC else GlassesModel.Protocol.XREAL_AIR,
                if(e.boot)"官方 ${e.family} Bootloader · 仅识别/固件模式" else "官方 type ${e.type} / ${e.family} · IMU · VSync · MCU 事件 · 显示模式",profile)
        } ?: GlassesModel("XREAL",d.productName?:"未知型号 (PID ${d.productId.hex4()})",GlassesModel.Protocol.GENERIC,"官方 APK 未收录的 0x3318 设备")
        0x0486 -> if(d.productId==0x573c) GlassesModel("XREAL", "Light MCU", GlassesModel.Protocol.XREAL_LIGHT_MCU,"按键 · 接近 · 环境光 · VSync · 显示模式") else GlassesModel("USB",d.productName?:"未知设备",GlassesModel.Protocol.GENERIC)
        0x05a9 -> when(d.productId){
            0x0680 -> GlassesModel("XREAL","Light OV580",GlassesModel.Protocol.XREAL_LIGHT_OV580,"IMU · RGB相机 · 双SLAM相机")
            0x0f87 -> GlassesModel("Grawoow / MetaVision","G530 / M53 OV580",GlassesModel.Protocol.GRAWOOW_OV580,"IMU · 复合视频模块")
            else -> GlassesModel("OVT",d.productName?:"OV580",GlassesModel.Protocol.GENERIC)
        }
        0x04d2 -> if(d.productId==0x162f) GlassesModel("Rokid",if(d.productName?.contains("Max",true)==true)"Max" else "Air",GlassesModel.Protocol.ROKID,"IMU · 磁力计 · 按键 · 接近 · 显示模式") else GlassesModel("Rokid",d.productName?:"未知型号",GlassesModel.Protocol.GENERIC)
        0x1ff7 -> if(d.productId==0x0ff4) GlassesModel("Grawoow / MetaVision","G530 / M53 MCU",GlassesModel.Protocol.GRAWOOW_MCU,"序列号 · 显示模式 · 校准数据") else GlassesModel("USB",d.productName?:"未知设备",GlassesModel.Protocol.GENERIC)
        0x04b4 -> if(d.productId==0x0002) GlassesModel("Mad Gaze","Glow",GlassesModel.Protocol.MAD_GAZE,"USB串口 · IMU · 磁力计 · 显示模式") else GlassesModel("Cypress",d.productName?:"未知设备",GlassesModel.Protocol.GENERIC)
        0x35ca -> viture[d.productId]?.let { e ->
            val protocol=when(e.kind){VitureKind.GEN1->GlassesModel.Protocol.VITURE;VitureKind.GEN2->GlassesModel.Protocol.VITURE_PASSIVE;VitureKind.CARINA,VitureKind.COMPANION->GlassesModel.Protocol.GENERIC}
            GlassesModel("VITURE",e.name,protocol,"官方 SDK ${e.generation} · ${e.capabilities}"+(if(e.kind==VitureKind.CARINA)" · Carina 原生流待实机验证" else ""))
        }
            ?: GlassesModel("VITURE","未知型号 (PID ${d.productId.hex4()})",GlassesModel.Protocol.GENERIC,"VITURE SDK 2.3.2 未收录")
        0x0c45 -> when(d.productId) {
            0x636b -> GlassesModel("VITURE","Luma 系列双目相机",GlassesModel.Protocol.GENERIC,"UVC/USB 相机 · Luma Pro/Cyber/Ultra")
            0x6368 -> GlassesModel("VITURE","Beast 单目相机",GlassesModel.Protocol.GENERIC,"UVC/USB 单目相机 · Beast")
            else -> GlassesModel(d.manufacturerName?:"Sonix",d.productName?:"USB Camera",GlassesModel.Protocol.GENERIC)
        }
        0x1bbb -> GlassesModel("RayNeo", if (d.productId == 0xaf50) "Air 3S Pro" else "未知型号 (PID ${d.productId.hex4()})", if (d.productId == 0xaf50) GlassesModel.Protocol.RAYNEO else GlassesModel.Protocol.GENERIC)
        else -> GlassesModel(d.manufacturerName ?: "USB", d.productName ?: "未知设备", GlassesModel.Protocol.GENERIC)
    }
}

fun Int.hex4() = "0x%04X".format(this)

data class SensorReading(
    val source: String,
    val timestamp: Long? = null,
    val accel: FloatArray? = null,
    val gyro: FloatArray? = null,
    val magnet: FloatArray? = null,
    val orientation: FloatArray? = null,
    val temperature: Float? = null,
    val proximity: Float? = null,
    val ambientLight: Float? = null,
    val rawHex: String = "",
    val timestampUnit: String = "ns",
    val accelUnit: String = "g",
    val gyroUnit: String = "°/s",
    val magnetUnit: String = "raw"
)
