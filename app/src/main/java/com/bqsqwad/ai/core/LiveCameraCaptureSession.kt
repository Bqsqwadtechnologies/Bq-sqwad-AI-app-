package com.bqsqwad.ai.core

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.util.concurrent.atomic.AtomicBoolean

class LiveCameraCaptureSession(
    private val context: Context,
    private val lensFacing: Int,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val thread = HandlerThread("bq-live-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var stopped = false

    @SuppressLint("MissingPermission")
    fun start() {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: error("Camera service is unavailable.")
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lensFacing }
            ?: manager.cameraIdList.firstOrNull()
            ?: error("No camera is available.")
        val characteristics = manager.getCameraCharacteristics(id)
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.JPEG).orEmpty()
        val size = sizes.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width * it.height }
            ?: sizes.firstOrNull()
            ?: Size(640, 480)
        reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 2).also {
            it.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use { img ->
                    val buffer = img.planes.firstOrNull()?.buffer ?: return@use
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    if (!stopped) onFrame(bytes)
                }
            }, handler)
        }
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (stopped) { device.close(); return }
                camera = device
                val surface = reader!!.surface
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (stopped) { session.close(); return }
                        capture = session
                        runCatching {
                            session.setRepeatingRequest(
                                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surface)
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                }.build(),
                                null,
                                handler
                            )
                        }.onFailure(onError)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError(IllegalStateException("Camera capture session could not be configured."))
                    }
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close()
                onError(IllegalStateException("Camera disconnected."))
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                onError(IllegalStateException("Camera error: $error"))
            }
        }, handler)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.post {
            runCatching { capture?.stopRepeating() }
            capture?.close()
            capture = null
            camera?.close()
            camera = null
            reader?.close()
            reader = null
            thread.quitSafely()
        }
    }
}
