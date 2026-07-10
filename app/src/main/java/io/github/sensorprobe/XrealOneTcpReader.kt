package io.github.sensorprobe

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** XREAL One/One Pro Ethernet IMU stream documented by the open-source One drivers. */
class XrealOneTcpReader(
    private val onReading:(SensorReading)->Unit,
    private val onStatus:(String)->Unit
):Closeable {
    private val running=AtomicBoolean(true)
    private var socket:Socket?=null
    private val thread=Thread(::run,"xreal-helen-tcp-imu")
    fun start()=thread.start()
    private fun run() {
        val s=Socket();socket=s
        try {
            s.connect(InetSocketAddress("169.254.2.1",52998),2000);s.soTimeout=3000
            onStatus("XREAL Ethernet IMU 已连接 169.254.2.1:52998")
            val stream=s.getInputStream();val pending=ArrayList<Byte>();val chunk=ByteArray(4096)
            while(running.get()) {
                val n=stream.read(chunk);if(n<0)break
                repeat(n){pending.add(chunk[it])}
                while(true) {
                    val start=findHeader(pending);if(start<0){if(pending.size>6)pending.subList(0,pending.size-6).clear();break}
                    if(start>0)pending.subList(0,start).clear();if(pending.size<84)break
                    val frame=ByteArray(84){pending[it]};pending.subList(0,84).clear();decode(frame)?.let(onReading)
                }
            }
        } catch(e:Exception) {
            if(running.get())onStatus("XREAL Ethernet IMU 链路不可达（需 USB 网络链路）")
        } finally { try{s.close()}catch(_:Exception){} }
    }
    private fun findHeader(data:List<Byte>):Int {
        val h=byteArrayOf(0x28,0x36,0,0,0,0x80.toByte())
        return (0..data.size-h.size).firstOrNull{i->h.indices.all{data[i+it]==h[it]}}?:-1
    }
    private fun decode(frame:ByteArray):SensorReading? {
        val marker=byteArrayOf(0,0x40,0x1f,0,0,0x40)
        if((0..frame.size-marker.size).none{i->marker.indices.all{frame[i+it]==marker[it]}})return null
        val b=ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);val timestamp=b.getLong(14)/1000
        val gx=b.getFloat(34);val gy=b.getFloat(38);val gz=b.getFloat(42)
        val ax=b.getFloat(46);val ay=b.getFloat(50);val az=b.getFloat(54)
        if(listOf(gx,gy,gz,ax,ay,az).any{!it.isFinite()} || kotlin.math.sqrt(ax*ax+ay*ay+az*az) !in 5f..15f)return null
        return SensorReading("XREAL One/One Pro TCP IMU",timestamp,
            accel=floatArrayOf(-ax,-az,-ay),gyro=floatArrayOf(-gx,-gz,-gy),rawHex=frame.hex(frame.size))
    }
    override fun close(){running.set(false);try{socket?.close()}catch(_:Exception){};thread.interrupt()}
}
