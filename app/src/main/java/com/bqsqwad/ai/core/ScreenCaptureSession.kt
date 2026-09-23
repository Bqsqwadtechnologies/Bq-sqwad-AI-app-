package com.bqsqwad.ai.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * User-consented Android screen viewer for AI Live Camera.
 *
 * A new MediaProjection token/session is required for every capture session.
 * The caller must obtain the token from MediaProjectionManager.createScreenCaptureIntent()
 * and pass the result Intent here only after the user grants Android's dialog.
 */
class ScreenCaptureSession(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onStopped: () -> Unit = {}
) {
    private val thread = HandlerThread("bq-screen-capture").also { it.start() }
    private val handler = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var stopped = false
    private var callback: MediaProjection.Callback? = null

    fun start(resultData: Intent, width: Int, height: Int, densityDpi: Int) {
        check(!stopped) { "Screen capture session is already stopped." }
        stopInternal(notify = false)

        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: error("Screen capture is unavailable on this device.")

        val mediaProjection = manager.getMediaProjection(android.app.Activity.RESULT_OK, resultData)
            ?: error("Android did not grant screen capture access.")

        projection = mediaProjection
        callback = object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopInternal(notify = true) }
            }
        }
        mediaProjection.registerCallback(callback!!, handler)

        val safeWidth = width.coerceIn(320, 1920)
        val safeHeight = height.coerceIn(320, 1080)
        reader = ImageReader.newInstance(
            safeWidth,
            safeHeight,
            PixelFormat.RGBA_8888,
            2
        ).also { imageReader ->
            imageReader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use {
                    val plane = it.planes.firstOrNull() ?: return@use
                    val buffer: ByteBuffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = (rowStride - pixelStride * safeWidth).coerceAtLeast(0)
                    val bitmapWidth = safeWidth + rowPadding / pixelStride
                    val bitmap = Bitmap.createBitmap(bitmapWidth, safeHeight, Bitmap.Config.ARGB_8888)
                    buffer.rewind()
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cropped = if (bitmapWidth == safeWidth) bitmap else Bitmap.createBitmap(bitmap, 0, 0, safeWidth, safeHeight)
                    val output = ByteArrayOutputStream()
                    val ok = cropped.compress(Bitmap.CompressFormat.JPEG, 72, output)
                    if (cropped !== bitmap) cropped.recycle()
                    bitmap.recycle()
                    if (ok && !stopped) onFrame(output.toByteArray())
                }
            }, handler)
        }

        display = mediaProjection.createVirtualDisplay(
            "BQ Sqwad AI Screen Viewer",
            safeWidth,
            safeHeight,
            densityDpi.coerceIn(160, 640),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        )
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.post { stopInternal(notify = false) }
        thread.quitSafely()
    }

    private fun stopInternal(notify: Boolean) {
        display?.release()
        display = null
        reader?.close()
        reader = null
        val currentProjection = projection
        callback?.let { cb -> runCatching { currentProjection?.unregisterCallback(cb) } }
        callback = null
        projection = null
        if (currentProjection != null && notify) {
            onStopped()
        }
        if (currentProjection != null && !notify) {
            runCatching { currentProjection.stop() }
        }
    }
}
