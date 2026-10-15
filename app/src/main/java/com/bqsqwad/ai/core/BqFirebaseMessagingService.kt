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
        private const val CHANNEL_ID = "bq_sqwad_ai_updates"
        private const val CHANNEL_NAME = "BQ Sqwad AI updates"
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
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "BQ Sqwad AI"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "You have a new BQ Sqwad AI update."
        val workspace = message.data["workspace"].orEmpty()

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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.bq_notification_mark)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.bq_sqwad_logo))
            .setContentTitle(title.take(120))
            .setContentText(body.take(500))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(2000)))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify(
            (System.currentTimeMillis() and 0x7fffffff).toInt(),
            notification
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
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
