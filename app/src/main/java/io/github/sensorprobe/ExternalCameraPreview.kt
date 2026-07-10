package io.github.sensorprobe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView

class ExternalCameraPreview(context:Context, private val cameraId:String):FrameLayout(context),TextureView.SurfaceTextureListener {
    private val texture=TextureView(context)
    private val thread=HandlerThread("external-camera-$cameraId").apply{start()}
    private val handler=Handler(thread.looper)
    private var device:CameraDevice?=null
    init {
        addView(texture,LayoutParams(LayoutParams.MATCH_PARENT,560))
        addView(TextView(context).apply{text="外接 Camera2 · ID $cameraId";setPadding(12,12,12,12)})
        texture.surfaceTextureListener=this
    }
    private fun open(st:SurfaceTexture){
        if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return
        val cm=context.getSystemService(CameraManager::class.java)
        val size=cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)?.maxByOrNull{it.width*it.height}
        size?.let{st.setDefaultBufferSize(it.width,it.height)}
        cm.openCamera(cameraId,object:CameraDevice.StateCallback(){
            override fun onOpened(c:CameraDevice){device=c;val surface=Surface(st);c.createCaptureSession(listOf(surface),object:CameraCaptureSession.StateCallback(){
                override fun onConfigured(s:CameraCaptureSession){s.setRepeatingRequest(c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{addTarget(surface)}.build(),null,handler)}
                override fun onConfigureFailed(s:CameraCaptureSession){}
            },handler)}
            override fun onDisconnected(c:CameraDevice){c.close()}
            override fun onError(c:CameraDevice,error:Int){c.close()}
        },handler)
    }
    override fun onSurfaceTextureAvailable(s:SurfaceTexture,w:Int,h:Int)=open(s)
    override fun onSurfaceTextureSizeChanged(s:SurfaceTexture,w:Int,h:Int){}
    override fun onSurfaceTextureDestroyed(s:SurfaceTexture):Boolean{device?.close();device=null;return true}
    override fun onSurfaceTextureUpdated(s:SurfaceTexture){}
    fun close(){device?.close();thread.quitSafely()}
}
