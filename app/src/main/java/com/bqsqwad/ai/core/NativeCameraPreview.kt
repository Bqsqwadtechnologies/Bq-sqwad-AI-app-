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
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal fun selectCameraIdForFacing(
    cameraIds: List<String>,
    requestedFacing: Int,
    facingForId: (String) -> Int?
): Pair<String, Int?>? {
    if (cameraIds.isEmpty()) return null
    val requested = cameraIds.firstOrNull { facingForId(it) == requestedFacing }
    val otherFacing = if (requestedFacing == CameraCharacteristics.LENS_FACING_FRONT) {
        CameraCharacteristics.LENS_FACING_BACK
    } else {
        CameraCharacteristics.LENS_FACING_FRONT
    }
    val selected = requested ?: cameraIds.firstOrNull { facingForId(it) == otherFacing } ?: cameraIds.first()
    return selected to facingForId(selected)
}

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
    torchEnabled: Boolean = false,
    onFrame: (ByteArray) -> Unit = {},
    onError: (String) -> Unit = {},
    onClosed: () -> Unit = {},
    onLensSelected: (Int) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            LiveCameraTextureView(context).apply {
                closedListener = onClosed
                lensListener = onLensSelected
                updateConfiguration(lensFacing, captureEveryMs, torchEnabled, onFrame, onError)
            }
        },
        update = { view ->
            view.closedListener = onClosed
            view.lensListener = onLensSelected
            view.updateConfiguration(lensFacing, captureEveryMs, torchEnabled, onFrame, onError)
        }
    )
}

private class LiveCameraTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    var facing: Int = CameraCharacteristics.LENS_FACING_BACK
        private set
    var intervalMs: Long = 1800L
        private set
    var torchEnabled: Boolean = false
        private set
    var frameListener: (ByteArray) -> Unit = {}
    var errorListener: (String) -> Unit = {}
    var closedListener: () -> Unit = {}
    var lensListener: (Int) -> Unit = {}
    private var lastErrorMessage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun reportCameraError(message: String) {
        if (lastErrorMessage == message) return
        lastErrorMessage = message
        mainHandler.post { if (!closed.get()) errorListener(message) }
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("bq-live-camera").also { it.start() }
    private val handler = Handler(thread.looper)

    private var camera: CameraDevice? = null
    private var closingCamera: CameraDevice? = null
    private val pendingCloseCallbacks = mutableListOf<() -> Unit>()
    private var session: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var previewSurface: Surface? = null
    private var frameTaskPosted = false
    private var consecutiveFrameFailures = 0
    private val closed = AtomicBoolean(false)

    init {
        surfaceTextureListener = this
    }

    fun updateConfiguration(
        newFacing: Int,
        newIntervalMs: Long,
        newTorchEnabled: Boolean,
        listener: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        frameListener = listener
        errorListener = onError
        intervalMs = newIntervalMs.coerceIn(1000L, 10000L)
        val torchChanged = torchEnabled != newTorchEnabled
        torchEnabled = newTorchEnabled
        if (facing != newFacing) {
            facing = newFacing
            handler.post {
                if (!closed.get()) {
                    closeCamera {
                        if (!closed.get() && isAvailable) openCamera()
                    }
                }
            }
        } else {
            if (torchChanged) handler.post { if (!closed.get()) updateTorchMode() }
            startIfReady()
        }
    }

    private fun updateTorchMode() {
        val device = camera ?: return
        val activeSession = session ?: return
        val surface = previewSurface ?: return
        val available = runCatching {
            cameraId?.let { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } == true
        }.getOrDefault(false)
        if (torchEnabled && !available) {
            reportCameraError("The selected camera does not have a usable flashlight. Switch to the rear camera.")
            return
        }
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }.build()
            activeSession.setRepeatingRequest(request, null, handler)
        }.onFailure {
            reportCameraError("The flashlight could not be changed on this camera. Try the rear camera.")
        }
    }

    fun startIfReady() {
        if (closed.get() || !isAvailable || camera != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            reportCameraError("Camera permission is required. Allow Camera in Android settings, then retry AI Live Camera.")
            return
        }
        openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (closed.get() || camera != null) return

        val selection = runCatching {
            selectCameraIdForFacing(cameraManager.cameraIdList.toList(), facing) { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }
        }.getOrNull()
        val id = selection?.first
        if (id == null) {
            reportCameraError("No usable camera was found on this device. Check Android camera settings and retry.")
            return
        }
        // Track the actual camera selected when the requested lens is unavailable.
        selection.second?.let { actualFacing ->
            facing = actualFacing
            mainHandler.post { if (!closed.get()) lensListener(actualFacing) }
        }

        cameraId = id
        runCatching {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed.get() || !isAvailable || cameraId != id) {
                        device.close()
                        return
                    }
                    lastErrorMessage = null
                    camera = device
                    createSession(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    if (camera === device) camera = null
                    if (closingCamera == null) closingCamera = device
                    device.close()
                    reportCameraError("The camera disconnected. Reopen AI Live Camera and try again.")
                }

                override fun onClosed(device: CameraDevice) {
                    if (closingCamera === device) closingCamera = null
                    if (pendingCloseCallbacks.isNotEmpty()) {
                        val callbacks = pendingCloseCallbacks.toList()
                        pendingCloseCallbacks.clear()
                        callbacks.forEach { callback -> runCatching { callback() } }
                    }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    if (camera === device) camera = null
                    device.close()
                    val message = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                            "The camera is in use by another app. Close that app and retry."
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
                            "Camera access is disabled by Android or device policy. Check device settings."
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
                            "Android's camera service is temporarily unavailable. Close other camera apps and retry."
                        else ->
                            "The camera is temporarily unavailable. Close other camera apps and retry."
                    }
                    reportCameraError(message)
                }
            }, handler)
        }.onFailure {
            reportCameraError("Android could not open the camera. Check camera permission and close other camera apps.")
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
                        lastErrorMessage = null
                        session = created
                        runCatching {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                val flashAvailable = runCatching {
                                    cameraId?.let { id ->
                                        cameraManager.getCameraCharacteristics(id)
                                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                                    } == true
                                }.getOrDefault(false)
                                set(
                                    CaptureRequest.FLASH_MODE,
                                    if (torchEnabled && flashAvailable) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                                )
                            }.build()
                            created.setRepeatingRequest(request, null, handler)
                        }.onFailure {
                            closeCamera()
                            reportCameraError("The camera preview could not start. Close other camera apps and try again.")
                            return
                        }
                        scheduleFrame()
                    }

                    override fun onConfigureFailed(created: CameraCaptureSession) {
                        if (session === created) session = null
                        created.close()
                        reportCameraError("The camera preview could not be configured. Try switching cameras or reopening AI Live Camera.")
                    }
                },
                handler
            )
        }.onFailure {
            closeCamera()
            reportCameraError("The camera preview could not be created. Reopen AI Live Camera and try again.")
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
                consecutiveFrameFailures = 0
                lastErrorMessage = null
                val bytes = bitmapToJpeg(bitmap)
                bitmap.recycle()
                if (bytes != null && !closed.get()) frameListener(bytes)
            } else {
                consecutiveFrameFailures += 1
                if (consecutiveFrameFailures >= 3) {
                    reportCameraError("The camera preview is open, but BQ Sqwad AI cannot read camera frames. Close other camera apps and retry.")
                }
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

    private fun closeCamera(afterClosed: (() -> Unit)? = null) {
        frameTaskPosted = false
        if (afterClosed != null) pendingCloseCallbacks.add(afterClosed)
        session?.close()
        session = null
        val device = camera
        camera = null
        previewSurface?.release()
        previewSurface = null
        cameraId = null
        if (device != null) {
            closingCamera = device
            device.close()
        } else if (closingCamera == null && pendingCloseCallbacks.isNotEmpty()) {
            val callbacks = pendingCloseCallbacks.toList()
            pendingCloseCallbacks.clear()
            callbacks.forEach { callback -> runCatching { callback() } }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startIfReady()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (camera != null) {
            handler.post {
                if (!closed.get()) {
                    closeCamera { if (!closed.get() && isAvailable) openCamera() }
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
        handler.post {
            closeCamera {
                mainHandler.post { closedListener() }
                thread.quitSafely()
            }
        }
        super.onDetachedFromWindow()
    }
}
