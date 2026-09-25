package com.bqsqwad.ai.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.core.interfaces.startStream
import kotlinx.coroutines.Dispatchers
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

    suspend fun startCamera(endpointUrl: String) = withContext(Dispatchers.IO) {
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
            val next = cameraSingleStreamer(context = context.applicationContext)
            next.startStream(endpointUrl)
            streamer = next
            endpoint = endpointUrl
            onState("live")
        } catch (t: Throwable) {
            onError(t.message ?: "Live broadcaster could not connect.")
            runCatching { streamer?.release() }
            streamer = null
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
        endpoint = null
        onState("stopped")
    }

    fun isStreaming(): Boolean = streamer?.isStreamingFlow?.value == true
}
