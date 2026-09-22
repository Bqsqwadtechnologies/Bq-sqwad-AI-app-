package com.bqsqwad.ai.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
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
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native camera preview used by AI Live Camera.
 *
 * The preview and frame sampler are deliberately lifecycle-bound to this view:
 * - camera permission is checked before opening the device;
 * - changing the lens closes the old device/session before reopening;
 * - frame callbacks are sampled off the UI thread;
 * - camera/session/handler resources are released when the view is detached.
 */
@Composable
fun NativeCameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
    captureEveryMs: Long = 1800L,
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
        update = { view ->
            view.updateConfiguration(lensFacing, captureEveryMs, onFrame)
        }
    )
}

private class LiveCameraTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    var facing: Int = CameraCharacteristics.LENS_FACING_BACK
        private set
    var intervalMs: Long = 1800L
        private set
    var frameListener: (ByteArray) -> Unit = {}

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("bq-live-camera").also { it.start() }
    private val handler = Handler(thread.looper)

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var previewSurface: Surface? = null
    private var frameTaskPosted = false
    private val closed = AtomicBoolean(false)

    init {
        surfaceTextureListener = this
    }

    fun updateConfiguration(
        newFacing: Int,
        newIntervalMs: Long,
        listener: (ByteArray) -> Unit
    ) {
        frameListener = listener
        intervalMs = newIntervalMs.coerceIn(1000L, 10000L)
        if (facing != newFacing) {
            facing = newFacing
            handler.post {
                if (!closed.get()) {
                    closeCamera()
                    if (isAvailable) openCamera()
                }
            }
        } else {
            startIfReady()
        }
    }

    fun startIfReady() {
        if (closed.get() || !isAvailable || camera != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (closed.get() || camera != null) return

        val id = runCatching {
            cameraManager.cameraIdList.firstOrNull { value ->
                cameraManager.getCameraCharacteristics(value)
                    .get(CameraCharacteristics.LENS_FACING) == facing
            } ?: cameraManager.cameraIdList.firstOrNull()
        }.getOrNull() ?: return

        cameraId = id
        runCatching {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed.get() || !isAvailable || cameraId != id) {
                        device.close()
                        return
                    }
                    camera = device
                    createSession(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    if (camera === device) camera = null
                    device.close()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    if (camera === device) camera = null
                    device.close()
                }
            }, handler)
        }
    }

    private fun createSession(device: CameraDevice) {
        val texture = surfaceTexture ?: return
        val bufferWidth = width.coerceAtLeast(640)
        val bufferHeight = height.coerceAtLeast(480)
        runCatching { texture.setDefaultBufferSize(bufferWidth, bufferHeight) }

        previewSurface?.release()
        val surface = Surface(texture)
        previewSurface = surface

        runCatching {
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(created: CameraCaptureSession) {
                        if (closed.get() || camera !== device) {
                            created.close()
                            return
                        }
                        session = created
                        runCatching {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()
                            created.setRepeatingRequest(request, null, handler)
                        }.onFailure {
                            closeCamera()
                            return
                        }
                        scheduleFrame()
                    }

                    override fun onConfigureFailed(created: CameraCaptureSession) {
                        if (session === created) session = null
                        created.close()
                    }
                },
                handler
            )
        }.onFailure {
            closeCamera()
        }
    }

    private fun scheduleFrame() {
        if (frameTaskPosted || closed.get()) return
        frameTaskPosted = true
        handler.postDelayed({
            frameTaskPosted = false
            if (closed.get() || !isAvailable || camera == null) return@postDelayed

            val bitmap = runCatching {
                getBitmap(960, 540)
            }.getOrNull()

            if (bitmap != null) {
                val bytes = bitmapToJpeg(bitmap)
                bitmap.recycle()
                if (bytes != null && !closed.get()) frameListener(bytes)
            }
            scheduleFrame()
        }, intervalMs.coerceIn(1000L, 10000L))
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray? {
        val output = ByteArrayOutputStream()
        return runCatching {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)
            output.toByteArray()
        }.getOrNull()
    }

    private fun closeCamera() {
        frameTaskPosted = false
        session?.close()
        session = null
        camera?.close()
        camera = null
        previewSurface?.release()
        previewSurface = null
        cameraId = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startIfReady()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (camera != null) {
            handler.post {
                if (!closed.get()) {
                    closeCamera()
                    openCamera()
                }
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        handler.post { closeCamera() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        if (!closed.compareAndSet(false, true)) return
        handler.post { closeCamera() }
        thread.quitSafely()
        super.onDetachedFromWindow()
    }
}
