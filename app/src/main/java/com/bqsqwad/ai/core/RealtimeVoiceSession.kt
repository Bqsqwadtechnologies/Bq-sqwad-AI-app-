package com.bqsqwad.ai.core

import android.content.Context
import android.media.*
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class RealtimeVoiceSession(
    private val context: Context,
    private val clientSecret: String, // Server-issued short-lived realtime credential; never a permanent API key.
    private val model: String,
    private val voice: String = "marin",
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onAudioLevel: (Float) -> Unit = {},
    private val onTranscript: (speaker: String, text: String) -> Unit = { _, _ -> }
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
    private val audioCaptureStarted = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private val inputTranscript = StringBuilder()
    private val outputTranscript = StringBuilder()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=${java.net.URLEncoder.encode(model, "UTF-8")}")
            .addHeader("Authorization", "Bearer $clientSecret")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    sendSessionUpdate(webSocket)
                    onState("configuring")
                } catch (failure: Throwable) {
                    webSocket.close(1011, "Audio initialization failed")
                    onError(failure.message ?: "BQ Sqwad AI could not start the live audio session.")
                    stopInternal()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleEvent(webSocket, text) }
                    .onFailure { failure ->
                        onError(failure.message ?: "BQ Sqwad AI received an unreadable live-audio event.")
                    }
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
        // The server-issued client secret already fixes the GA model, voice and audio output.
        // GA session.update cannot change the model/voice; only send mutable session settings.
        ws.send(JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("type", "realtime")
                put("instructions", "You are BQ Sqwad AI. Have a natural, helpful, concise voice conversation. Do not ask the user to read or type text. Respond with voice audio only.")
                put("audio", JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
                        put("transcription", JSONObject().put("model", "gpt-4o-mini-transcribe"))
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("interrupt_response", true)
                            put("create_response", true)
                        })
                    })
                })
            })
        }.toString())
    }

    /** Sends a current camera/screen frame into the same voice session. The response remains audio-only. */
    fun sendVisualFrame(jpegBytes: ByteArray) {
        if (!running.get()) return
        val encoded = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        socket?.send(JSONObject().apply {
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", org.json.JSONArray().put(
                    JSONObject().apply {
                        put("type", "input_image")
                        put("image_url", "data:image/jpeg;base64," + encoded)
                    }
                ))
            })
        }.toString())
        socket?.send(JSONObject().put("type", "response.create").put("response", JSONObject().apply {
            put("output_modalities", org.json.JSONArray().put("audio"))
        }).toString())
    }

    private fun handleEvent(ws: WebSocket, raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (event.optString("type")) {
            "session.created" -> onState("configuring")
            "session.updated" -> {
                if (audioCaptureStarted.compareAndSet(false, true)) {
                    val capture = runCatching { startAudioCapture() }
                    if (capture.isFailure) {
                        audioCaptureStarted.set(false)
                        onError(capture.exceptionOrNull()?.message ?: "Microphone audio could not start.")
                        return
                    }
                }
                onState("listening")
            }
            "input_audio_buffer.speech_started" -> {
                // Barge-in: immediately stop local playback and cancel the active response.
                player?.pause()
                player?.flush()
                playing.set(false)
                ws.send(JSONObject().put("type", "response.cancel").toString())
                onState("listening")
            }
            "input_audio_buffer.speech_stopped" -> onState("thinking")
            "conversation.item.input_audio_transcription.delta", "input_audio_transcription.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotBlank()) inputTranscript.append(delta)
            }
            "conversation.item.input_audio_transcription.completed", "input_audio_transcription.completed" -> {
                val transcript = event.optString("transcript").trim().ifBlank { inputTranscript.toString().trim() }
                if (transcript.isNotBlank()) {
                    inputTranscript.setLength(0)
                    onTranscript("You", transcript)
                }
            }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotBlank()) {
                    outputTranscript.append(delta)
                }
            }
            "response.output_audio_transcript.done", "response.audio_transcript.done" -> {
                val transcript = event.optString("transcript").trim().ifBlank { outputTranscript.toString().trim() }
                if (transcript.isNotBlank()) {
                    outputTranscript.setLength(0)
                    onTranscript("BQ Sqwad AI", transcript)
                }
            }
            // Accept both current GA and legacy Realtime audio-delta event names.
            "response.output_audio.delta", "response.audio.delta" -> {
                val encoded = event.optString("delta")
                if (encoded.isNotBlank()) {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    ensurePlayer()
                    player?.play()
                    playing.set(true)
                    onAudioLevel(pcmRms(bytes))
                    val track = player ?: return
                    val written = track.write(bytes, 0, bytes.size)
                    if (written < 0) throw IllegalStateException("Audio playback failed ($written).")
                    onState("speaking")
                }
            }
            "response.done" -> {
                onAudioLevel(0f)
                onState("listening")
            }
            "error" -> onError(event.optJSONObject("error")?.optString("message") ?: "Realtime voice error")
        }
    }

    private fun startAudioCapture() {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (min <= 0) throw IllegalStateException("Microphone audio format is unavailable.")
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL_IN, ENCODING, min * 2)
        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            recorder = null
            throw IllegalStateException("Microphone audio could not be initialized on this device.")
        }
        recorder?.startRecording()
        if (recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder?.release()
            recorder = null
            throw IllegalStateException("BQ Sqwad AI could not start microphone capture. Check microphone permission and try again.")
        }
        audioJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val buffer = ByteArray(min.coerceAtLeast(2048))
            while (isActive && running.get()) {
                val count = recorder?.read(buffer, 0, buffer.size) ?: -1
                if (count > 0) {
                    onAudioLevel(pcmRms(buffer, count))
                    val payload = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                    socket?.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", payload).toString())
                }
            }
        }
    }

    private fun pcmRms(bytes: ByteArray, length: Int = bytes.size): Float {
        val sampleCount = (length / 2).coerceAtLeast(1)
        var sum = 0.0
        var offset = 0
        repeat(sampleCount) {
            if (offset + 1 >= length) return@repeat
            val sample = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort().toInt()
            val normalized = sample / 32768.0
            sum += normalized * normalized
            offset += 2
        }
        return kotlin.math.sqrt(sum / sampleCount).toFloat().coerceIn(0f, 1f)
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
        audioCaptureStarted.set(false)
        audioJob?.cancel()
        audioJob = null
        onAudioLevel(0f)
        inputTranscript.setLength(0)
        outputTranscript.setLength(0)
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
