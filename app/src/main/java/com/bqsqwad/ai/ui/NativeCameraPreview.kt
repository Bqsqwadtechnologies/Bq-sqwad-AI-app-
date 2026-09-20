package com.bqsqwad.ai.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

private class NativeCameraPreviewController(private val context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var texture: TextureView? = null

    private val callback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            camera = device
            startPreview()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (camera === device) camera = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            if (camera === device) camera = null
        }
    }

    fun attach(view: TextureView) {
        texture = view
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                open()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                close()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (view.isAvailable) open()
    }

    private fun open() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (camera != null) return
        val id = manager.cameraIdList.firstOrNull { value ->
            manager.getCameraCharacteristics(value).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return
        runCatching { manager.openCamera(id, callback, null) }
    }

    private fun startPreview() {
        val device = camera ?: return
        val view = texture ?: return
        val surfaceTexture = view.surfaceTexture ?: return
        val size = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        surfaceTexture.setDefaultBufferSize(size, height)
        val surface = Surface(surfaceTexture)
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
        }
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(value: CameraCaptureSession) {
                if (camera !== device) return
                session = value
                runCatching {
                    value.setRepeatingRequest(request.build(), null, null)
                }
            }
            override fun onConfigureFailed(value: CameraCaptureSession) {
                if (session === value) session = null
            }
        }, null)
    }

    fun close() {
        session?.close()
        session = null
        camera?.close()
        camera = null
    }
}

@Composable
fun NativeCameraPreview(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { NativeCameraPreviewController(context) }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(300.dp),
        factory = { TextureView(it).also(controller::attach) }
    )
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
}
