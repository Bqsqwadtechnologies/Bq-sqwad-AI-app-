package com.bqsqwad.ai.core

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.ActivityCompat
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native production broadcaster used by Organization AI Studio.
 *
 * StreamPack supplies Camera2/MediaCodec H.264 + microphone AAC and RTMP/RTMPS
 * transport. The app never stores provider credentials; it receives a short-lived
 * ingestion URL from the authenticated backend after the destination session is created.
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
        check(ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Camera permission is required."
        }
        check(ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required."
        }

        stop()
        val application = context.applicationContext as Application
        val cameraId = application.cameraManager.defaultCameraId
        val next = SingleStreamer(application, defaultRotation = 0)
        next.setVideoSource(CameraSourceFactory(cameraId))
        (next as IAudioSingleStreamer).setAudioSource(MicrophoneSourceFactory())

        try {
            onState("connecting")
            next.startStream(Uri.parse(endpointUrl))
            streamer = next
            endpoint = endpointUrl
            onState("live")
        } catch (t: Throwable) {
            runCatching { next.release() }
            onError(t.message ?: "Live broadcaster could not connect.")
            throw t
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val current = streamer ?: return@withContext
        runCatching { current.stopStream() }
        runCatching { current.close() }
        runCatching { current.release() }
        streamer = null
        endpoint = null
        onState("stopped")
    }

    fun isStreaming(): Boolean = streamer?.isStreamingFlow?.value == true
}
