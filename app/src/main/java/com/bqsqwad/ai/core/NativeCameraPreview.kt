package com.bqsqwad.ai.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

@Composable
fun NativeCameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK,
    captureEveryMs: Long = 2500L,
    onFrame: (ByteArray) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            LiveCameraTextureView(context).apply {
                facing = lensFacing
                intervalMs = captureEveryMs
                frameListener = onFrame
            }
        },
        update = {
            it.facing = lensFacing
            it.intervalMs = captureEveryMs
            it.frameListener = onFrame
            it.startIfReady()
        }
    )
}

private class LiveCameraTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    var facing: Int = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
    var intervalMs: Long = 2500L
    var frameListener: (ByteArray) -> Unit = {}

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("bq-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val lastFrameAt = AtomicLong(0L)

    init {
        surfaceTextureListener = this
    }

    fun startIfReady() {
        if (isAvailable && camera == null) openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(
                android.hardware.camera2.CameraCharacteristics.LENS_FACING
            ) == facing
        } ?: cameraManager.cameraIdList.firstOrNull() ?: return

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                createSession(device)
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close()
                camera = null
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                camera = null
            }
        }, handler)
    }

    private fun createSession(device: CameraDevice) {
        val texture = surfaceTexture ?: return
        texture.setDefaultBufferSize(width.coerceAtLeast(640), height.coerceAtLeast(480))
        val surface = Surface(texture)
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(created: CameraCaptureSession) {
                session = created
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                created.setRepeatingRequest(request, null, handler)
                scheduleFrame()
            }
            override fun onConfigureFailed(created: CameraCaptureSession) = Unit
        }, handler)
    }

    private fun scheduleFrame() {
        handler.postDelayed({
            if (!isAvailable || camera == null) return@postDelayed
            val now = System.currentTimeMillis()
            if (now - lastFrameAt.get() >= intervalMs) {
                val bitmap = getBitmap(960, 540)
                if (bitmap != null) {
                    val output = ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, output)
                    bitmap.recycle()
                    lastFrameAt.set(now)
                    frameListener(output.toByteArray())
                }
            }
            scheduleFrame()
        }, intervalMs.coerceAtLeast(1000L))
    }

    private fun closeCamera() {
        session?.close()
        session = null
        camera?.close()
        camera = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startIfReady()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        closeCamera()
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        closeCamera()
        thread.quitSafely()
        super.onDetachedFromWindow()
    }
}
