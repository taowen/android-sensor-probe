package io.github.sensorprobe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.atomic.AtomicBoolean

object UvcNative {
    init { System.loadLibrary("sensorprobe_native") }
    external fun start(javaFd:Int):Long
    external fun readFrame(handle:Long):ByteArray?
    external fun stop(handle:Long)
}

class UvcCameraReader(javaFd:Int,private val callback:(Bitmap)->Unit,private val recorder:DiagnosticRecorder):AutoCloseable {
    private val handle=if(javaFd>=0)UvcNative.start(javaFd)else 0L
    private val running=AtomicBoolean(handle!=0L)
    private val thread=Thread({while(running.get())UvcNative.readFrame(handle)?.let{jpeg->val now=android.os.SystemClock.elapsedRealtimeNanos();val bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.size);recorder.cameraFrame("uvc",now,null,jpeg.size,bitmap!=null);bitmap?.let(callback)}} ,"viture-uvc")
    init{if(running.get())thread.start()}
    val started get()=running.get()
    val transport get()="libusb isochronous"
    override fun close(){if(running.getAndSet(false)){UvcNative.stop(handle);thread.interrupt()}}
}
