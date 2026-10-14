package com.bqsqwad.ai.core

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RealtimeVoiceSession(
    private val context: Context,
    private val clientSecret: String, // Server-issued short-lived realtime credential; never a permanent API key.
    private val model: String,
    private val voice: String = "marin",
    private val allowWebSearch: Boolean = true,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onAudioLevel: (Float) -> Unit = {},
    private val onTranscript: (speaker: String, text: String) -> Unit = { _, _ -> },
    private val onOutputAudioLevel: (Float) -> Unit = {}
) {
    companion object {
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val http = OkHttpClient()
    private val toolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toolCallInProgress = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var audioJob: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode: Int? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private val lastOutputStartedAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val running = AtomicBoolean(false)
    private val audioCaptureStarted = AtomicBoolean(false)
    private val sessionConfigured = AtomicBoolean(false)
    private val sessionCreated = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    // Realtime accepts one active response at a time. Keep only the newest visual frame
    // while speech is being answered, then submit it when the current response finishes.
    private val responseInProgress = AtomicBoolean(false)
    // Ignore any late audio deltas from a response after the user interrupts it.
    // The next response.created event re-enables playback for the new AI turn.
    private val suppressAudioUntilResponse = AtomicBoolean(false)
    private val pendingVisualFrame = AtomicReference<ByteArray?>(null)
    private val inputTranscript = StringBuilder()
    private val outputTranscript = StringBuilder()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=${java.net.URLEncoder.encode(model, "UTF-8")}")
            .addHeader("Authorization", "Bearer $clientSecret")
            .build()
        toolScope.launch {
            delay(10_000L)
            if (running.get() && !sessionConfigured.get()) {
                onError(
                    if (sessionCreated.get()) {
                        "Live Voice connected but could not finish configuring in 10 seconds. Check the network and tap Retry."
                    } else {
                        "Live Voice did not receive a session response in 10 seconds. Check the network and tap Retry."
                    }
                )
                stopInternal()
            }
        }
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
                put("instructions", if (allowWebSearch) {
                    "You are BQ Sqwad AI. Have a natural, helpful, concise voice conversation. When a live camera or screen image is supplied, use it as visual context. For current facts, latest news, current prices, sports schedules/scores, or current office holders, call search_web and answer from its sources. If the tool fails or returns no sources, clearly say Web Search is unavailable right now. Do not claim you cannot access live results when search_web is available. Do not invent citations. Respond with voice audio only."
                } else {
                    "You are BQ Sqwad AI. Have a natural, helpful, concise voice conversation. When a live camera or screen image is supplied, use it as visual context. If the user asks for current facts, explain that Web Search is turned off and that the answer may not be current. Respond with voice audio only."
                })
                put("tools", org.json.JSONArray().apply {
                    if (allowWebSearch) put(JSONObject().apply {
                        put("type", "function")
                        put("name", "search_web")
                        put("description", "Search current public web information when the user asks for latest or time-sensitive facts.")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "A concise search query for the user's current-information question.")))
                            put("required", org.json.JSONArray().put("query"))
                            put("additionalProperties", false)
                        })
                    })
                })
                put("tool_choice", if (allowWebSearch) "auto" else "none")
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

    /**
     * Queues the latest camera/screen frame. Audio turns and visual prompts share one Realtime
     * response stream, so requesting a response for every frame can collide with an active
     * speech response and terminate the session. Keep the newest frame and process it when idle.
     */
    fun sendVisualFrame(jpegBytes: ByteArray) {
        if (!running.get() || jpegBytes.isEmpty()) return
        pendingVisualFrame.set(jpegBytes)
        socket?.let(::sendPendingVisualFrameIfIdle)
    }

    private fun sendPendingVisualFrameIfIdle(ws: WebSocket) {
        if (!running.get() || !sessionConfigured.get() || !responseInProgress.compareAndSet(false, true)) return
        val frame = pendingVisualFrame.getAndSet(null)
        if (frame == null) {
            responseInProgress.set(false)
            return
        }
        val encoded = Base64.encodeToString(frame, Base64.NO_WRAP)
        val itemSent = ws.send(JSONObject().apply {
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", org.json.JSONArray()
                    .put(JSONObject().put("type", "input_text").put("text", "Analyze this latest live camera or screen frame. Use it as visual context for the user's ongoing conversation; mention important visible details or changes without repeating unchanged observations."))
                    .put(JSONObject().apply {
                        put("type", "input_image")
                        put("image_url", "data:image/jpeg;base64," + encoded)
                    })
                )
            })
        }.toString())
        val responseSent = itemSent && ws.send(JSONObject().put("type", "response.create").put("response", JSONObject().apply {
            put("output_modalities", org.json.JSONArray().put("audio"))
        }).toString())
        if (!responseSent) responseInProgress.set(false)
    }

    private fun handleEvent(ws: WebSocket, raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (event.optString("type")) {
            "session.created" -> {
                sessionCreated.set(true)
                onState("configuring")
            }
            "response.created" -> {
                suppressAudioUntilResponse.set(false)
                responseInProgress.set(true)
            }
            "session.updated" -> {
                sessionConfigured.set(true)
                if (audioCaptureStarted.compareAndSet(false, true)) {
                    val capture = runCatching { startAudioCapture() }
                    if (capture.isFailure) {
                        audioCaptureStarted.set(false)
                        onError(capture.exceptionOrNull()?.message ?: "Microphone audio could not start.")
                        return
                    }
                }
                onState("listening")
                sendPendingVisualFrameIfIdle(ws)
            }
            "input_audio_buffer.speech_started" -> {
                // Ignore only immediate speaker echo. The short guard expires quickly so
                // genuine user barge-in still interrupts a long AI response.
                if (!hasExternalAudioRoute() && System.currentTimeMillis() - lastOutputStartedAt.get() in 0L..280L) {
                    return
                }
                // Barge-in: immediately stop local playback and cancel the active response.
                val interruptingAi = playing.get() || responseInProgress.get()
                if (interruptingAi) {
                    suppressAudioUntilResponse.set(true)
                    runCatching { player?.pause() }
                    runCatching { player?.flush() }
                    playing.set(false)
                    // Cancel only when a response is active; otherwise Realtime may emit
                    // a spurious "no active response" error while the user is simply speaking.
                    ws.send(JSONObject().put("type", "response.cancel").toString())
                }
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
                // Cancellation can race with already queued audio events. Never let those
                // stale chunks restart speech while the user is taking their turn.
                if (suppressAudioUntilResponse.get()) return
                val encoded = event.optString("delta")
                if (encoded.isNotBlank()) {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    ensurePlayer()
                    player?.play()
                    playing.set(true)
                    lastOutputStartedAt.set(System.currentTimeMillis())
                    onOutputAudioLevel(pcmRms(bytes))
                    val track = player ?: return
                    val written = track.write(bytes, 0, bytes.size)
                    if (written < 0) throw IllegalStateException("Audio playback failed ($written).")
                    onState("speaking")
                }
            }
            "response.function_call_arguments.done" -> {
                if (allowWebSearch && event.optString("name") == "search_web") {
                    val callId = event.optString("call_id")
                    val query = runCatching { JSONObject(event.optString("arguments")).optString("query").trim() }.getOrDefault("")
                    if (callId.isNotBlank() && query.isNotBlank() && toolCallInProgress.compareAndSet(false, true)) {
                        toolScope.launch {
                            val output = runCatching {
                                val result = WorkspaceApiClient().search(query, false)
                                JSONObject().apply {
                                    put("sources", org.json.JSONArray().apply {
                                        result.sources.filter { it.url.startsWith("https://", ignoreCase = true) }.take(10).forEach { source ->
                                            put(JSONObject().put("title", source.title).put("url", source.url).put("snippet", source.snippet.orEmpty()))
                                        }
                                    })
                                    if (result.sources.isEmpty()) put("message", "Web Search returned no current sources.")
                                }.toString()
                            }.getOrElse {
                                JSONObject().put("error", "Web Search is unavailable right now.").toString()
                            }
                            if (running.get()) {
                                val outputSent = ws.send(JSONObject().apply {
                                    put("type", "conversation.item.create")
                                    put("item", JSONObject().apply {
                                        put("type", "function_call_output")
                                        put("call_id", callId)
                                        put("output", output)
                                    })
                                }.toString())
                                if (outputSent) {
                                    ws.send(JSONObject().put("type", "response.create").put("response", JSONObject().put("output_modalities", org.json.JSONArray().put("audio"))).toString())
                                } else {
                                    onError("Web Search results could not be returned to the live session.")
                                }
                            }
                            toolCallInProgress.set(false)
                        }
                    }
                }
            }
            "response.done" -> {
                responseInProgress.set(false)
                onOutputAudioLevel(0f)
                onState("listening")
                if (!toolCallInProgress.get()) sendPendingVisualFrameIfIdle(ws)
            }
            "error" -> {
                val realtimeError = event.optJSONObject("error")
                val code = realtimeError?.optString("code").orEmpty().lowercase()
                val message = realtimeError?.optString("message").orEmpty()
                // Empty commits, cancellations, and response conflicts are recoverable.
                val fatal = code.contains("auth") ||
                    code.contains("expired") ||
                    code.contains("quota") ||
                    code.contains("billing") ||
                    code.contains("model_not_found") ||
                    code.contains("invalid_session") ||
                    code.contains("session_configuration") ||
                    code.contains("connection_closed") ||
                    message.contains("unauthorized", ignoreCase = true) ||
                    message.contains("invalid api key", ignoreCase = true) ||
                    message.contains("insufficient_quota", ignoreCase = true) ||
                    message.contains("model not found", ignoreCase = true)
                if (fatal) {
                    responseInProgress.set(false)
                    onError("Realtime session error (" + (code.ifBlank { "fatal_provider_error" }) + "). Retry the session.")
                } else {
                    if (!playing.get()) responseInProgress.set(false)
                    onState(if (playing.get()) "speaking" else "listening")
                }
            }
        }
    }

    private fun startAudioCapture() {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (min <= 0) throw IllegalStateException("Microphone audio format is unavailable.")
        val communicationRecorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL_IN, ENCODING, min * 2)
        }.getOrNull()
        recorder = if (communicationRecorder?.state == AudioRecord.STATE_INITIALIZED) {
            communicationRecorder
        } else {
            communicationRecorder?.release()
            // Some Android devices do not initialize VOICE_COMMUNICATION capture reliably.
            // Fall back to the standard microphone source rather than ending the session.
            runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_IN, ENCODING, min * 2)
            }.getOrNull()
        }
        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            recorder = null
            throw IllegalStateException("Microphone audio could not be initialized. Check microphone permission and close other apps using the microphone.")
        }
        configureCommunicationAudio()
        val activeRecorder = recorder ?: throw IllegalStateException("Microphone audio could not be initialized.")
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(activeRecorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(activeRecorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            automaticGainControl = runCatching { AutomaticGainControl.create(activeRecorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        activeRecorder.startRecording()
        if (recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder?.release()
            recorder = null
            throw IllegalStateException("BQ Sqwad AI could not start microphone capture. Check microphone permission and try again.")
        }
        audioJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val buffer = ByteArray(min.coerceAtLeast(2048))
            while (isActive && running.get()) {
                val activeRecorder = recorder ?: break
                val count = activeRecorder.read(buffer, 0, buffer.size)
                when {
                    count > 0 -> {
                        onAudioLevel(pcmRms(buffer, count))
                        val payload = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                        val sent = socket?.send(
                            JSONObject().put("type", "input_audio_buffer.append").put("audio", payload).toString()
                        ) == true
                        if (!sent && running.get()) {
                            onError("The live audio connection was interrupted. Reconnect and try again.")
                            break
                        }
                    }
                    count < 0 -> {
                        if (running.get()) {
                            onAudioLevel(0f)
                            onError("Microphone capture was interrupted. Check microphone permission and close other apps using the microphone, then reconnect.")
                        }
                        break
                    }
                    else -> delay(8L)
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
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
        toolScope.coroutineContext.cancelChildren()
        toolCallInProgress.set(false)
        onAudioLevel(0f)
        onOutputAudioLevel(0f)
        inputTranscript.setLength(0)
        outputTranscript.setLength(0)
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        runCatching { echoCanceler?.release() }; echoCanceler = null
        runCatching { noiseSuppressor?.release() }; noiseSuppressor = null
        runCatching { automaticGainControl?.release() }; automaticGainControl = null
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        player?.release()
        player = null
        restoreCommunicationAudio()
        socket = null
    }

    private fun hasExternalAudioRoute(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
            }
        }.getOrDefault(false)
    }

    private fun configureCommunicationAudio() {
        if (previousAudioMode == null) previousAudioMode = audioManager.mode
        audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                onState("listening")
            }
        }
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = !hasExternalAudioRoute()
    }

    private fun restoreCommunicationAudio() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        audioFocusListener = null
        runCatching { audioManager.isSpeakerphoneOn = false }
        previousAudioMode?.let { oldMode -> runCatching { audioManager.mode = oldMode } }
        previousAudioMode = null
    }
}
