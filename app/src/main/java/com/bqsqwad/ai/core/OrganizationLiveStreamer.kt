package com.bqsqwad.ai.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.core.interfaces.startStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Native production broadcaster used by Organization AI Studio.
 *
 * StreamPack owns the complete Camera2 + MediaCodec + microphone pipeline and
 * sends H.264/AAC to the backend-provided RTMP/RTMPS ingestion endpoint.
 * Provider credentials are never stored in the APK.
 */
class OrganizationLiveStreamer(
    private val context: Context,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var streamer: SingleStreamer? = null
    private var endpoint: String? = null

    companion object {
        private val _activeStreamer = MutableStateFlow<SingleStreamer?>(null)
        val activeStreamer: StateFlow<SingleStreamer?> = _activeStreamer
    }

    suspend fun startCamera(endpointUrl: String, lensFacing: Int = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) = withContext(Dispatchers.IO) {
        require(endpointUrl.startsWith("rtmp://") || endpointUrl.startsWith("rtmps://")) {
            "Unsupported live endpoint."
        }
        check(
            ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Camera permission is required." }
        check(
            ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission is required." }

        stop()

        try {
            onState("connecting")
            val cameraManager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                ?: throw IllegalStateException("Camera service is unavailable.")
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                runCatching {
                    cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == lensFacing
                }.getOrDefault(false)
            } ?: throw IllegalStateException("The selected camera is not available for online streaming.")
            val next = cameraSingleStreamer(context = context.applicationContext, cameraId = cameraId)
            next.startStream(endpointUrl)
            streamer = next
            _activeStreamer.value = next
            endpoint = endpointUrl
            onState("live")
        } catch (t: Throwable) {
            onError(t.message ?: "Live broadcaster could not connect.")
            runCatching { streamer?.release() }
            streamer = null
            _activeStreamer.value = null
            endpoint = null
            throw t
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val current = streamer ?: return@withContext
        runCatching {
            if (current.isStreamingFlow.value) current.stopStream()
        }
        runCatching { current.close() }
        runCatching { current.release() }
        streamer = null
        _activeStreamer.value = null
        endpoint = null
        onState("stopped")
    }

    fun isStreaming(): Boolean = streamer?.isStreamingFlow?.value == true
}
