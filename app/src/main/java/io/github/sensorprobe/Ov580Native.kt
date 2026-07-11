package io.github.sensorprobe

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

object Ov580Native {
    init { System.loadLibrary("sensorprobe_native") }
    external fun start(javaFd:Int):Long
    external fun readFrame(nativeHandle:Long):ByteArray?
    external fun stop(nativeHandle:Long)
}

class Ov580SlamReader(javaFd:Int,private val callback:(Bitmap,Bitmap,Long)->Unit,private val recorder:DiagnosticRecorder):AutoCloseable {
    private val fd=Ov580Native.start(javaFd)
    private val running=AtomicBoolean(fd!=0L)
    private val thread=Thread({loop()},"ov580-slam")
    init{if(fd!=0L)thread.start()}
    val started get()=fd!=0L
    private fun loop(){while(running.get()){val p=Ov580Native.readFrame(fd)?:continue;val now=android.os.SystemClock.elapsedRealtimeNanos();val left=gray(p,0);val right=gray(p,640*480);val ts=ByteBuffer.wrap(p,640*480*2,8).order(ByteOrder.LITTLE_ENDIAN).long/1000+37600;recorder.cameraFrame("ov580_slam",now,ts,p.size,true);callback(left,right,ts)}}
    private fun gray(p:ByteArray,off:Int):Bitmap {val colors=IntArray(640*480){i->val y=p[off+i].toInt() and 255;Color.rgb(y,y,y)};return Bitmap.createBitmap(colors,640,480,Bitmap.Config.ARGB_8888)}
    override fun close(){if(running.getAndSet(false)){Ov580Native.stop(fd);thread.interrupt()}}
}
