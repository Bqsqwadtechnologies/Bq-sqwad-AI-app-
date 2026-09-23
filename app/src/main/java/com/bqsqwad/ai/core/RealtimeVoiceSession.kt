package com.bqsqwad.ai.core

import android.media.*
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class RealtimeVoiceSession(
    private val clientSecret: String,
    private val model: String,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val http = OkHttpClient()
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var audioJob: Job? = null
    private val running = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=${java.net.URLEncoder.encode(model, "UTF-8")}")
            .addHeader("Authorization", "Bearer $clientSecret")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendSessionUpdate(webSocket)
                startAudioCapture()
                onState("connected")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(webSocket, text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onState("stopping")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopInternal()
                onState("stopped")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t.message ?: "Realtime voice connection failed.")
                stopInternal()
            }
        })
    }

    private fun sendSessionUpdate(ws: WebSocket) {
        ws.send(JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("type", "realtime")
                put("model", model)
                put("instructions", "You are BQ Sqwad AI. Have a natural, helpful, concise voice conversation. Do not ask the user to read or type text. Respond with voice audio only.")
                put("output_modalities", org.json.JSONArray().put("audio"))
                put("audio", JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("format", JSONObject().put("type", "audio/pcm"))
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("interrupt_response", true)
                            put("create_response", true)
                        })
                    })
                    put("output", JSONObject().put("format", JSONObject().put("type", "audio/pcm")))
                })
            })
        }.toString())
    }

    private fun handleEvent(ws: WebSocket, raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (event.optString("type")) {
            "session.created", "session.updated" -> onState("connected")
            "input_audio_buffer.speech_started" -> {
                // Barge-in: immediately stop local playback and cancel the active response.
                player?.pause()
                player?.flush()
                playing.set(false)
                ws.send(JSONObject().put("type", "response.cancel").toString())
                onState("listening")
            }
            "input_audio_buffer.speech_stopped" -> onState("thinking")
            "response.output_audio.delta" -> {
                val encoded = event.optString("delta")
                if (encoded.isNotBlank()) {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    ensurePlayer()
                    player?.play()
                    playing.set(true)
                    player?.write(bytes, 0, bytes.size)
                    onState("speaking")
                }
            }
            "response.done" -> onState("listening")
            "error" -> onError(event.optJSONObject("error")?.optString("message") ?: "Realtime voice error")
        }
    }

    private fun startAudioCapture() {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (min <= 0) throw IllegalStateException("Microphone audio format is unavailable.")
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL_IN, ENCODING, min * 2)
        recorder?.startRecording()
        audioJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val buffer = ByteArray(min.coerceAtLeast(2048))
            while (isActive && running.get()) {
                val count = recorder?.read(buffer, 0, buffer.size) ?: -1
                if (count > 0) {
                    val payload = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                    socket?.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", payload).toString())
                }
            }
        }
    }

    private fun ensurePlayer() {
        if (player != null) return
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        if (min <= 0) throw IllegalStateException("Speaker audio format is unavailable.")
        player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(ENCODING).setChannelMask(CHANNEL_OUT).build())
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        socket?.close(1000, "User stopped voice")
        stopInternal()
    }

    private fun stopInternal() {
        running.set(false)
        audioJob?.cancel()
        audioJob = null
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        player?.release()
        player = null
        socket = null
    }
}
