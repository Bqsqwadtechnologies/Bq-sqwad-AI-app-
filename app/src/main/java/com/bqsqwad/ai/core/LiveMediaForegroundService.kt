package com.bqsqwad.ai.core

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class LiveMediaUiState(
    val active: Boolean = false,
    val mode: String = "",
    val status: String = "stopped",
    val answer: String = "",
    val audioLevel: Float = 0f,
    val log: List<String> = emptyList()
)

class LiveMediaForegroundService : Service() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_LENS = "lens"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"
        const val EXTRA_LIVE_SESSION_ID = "live_session_id"
        const val EXTRA_RIGHTS_ID = "rights_id"
        const val EXTRA_LIVE_ENDPOINT = "live_endpoint"
        const val EXTRA_YOUTUBE_ACCOUNT_ID = "youtube_account_id"
        const val EXTRA_YOUTUBE_BROADCAST_ID = "youtube_broadcast_id"
        const val ACTION_STOP = "stop"
        const val MODE_CAMERA = "camera"
        const val MODE_VOICE = "voice"
        const val MODE_SCREEN = "screen"
        private const val CHANNEL_ID = "bq_live_media"
        private const val NOTIFICATION_ID = 4101
        private const val PREFS = "bq_live_media"
        private val _uiState = MutableStateFlow(LiveMediaUiState())
        val uiState: StateFlow<LiveMediaUiState> = _uiState
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val analyzing = AtomicBoolean(false)
    private var cameraSession: LiveCameraCaptureSession? = null
    private var screenSession: ScreenCaptureSession? = null
    private var mode = ""
    private var prompt = "Describe what is visible."
    private var lens = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
    private var realtimeVoice: RealtimeVoiceSession? = null
    private var liveSessionId: String? = null
    private var rightsId: String? = null
    private var liveEndpoint: String? = null
    private var organizationStreamer: OrganizationLiveStreamer? = null
    private var youtubeAccountId: String? = null
    private var youtubeBroadcastId: String? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val requestedMode = intent?.getStringExtra(EXTRA_MODE).orEmpty()
        if (requestedMode.isNotBlank()) mode = requestedMode
        prompt = intent?.getStringExtra(EXTRA_PROMPT)?.takeIf { it.isNotBlank() } ?: prompt
        lens = intent?.getIntExtra(EXTRA_LENS, lens) ?: lens
        liveSessionId = intent?.getStringExtra(EXTRA_LIVE_SESSION_ID)?.trim()?.takeIf { it.isNotBlank() } ?: liveSessionId
        rightsId = intent?.getStringExtra(EXTRA_RIGHTS_ID)?.trim()?.takeIf { it.isNotBlank() } ?: rightsId
        liveEndpoint = intent?.getStringExtra(EXTRA_LIVE_ENDPOINT)?.trim()?.takeIf { it.isNotBlank() } ?: liveEndpoint
        youtubeAccountId = intent?.getStringExtra(EXTRA_YOUTUBE_ACCOUNT_ID)?.trim()?.takeIf { it.isNotBlank() } ?: youtubeAccountId
        youtubeBroadcastId = intent?.getStringExtra(EXTRA_YOUTUBE_BROADCAST_ID)?.trim()?.takeIf { it.isNotBlank() } ?: youtubeBroadcastId

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.bqsqwad.ai.R.drawable.bq_sqwad_logo)
            .setContentTitle("BQ Sqwad AI")
            .setContentText(when (mode) {
                MODE_CAMERA -> "Live camera is active"
                MODE_SCREEN -> "Screen viewer is active"
                else -> "Live voice is active"
            })
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    PendingIntent.getService(this, 4102, Intent(this, LiveMediaForegroundService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                ).build()
            )
            .build()

        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode == MODE_CAMERA -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode == MODE_SCREEN -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, type) else startForeground(NOTIFICATION_ID, notification)

        when (mode) {
            MODE_CAMERA -> startCamera()
            MODE_SCREEN -> startScreen(intent)
            MODE_VOICE -> startVoice()
        }
        return START_NOT_STICKY
    }

    private fun startVoice() {
        if (realtimeVoice != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            writeError("Microphone permission is required.")
            stopSelf()
            return
        }
        serviceScope.launch {
            try {
                val config = WorkspaceApiClient().realtimeSession()
                val secret = config.optString("clientSecret").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(config.optString("error", "Realtime voice session could not be created."))
                val model = config.optString("model").ifBlank { "gpt-realtime-2.1" }
                withContext(Dispatchers.Main) {
                    realtimeVoice = RealtimeVoiceSession(
                        clientSecret = secret,
                        model = model,
                        onState = { state -> writeState(true, state) },
                        onError = { error -> writeError(error) },
                        onAudioLevel = { level -> updateAudioLevel(level) },
                        onTranscript = { speaker, text -> appendLog("$speaker: $text") }
                    ).also { it.start() }
                }
            } catch (t: Throwable) {
                writeError(t.message ?: "Realtime voice could not start.")
                stopSelf()
            }
        }
        writeState(true, "connecting")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "BQ Sqwad AI live sessions", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Visible status for active BQ Sqwad AI live sessions."
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startCamera() {
        if (organizationStreamer != null || cameraSession != null) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            writeError("Camera permission is required.")
            stopSelf()
            return
        }
        val endpoint = liveEndpoint
        if (!endpoint.isNullOrBlank()) {
            val broadcaster = OrganizationLiveStreamer(
                this,
                onState = { state -> writeState(state == "live" || state == "connecting", state) },
                onError = { writeError(it) }
            )
            organizationStreamer = broadcaster
            serviceScope.launch {
                try {
                    broadcaster.startCamera(endpoint)
                    writeState(true, "live")
                } catch (t: Throwable) {
                    organizationStreamer = null
                    writeError(t.message ?: "Live broadcaster could not start.")
                    stopSelf()
                }
            }
            return
        }

        val session = LiveCameraCaptureSession(
            this,
            lens,
            onFrame = { bytes -> analyzeFrame(bytes, "image/jpeg") },
            onError = { writeError(it.message ?: "Camera capture failed.") }
        )
        try {
            session.start()
            cameraSession = session
            writeState(true, "")
        } catch (t: Throwable) {
            runCatching { session.stop() }
            writeError(t.message ?: "Camera could not start.")
            stopSelf()
        }
    }

    private fun startScreen(intent: Intent?) {
        if (screenSession != null) return
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
        if (data == null) {
            writeError("Android screen-capture permission data is missing.")
            stopSelf()
            return
        }
        val metrics = resources.displayMetrics
        val width = intent?.getIntExtra(EXTRA_WIDTH, metrics.widthPixels) ?: metrics.widthPixels
        val height = intent?.getIntExtra(EXTRA_HEIGHT, metrics.heightPixels) ?: metrics.heightPixels
        val density = intent?.getIntExtra(EXTRA_DENSITY, metrics.densityDpi) ?: metrics.densityDpi
        screenSession = runCatching {
            ScreenCaptureSession(this, onFrame = { bytes -> analyzeFrame(bytes, "image/jpeg") }, onStopped = {
                writeState(false, "Screen sharing was stopped by Android or the user.")
                stopSelf()
            }).also { it.start(data, width, height, density) }
        }.onFailure {
            writeError(it.message ?: "Screen sharing could not start.")
            stopSelf()
        }.getOrNull()
        if (screenSession != null) writeState(true, "")
    }

    private fun analyzeFrame(bytes: ByteArray, mimeType: String) {
        if (!analyzing.compareAndSet(false, true)) return
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        serviceScope.launch {
            try {
                val policyPrompt = if (liveSessionId != null) {
                    "Analyze this live frame for protected broadcast content. Return ONLY JSON with protectedBroadcastDetected, confidence, and reason. Mark true only when the frame appears to show television, sports, movie, news, or other third-party protected programming being captured from a screen or TV."
                } else prompt
                val result = ChatApiClient().analyzeCameraFrame(policyPrompt, encoded, mimeType)
                if (liveSessionId == null) {
                    appendLog("Asked: $prompt")
                }
                appendLog("BQ Sqwad AI: ${result.text}")
                writeState(true, result.text)
                val sessionId = liveSessionId
                if (sessionId != null) {
                    val decision = runCatching {
                        val raw = result.text.substringAfter("{").substringBeforeLast("}")
                        org.json.JSONObject("{$raw}")
                    }.getOrNull()
                    if (decision?.optBoolean("protectedBroadcastDetected", false) == true) {
                        val confidence = decision.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
                        if (confidence >= 0.85) {
                            val response = WorkspaceApiClient().enforceLiveContentPolicy(
                                org.json.JSONObject()
                                    .put("sessionId", sessionId)
                                    .put("rightsId", rightsId)
                                    .put("protectedBroadcastDetected", true)
                                    .put("confidence", confidence)
                                    .put("detectionSource", "android_live_vision")
                            )
                            if (!response.optBoolean("allowed", true)) {
                                writeError(response.optString("message", "Live stream stopped by protected-content policy."))
                                stopSelf()
                                return@launch
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                writeError(t.message ?: "AI live analysis failed.")
            } finally {
                analyzing.set(false)
            }
        }
    }

    private fun writeState(active: Boolean, answer: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("active", active)
            .putString("mode", mode)
            .putString("answer", answer)
            .apply()
        _uiState.value = _uiState.value.copy(
            active = active,
            mode = mode,
            status = answer.ifBlank { if (active) "active" else "stopped" },
            answer = answer
        )
    }

    private fun updateAudioLevel(level: Float) {
        _uiState.value = _uiState.value.copy(audioLevel = level.coerceIn(0f, 1f))
    }

    private fun appendLog(entry: String) {
        val clean = entry.trim()
        if (clean.isBlank()) return
        _uiState.value = _uiState.value.copy(log = (_uiState.value.log + clean).takeLast(80))
    }

    private fun writeError(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("active", false)
            .putString("mode", mode)
            .putString("answer", message)
            .apply()
        _uiState.value = _uiState.value.copy(active = false, mode = mode, status = "error", answer = message, audioLevel = 0f)
        appendLog("Error: $message")
    }

    override fun onDestroy() {
        realtimeVoice?.stop()
        realtimeVoice = null
        cameraSession?.stop()
        cameraSession = null
        runBlocking {
            runCatching { organizationStreamer?.stop() }
            val accountId = youtubeAccountId
            val broadcastId = youtubeBroadcastId
            if (!accountId.isNullOrBlank() && !broadcastId.isNullOrBlank()) {
                runCatching {
                    WorkspaceApiClient().transitionYouTubeLive(accountId, broadcastId, "complete")
                }
            }
        }
        organizationStreamer = null
        screenSession?.stop()
        screenSession = null
        serviceScope.cancel()
        _uiState.value = LiveMediaUiState()
        writeState(false, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
