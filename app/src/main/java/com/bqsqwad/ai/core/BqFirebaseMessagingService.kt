package com.bqsqwad.ai.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
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
        @Volatile private var cachedUploadedNotificationLogo: Bitmap? = null
        @Volatile private var cachedUploadedNotificationLogoAt: Long = 0L

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
        // Fetch the latest uploaded logo once per process and use it for both
        // the expanded artwork and a system-compliant monochrome status icon.
        val uploadedLogo = loadUploadedNotificationLogo()
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(uploadedLogo?.let { createMonochromeNotificationIcon(it) }
                ?: IconCompat.createWithResource(this, R.drawable.bq_notification_logo))
            .setLargeIcon(uploadedLogo ?: BitmapFactory.decodeResource(resources, notificationLogoResourceId(), BitmapFactory.Options().apply { inSampleSize = 4 }))
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

    // Prefer the owner's uploaded PNG from the Android repository so the logo is not
    // frozen to whatever image happened to be compiled into an older APK. Keep a
    // bounded in-memory cache and fall back to the bundled resource when offline.
    private fun resolveUploadedNotificationLogoUrl(): String {
        val fallback = "https://bqsqwadai.com/bq-notification-logo.png?v=20261020-bq-notification-v7"
        val connection = runCatching {
            (URL("https://bqsqwadai.com/api/public/app-config?notificationLogo=1").openConnection() as HttpURLConnection)
        }.getOrNull() ?: return fallback
        return try {
            connection.connectTimeout = 2500
            connection.readTimeout = 3500
            connection.instanceFollowRedirects = true
            val raw = connection.inputStream.use { it.bufferedReader().readText() }.take(32_000)
            val candidate = JSONObject(raw).optString("logoUrl", "")
            if (candidate.startsWith("https://") &&
                (candidate.contains("firebasestorage.googleapis.com/") ||
                 candidate.contains("storage.googleapis.com/") ||
                 candidate.startsWith("https://bqsqwadai.com/"))) candidate else fallback
        } catch (_: Exception) {
            fallback
        } finally {
            connection.disconnect()
        }
    }

    private fun loadUploadedNotificationLogo(): Bitmap? {
        val now = System.currentTimeMillis()
        cachedUploadedNotificationLogo?.takeUnless { it.isRecycled }?.let {
            if (now - cachedUploadedNotificationLogoAt < 5 * 60 * 1000L) return it
        }
        val connection = runCatching {
            (URL(resolveUploadedNotificationLogoUrl()).openConnection() as HttpURLConnection)
        }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 2500
            connection.readTimeout = 3500
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/png,image/*")
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 5_000_000) {
                null
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                val decoded = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
                )
                if (decoded != null) {
                    cachedUploadedNotificationLogo = decoded
                    cachedUploadedNotificationLogoAt = now
                }
                decoded
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Android status-bar icons are rendered as a monochrome mask by the OS.
     * Derive that mask from the current uploaded logo instead of reusing an
     * old hard-coded BX/BQ glyph. The full-colour source remains the large icon.
     */
    private fun createMonochromeNotificationIcon(source: Bitmap): IconCompat {
        val size = 64
        val scaled = Bitmap.createScaledBitmap(source, size, size, true)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val corners = intArrayOf(
            scaled.getPixel(0, 0), scaled.getPixel(size - 1, 0),
            scaled.getPixel(0, size - 1), scaled.getPixel(size - 1, size - 1)
        )
        val backgroundAlpha = corners.map { Color.alpha(it) }.average().toInt()
        val backgroundRed = corners.map { Color.red(it) }.average().toInt()
        val backgroundGreen = corners.map { Color.green(it) }.average().toInt()
        val backgroundBlue = corners.map { Color.blue(it) }.average().toInt()
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val pixel = scaled.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val distance = kotlin.math.abs(Color.red(pixel) - backgroundRed) +
                    kotlin.math.abs(Color.green(pixel) - backgroundGreen) +
                    kotlin.math.abs(Color.blue(pixel) - backgroundBlue)
                val visible = if (backgroundAlpha < 24) alpha else if (distance >= 72) alpha else 0
                pixels[y * size + x] = Color.argb(visible, 255, 255, 255)
            }
        }
        output.setPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        return IconCompat.createWithBitmap(output)
    }

    // Use the uploaded notification-logo resource for both status-bar and expanded notification artwork.
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