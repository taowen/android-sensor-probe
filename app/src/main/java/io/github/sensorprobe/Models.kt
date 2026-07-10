package io.github.sensorprobe

import android.hardware.usb.UsbDevice

data class GlassesModel(val brand: String, val model: String, val protocol: Protocol, val capabilities:String="USB 接口探测") {
    enum class Protocol { XREAL_AIR, XREAL_LIGHT_MCU, XREAL_LIGHT_OV580, ROKID, GRAWOOW_MCU, GRAWOOW_OV580, MAD_GAZE, VITURE, RAYNEO, GENERIC }
    val displayName get() = "$brand $model"
}

object ModelCatalog {
    private val xreal = mapOf(
        0x0424 to "Air", 0x0428 to "Air 2", 0x0432 to "Air 2 Pro",
        0x0426 to "Air 2 Ultra", 0x0435 to "One Pro", 0x0436 to "One Pro",
        0x0437 to "One", 0x0438 to "One", 0x043e to "One S", 0x043d to "One S",
        0x0440 to "xbx a01"
    )
    private val viture = mapOf(
        0x1011 to "One", 0x1013 to "One", 0x1017 to "One",
        0x1015 to "One Lite", 0x101b to "One Lite", 0x1019 to "Pro", 0x101d to "Pro",
        0x1131 to "Luma", 0x1121 to "Luma Pro", 0x1141 to "Luma Pro",
        0x1101 to "Luma Ultra", 0x1104 to "Luma Ultra", 0x1151 to "Luma Cyber", 0x1201 to "Beast"
    )

    fun identify(d: UsbDevice): GlassesModel = when (d.vendorId) {
        0x3318 -> GlassesModel("XREAL", xreal[d.productId] ?: "未知型号 (PID ${d.productId.hex4()})", GlassesModel.Protocol.XREAL_AIR, "IMU · 磁力计 · 按键 · 显示模式")
        0x0486 -> if(d.productId==0x573c) GlassesModel("XREAL", "Light MCU", GlassesModel.Protocol.XREAL_LIGHT_MCU,"按键 · 接近 · 环境光 · VSync · 显示模式") else GlassesModel("USB",d.productName?:"未知设备",GlassesModel.Protocol.GENERIC)
        0x05a9 -> when(d.productId){
            0x0680 -> GlassesModel("XREAL","Light OV580",GlassesModel.Protocol.XREAL_LIGHT_OV580,"IMU · RGB相机 · 双SLAM相机")
            0x0f87 -> GlassesModel("Grawoow / MetaVision","G530 / M53 OV580",GlassesModel.Protocol.GRAWOOW_OV580,"IMU · 复合视频模块")
            else -> GlassesModel("OVT",d.productName?:"OV580",GlassesModel.Protocol.GENERIC)
        }
        0x04d2 -> if(d.productId==0x162f) GlassesModel("Rokid",if(d.productName?.contains("Max",true)==true)"Max" else "Air",GlassesModel.Protocol.ROKID,"IMU · 磁力计 · 按键 · 接近 · 显示模式") else GlassesModel("Rokid",d.productName?:"未知型号",GlassesModel.Protocol.GENERIC)
        0x1ff7 -> if(d.productId==0x0ff4) GlassesModel("Grawoow / MetaVision","G530 / M53 MCU",GlassesModel.Protocol.GRAWOOW_MCU,"序列号 · 显示模式 · 校准数据") else GlassesModel("USB",d.productName?:"未知设备",GlassesModel.Protocol.GENERIC)
        0x04b4 -> if(d.productId==0x0002) GlassesModel("Mad Gaze","Glow",GlassesModel.Protocol.MAD_GAZE,"USB串口 · IMU · 磁力计 · 显示模式") else GlassesModel("Cypress",d.productName?:"未知设备",GlassesModel.Protocol.GENERIC)
        0x35ca -> GlassesModel("VITURE", viture[d.productId] ?: "未知型号 (PID ${d.productId.hex4()})", GlassesModel.Protocol.VITURE)
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
    val rawHex: String = ""
)
