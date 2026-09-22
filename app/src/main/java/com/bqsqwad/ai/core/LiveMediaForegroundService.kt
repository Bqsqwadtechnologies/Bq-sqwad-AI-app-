package com.bqsqwad.ai.core

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class LiveMediaForegroundService : Service() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_LENS = "lens"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"
        const val ACTION_STOP = "stop"
        const val MODE_CAMERA = "camera"
        const val MODE_VOICE = "voice"
        const val MODE_SCREEN = "screen"
        private const val CHANNEL_ID = "bq_live_media"
        private const val NOTIFICATION_ID = 4101
        private const val PREFS = "bq_live_media"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val analyzing = AtomicBoolean(false)
    private var cameraSession: LiveCameraCaptureSession? = null
    private var screenSession: ScreenCaptureSession? = null
    private var mode = ""
    private var prompt = "Describe what is visible."
    private var lens = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK

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
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "BQ Sqwad AI live sessions", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Visible status for active BQ Sqwad AI live sessions."
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startCamera() {
        if (cameraSession != null) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            writeError("Camera permission is required.")
            stopSelf()
            return
        }
        cameraSession = LiveCameraCaptureSession(
            this,
            lens,
            onFrame = { bytes -> analyzeFrame(bytes, "image/jpeg") },
            onError = { writeError(it.message ?: "Camera capture failed.") }
        ).also { runCatching { it.start() }.onFailure { writeError(it.message ?: "Camera could not start."); stopSelf() } }
        writeState(true, "")
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
                val result = ChatApiClient().analyzeCameraFrame(prompt, encoded, mimeType)
                writeState(true, result.text)
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
    }

    private fun writeError(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("active", false)
            .putString("mode", mode)
            .putString("answer", message)
            .apply()
    }

    override fun onDestroy() {
        cameraSession?.stop()
        cameraSession = null
        screenSession?.stop()
        screenSession = null
        serviceScope.cancel()
        writeState(false, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
