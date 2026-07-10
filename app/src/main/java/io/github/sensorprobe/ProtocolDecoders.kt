package io.github.sensorprobe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

interface GlassesProtocol {
    fun startCommand(): ByteArray?
    fun decode(bytes: ByteArray, length: Int): SensorReading?
}

object RawUsbProtocol:GlassesProtocol {
    override fun startCommand():ByteArray?=null
    override fun decode(bytes:ByteArray,length:Int)=SensorReading("USB MCU / serial raw · $length bytes",rawHex=bytes.hex(length))
}

object XrealLightMcuProtocol:GlassesProtocol {
    private fun packet(category:Char,command:Char,data:String):ByteArray {
        val prefix="\u0002:$category:$command:$data:0:".encodeToByteArray()
        val crc=java.util.zip.Adler32().apply{update(prefix)}.value.toString(16).padStart(8,'0')
        return ByteArray(64).also { (prefix+crc.encodeToByteArray()+byteArrayOf(':'.code.toByte(),3)).copyInto(it) }
    }
    override fun startCommand()=packet('@','3',"1")
    fun ambientEnable()=packet('1','L',"1")
    fun vsyncEnable()=packet('1','N',"1")
    fun displayMode(mode:Char)=packet('1','3',mode.toString())
    override fun decode(bytes:ByteArray,length:Int):SensorReading? {
        if(length<4||bytes[0].toInt()!=2)return null
        val end=(1 until length).firstOrNull{bytes[it].toInt()==3}?:length
        val s=bytes.copyOfRange(1,end).decodeToString()
        return when {
            s.contains(":5:K:UP") -> SensorReading("XREAL Light MCU · 按键 UP",rawHex=bytes.hex(length))
            s.contains(":5:K:DN") -> SensorReading("XREAL Light MCU · 按键 DOWN",rawHex=bytes.hex(length))
            s.contains(":5:P:near") -> SensorReading("XREAL Light MCU · 接近：已佩戴",proximity=1f,rawHex=bytes.hex(length))
            s.contains(":5:P:away") -> SensorReading("XREAL Light MCU · 接近：已摘下",proximity=0f,rawHex=bytes.hex(length))
            s.contains(":5:L:") -> SensorReading("XREAL Light MCU · 环境光",ambientLight=s.substringAfter(":5:L:").substringBefore(':').toFloatOrNull(),rawHex=bytes.hex(length))
            s.contains(":5:N:") -> SensorReading("XREAL Light MCU · VSync",rawHex=bytes.hex(length))
            else -> SensorReading("XREAL Light MCU",rawHex=bytes.hex(length))
        }
    }
}

object XrealProtocol : GlassesProtocol {
    private const val GYRO_SCALE = 2000f / 8388608f
    private const val ACCEL_SCALE = 16f / 8388608f
    override fun startCommand() = startCommand(1.toByte())
    fun startCommand(state: Byte) = command(0x19, byteArrayOf(state))
    fun command(msgId: Int, data: ByteArray = byteArrayOf()): ByteArray {
        val packetLength=3+data.size
        val body = byteArrayOf(packetLength.toByte(), (packetLength shr 8).toByte(), msgId.toByte()) + data
        val crc = CRC32().apply { update(body) }.value
        return ByteArray(body.size+5).also { out ->
            out[0]=0xAA.toByte(); out[1]=crc.toByte(); out[2]=(crc shr 8).toByte(); out[3]=(crc shr 16).toByte(); out[4]=(crc shr 24).toByte()
            body.copyInto(out,5)
        }
    }
    override fun decode(bytes: ByteArray, length: Int): SensorReading? {
        if (length != 64 || bytes[0].toInt() != 1) return null
        fun i24(o: Int): Int { val n = (bytes[o].toInt() and 255) or ((bytes[o+1].toInt() and 255) shl 8) or ((bytes[o+2].toInt() and 255) shl 16); return if (n and 0x800000 != 0) n or -0x1000000 else n }
        fun i16(o: Int) = ((bytes[o].toInt() and 255) or (bytes[o+1].toInt() shl 8)).toShort().toInt()
        val timestamp = ByteBuffer.wrap(bytes, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return SensorReading("XREAL HID IMU", timestamp,
            floatArrayOf(i24(33)*ACCEL_SCALE, i24(36)*ACCEL_SCALE, i24(39)*ACCEL_SCALE),
            floatArrayOf(i24(18)*GYRO_SCALE, i24(21)*GYRO_SCALE, i24(24)*GYRO_SCALE),
            floatArrayOf(i16(48).toFloat(), i16(50).toFloat(), i16(52).toFloat()),
            temperature = ByteBuffer.wrap(bytes, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toFloat() / 132.48f + 25f, rawHex = bytes.hex(length))
    }
}

object XbxA01Protocol : GlassesProtocol {
    override fun startCommand() = XrealProtocol.startCommand(1)
    override fun decode(bytes: ByteArray, length: Int):SensorReading? {
        if(length>=9 && bytes[0]==0xaa.toByte() && (bytes[7].toInt() and 255)==0x19) {
            val status=bytes[8].toInt() and 255
            return SensorReading("XBX A01 / Helen · IMU 启动 ACK · ${if(status==0) "成功" else "错误 $status"}",rawHex=bytes.hex(length))
        }
        if(length!=64 || bytes[0].toInt()!=1 || bytes[1].toInt()!=2) return null
        fun u16(o:Int)=(bytes[o].toInt() and 255) or ((bytes[o+1].toInt() and 255) shl 8)
        fun i16(o:Int)=u16(o).toShort().toInt()
        fun i24(o:Int):Int { val v=(bytes[o].toInt() and 255) or ((bytes[o+1].toInt() and 255) shl 8) or ((bytes[o+2].toInt() and 255) shl 16); return if(v and 0x800000!=0)v-0x1000000 else v }
        fun i32(o:Int)=ByteBuffer.wrap(bytes,o,4).order(ByteOrder.LITTLE_ENDIAN).int
        val gd=i32(0x0E); val ad=i32(0x1D); val md=i32(0x2C); val mo=i16(0x2A)
        val gyro=if(gd!=0) FloatArray(3){i24(0x12+it*3)*u16(0x0C).toFloat()/gd} else null
        val accel=if(ad!=0) FloatArray(3){i24(0x21+it*3)*u16(0x1B).toFloat()/ad} else null
        val magnet=if(md!=0) FloatArray(3){(i16(0x30+it*2)-mo).toFloat()/md} else null
        val timestamp=ByteBuffer.wrap(bytes,4,8).order(ByteOrder.LITTLE_ENDIAN).long
        return SensorReading("XBX a01 HID IMU",timestamp,accel,gyro,magnet,temperature=i16(2)/326.8f+25f,rawHex=bytes.hex(length))
    }
}

object VitureProtocol : GlassesProtocol {
    override fun startCommand(): ByteArray {
        val p = ByteArray(20); p[0]=0xff.toByte(); p[1]=0xfe.toByte(); p[4]=16; p[14]=0x15; p[16]=1; p[18]=1; p[19]=3
        val crc = crc16(p, 4, p.size - 4); p[2]=(crc shr 8).toByte(); p[3]=crc.toByte(); return p
    }
    override fun decode(bytes: ByteArray, length: Int): SensorReading? {
        if (length < 30 || bytes[0] != 0xff.toByte() || bytes[1] != 0xfc.toByte()) return null
        fun swappedFloat(o: Int): Float = ByteBuffer.wrap(byteArrayOf(bytes[o+1],bytes[o],bytes[o+3],bytes[o+2])).order(ByteOrder.BIG_ENDIAN).float
        val yaw=-swappedFloat(18); val roll=-swappedFloat(22); val pitch=swappedFloat(26)
        if (!yaw.isFinite() || !roll.isFinite() || !pitch.isFinite()) return null
        return SensorReading("VITURE HID pose", orientation=floatArrayOf(roll,pitch,yaw), rawHex=bytes.hex(length))
    }
    private fun crc16(d: ByteArray, from: Int, len: Int): Int { var crc=0xffff; for(i in from until from+len){ crc=crc xor ((d[i].toInt() and 255) shl 8); repeat(8){crc=if(crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1; crc=crc and 0xffff} }; return crc }
}

/** VITURE Gen2 raw report used by Luma and Beast (HID interface 5 on Beast). */
object VitureGen2RawProtocol : GlassesProtocol {
    override fun startCommand()=command(0x0301,byteArrayOf(2,2))

    fun command(messageId:Int,payload:ByteArray=byteArrayOf()):ByteArray {
        val checksum=payload.sumOf{it.toInt() and 0xff} and 0xffff
        return ByteBuffer.allocate(8+payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x10.toShort());putShort(messageId.toShort());putShort(payload.size.toShort());putShort(checksum.toShort());put(payload)
        }.array()
    }

    override fun decode(bytes:ByteArray,length:Int):SensorReading? {
        if(length<8 || bytes[0]!=0x10.toByte() || bytes[1]!=0.toByte())return null
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val messageId=b.getShort(2).toInt() and 0xffff
        val payloadLength=b.getShort(4).toInt() and 0xffff
        if(payloadLength>length-8)return null
        val expected=b.getShort(6).toInt() and 0xffff
        val actual=(8 until 8+payloadLength).sumOf{bytes[it].toInt() and 0xff} and 0xffff
        if(expected!=actual)return null
        if(messageId!=0x7309) {
            val status=bytes.getOrNull(8)?.toInt()?.and(0xff)
            val value=bytes.getOrNull(9)?.toInt()?.and(0xff)
            val source=when(messageId) {
                0x2301->"VITURE RAW IMU 启动 ACK · status=$status"
                0x5140->"VITURE Beast 模式 · ${if(value==1)"Native" else if(value==0)"Bypass" else "未知 $value"}"
                0x5142->"VITURE Beast 显示 · ${if(value==0x31)"2D" else if(value==0x37)"3D" else "mode=${value?.hex4()}"}"
                0x2142->"VITURE Beast 显示模式设置 ACK · status=$status"
                else->"VITURE V2 response ${messageId.hex4()} · status=$status value=$value"
            }
            return SensorReading(source,rawHex=bytes.hex(length))
        }
        if(length<64)return null
        fun v3(offset:Int)=floatArrayOf(b.getFloat(offset),b.getFloat(offset+4),b.getFloat(offset+8))
        val temperature=(b.getShort(16).toInt() and 0xffff)/5f
        val timestamp=(b.getInt(60).toLong() and 0xffffffffL)*1000L
        val gyro=v3(18);val accel=v3(30);val magnet=v3(42)
        if((gyro+accel+magnet).any{!it.isFinite()})return null
        return SensorReading("VITURE Gen2 RAW IMU (g, rad/s, µT)",timestamp,accel,gyro,magnet,temperature=temperature,rawHex=bytes.hex(length))
    }
}

object RayneoProtocol : GlassesProtocol {
    override fun startCommand() = ByteArray(64).also { it[0]=0x66; it[1]=1 }
    override fun decode(bytes: ByteArray, length: Int): SensorReading? {
        if(length < 58 || bytes[0] != 0x99.toByte() || bytes[1] != 0x65.toByte()) return null
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return SensorReading("RayNeo HID IMU", b.getInt(40).toLong() and 0xffffffffL,
            floatArrayOf(b.getFloat(4),b.getFloat(8),b.getFloat(12)), floatArrayOf(b.getFloat(16),b.getFloat(20),b.getFloat(24)),
            floatArrayOf(b.getFloat(32),b.getFloat(36),b.getFloat(52)), temperature=b.getFloat(28), proximity=b.getFloat(44), ambientLight=b.getFloat(48), rawHex=bytes.hex(length))
    }
}

object RokidProtocol : GlassesProtocol {
    override fun startCommand():ByteArray?=null
    override fun decode(bytes:ByteArray,length:Int):SensorReading? {
        if(length<33)return null
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun v3(o:Int)=floatArrayOf(b.getFloat(o),b.getFloat(o+4),b.getFloat(o+8))
        return when(bytes[0].toInt() and 255) {
            17 -> if(length>=47) SensorReading("Rokid combined IMU (m/s², rad/s, µT)",b.getLong(1)/1000, v3(9),v3(21),v3(33),proximity=(bytes[46].toInt() and 255).toFloat(),rawHex=bytes.hex(length)) else null
            4 -> when(bytes[1].toInt() and 255) {
                1 -> SensorReading("Rokid accelerometer (m/s²)",b.getLong(9),accel=v3(21),rawHex=bytes.hex(length))
                2 -> SensorReading("Rokid gyroscope (rad/s)",b.getLong(9),gyro=v3(21),rawHex=bytes.hex(length))
                3 -> SensorReading("Rokid magnetometer (µT)",b.getLong(9),magnet=v3(21),rawHex=bytes.hex(length))
                else -> null
            }
            2 -> SensorReading("Rokid keys/proximity",proximity=(bytes.getOrNull(51)?.toInt()?.and(255)?:0).toFloat(),rawHex=bytes.hex(length))
            else -> null
        }
    }
}

object XrealLightOv580Protocol:GlassesProtocol {
    override fun startCommand()=byteArrayOf(2,0x19,1,0,0,0,0)
    override fun decode(bytes:ByteArray,length:Int):SensorReading? {
        if(length<108 || bytes[0].toInt()!=1)return null
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val gt=b.getLong(44)/1000; val gm=b.getInt(52).toFloat(); val gd=b.getInt(56).toFloat()
        val at=b.getLong(72)/1000; val am=b.getInt(80).toFloat(); val ad=b.getInt(84).toFloat()
        if(gd==0f||ad==0f)return null
        val gyro=floatArrayOf(b.getInt(60)*gm/gd,b.getInt(64)*gm/gd,b.getInt(68)*gm/gd)
        val accel=floatArrayOf(b.getInt(88)*am/ad*9.81f,b.getInt(92)*am/ad*9.81f,b.getInt(96)*am/ad*9.81f)
        return SensorReading("XREAL Light OV580 IMU (m/s², °/s)",minOf(gt,at),accel,gyro,rawHex=bytes.hex(length))
    }
}

object GrawoowOv580Protocol:GlassesProtocol {
    override fun startCommand():ByteArray?=null
    override fun decode(bytes:ByteArray,length:Int):SensorReading? {
        if(length<100)return null
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val g=(Math.PI/180.0/16.4).toFloat(); val a=9.81f/16384f
        val gx=b.getInt(0x3c)*g; val gy=b.getInt(0x40)*g; val gz=b.getInt(0x44)*g
        val ax=b.getInt(0x58)*a; val ay=b.getInt(0x5c)*a; val az=b.getInt(0x60)*a
        return SensorReading("Grawoow G530 / M53 IMU (m/s², rad/s)",accel=floatArrayOf(-ay,-az,ax),gyro=floatArrayOf(-gy,-gz,gx),rawHex=bytes.hex(length))
    }
}

internal fun ByteArray.hex(n: Int) = take(minOf(n, 64)).joinToString(" ") { "%02X".format(it.toInt() and 255) }
