package com.bqsqwad.ai.core

import android.graphics.BitmapFactory
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bqsqwad.ai.MainActivity
import com.bqsqwad.ai.AppVisibility
import com.bqsqwad.ai.InAppNotificationBus
import com.bqsqwad.ai.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BqFirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        private const val REPLIES_CHANNEL_ID = "bq_sqwad_replies"
        private const val REMINDERS_CHANNEL_ID = "bq_sqwad_reminders"
        private const val BIRTHDAY_CHANNEL_ID = "bq_sqwad_birthday"
        private const val LIVE_CHANNEL_ID = "bq_live_media"
        private const val BASE_PATH = "/notifications/token"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        registerToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Respect the user's global notification preference and avoid foreground duplicates.
        if (!BqLifecycleNotifications.notificationsEnabled(this)) return
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "BQ Sqwad AI"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "You have a new BQ Sqwad AI update."
        if (AppVisibility.isForeground) {
            InAppNotificationBus.show(if (title == "BQ Sqwad AI") body else "$title · $body")
            return
        }
        val workspace = message.data["workspace"].orEmpty()
        val category = (message.data["category"] ?: title).lowercase()
        val channelId = when {
            category.contains("live") -> LIVE_CHANNEL_ID
            category.contains("birthday") || category.contains("milestone") -> BIRTHDAY_CHANNEL_ID
            category.contains("reply") -> REPLIES_CHANNEL_ID
            else -> REMINDERS_CHANNEL_ID
        }
        val displayBody = if (title.equals("BQ Sqwad AI", ignoreCase = true)) body else "$title · $body"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("bq_notification_workspace", workspace)
        }
        val pending = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() and 0x7fffffff).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.bq_notification_mark)
            .setLargeIcon(BitmapFactory.decodeResource(resources, notificationLogoResourceId(), BitmapFactory.Options().apply { inSampleSize = 4 }))
            .setColor(android.graphics.Color.rgb(39, 112, 235))
            .setContentTitle("BQ Sqwad AI")
            .setContentText(displayBody.take(500))
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody.take(2000)))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify(
            (System.currentTimeMillis() and 0x7fffffff).toInt(),
            notification
        )
    }

    // Full uploaded brand artwork is the large icon; Android's status-bar icon must be a monochrome alpha-only mark.
    private fun notificationLogoResourceId(): Int = R.drawable.bq_notification_logo

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            listOf(
                NotificationChannel(REPLIES_CHANNEL_ID, "Replies", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(REMINDERS_CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(BIRTHDAY_CHANNEL_ID, "Birthday", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(LIVE_CHANNEL_ID, "Live sessions", NotificationManager.IMPORTANCE_LOW)
            ).forEach { channel ->
                if (manager.getNotificationChannel(channel.id) == null) manager.createNotificationChannel(channel)
            }
        }
    }

    private fun registerToken(token: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val idToken = com.google.android.gms.tasks.Tasks.await(user.getIdToken(false)).token
                    ?: return@runCatching
                val url = URL("${ApiConfig.baseUrl}$BASE_PATH")
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer $idToken")
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use {
                    it.write(JSONObject().put("token", token).put("platform", "android").toString().toByteArray())
                }
                c.inputStream.close()
                c.disconnect()
            }
        }
    }
}
