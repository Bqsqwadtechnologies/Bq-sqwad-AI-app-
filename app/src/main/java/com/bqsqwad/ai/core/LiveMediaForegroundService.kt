package com.bqsqwad.ai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class LiveMediaForegroundService : Service() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CAMERA = "camera"
        const val MODE_VOICE = "voice"
        private const val CHANNEL_ID = "bq_live_media"
        private const val NOTIFICATION_ID = 4101
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BQ Sqwad AI live sessions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible status for an active BQ Sqwad AI camera or voice session."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE).orEmpty()
        val camera = mode == MODE_CAMERA
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.bqsqwad.ai.R.drawable.bq_sqwad_logo)
            .setContentTitle("BQ Sqwad AI")
            .setContentText(if (camera) "Live camera is active" else "Live voice is active")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && camera ->
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, type) else startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
