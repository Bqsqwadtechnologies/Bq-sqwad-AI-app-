package com.bqsqwad.ai.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/** Privacy-safe local lifecycle notices. Notification content never includes account or project details. */
object BqLifecycleNotifications {
    private const val PREFS = "bq_lifecycle_notices"
    private const val CHANNEL_ID = "bq_sqwad_lifecycle"
    private const val CHANNEL_NAME = "BQ Sqwad AI updates"
    private const val FIRST_OPEN = "first_open_at"
    private const val LAST_OPEN = "last_open_at"
    private const val SENT_MILESTONES = "sent_age_milestones"
    private const val NEXT_ID = "next_notification_id"

    fun onAppOpened(context: Context, notificationsEnabled: Boolean) {
        if (!notificationsEnabled) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val firstOpen = prefs.getLong(FIRST_OPEN, 0L).let { saved ->
            if (saved > 0L) saved else now.also { prefs.edit().putLong(FIRST_OPEN, it).apply() }
        }
        val previousOpen = prefs.getLong(LAST_OPEN, 0L)
        if (previousOpen > 0L && now - previousOpen >= TimeUnit.DAYS.toMillis(7)) {
            post(context, "Your project is waiting", "Come back to BQ Sqwad AI and continue your project or pick up where you left off.")
        }
        prefs.edit().putLong(LAST_OPEN, now).apply()

        val ageDays = TimeUnit.MILLISECONDS.toDays((now - firstOpen).coerceAtLeast(0L))
        val milestones = listOf(
            365L to "BQ Sqwad AI Birthday",
            30L to "BQ Sqwad AI is one month old!",
            7L to "BQ Sqwad AI is one week old!",
            1L to "BQ Sqwad AI is one day old!"
        )
        val sent = prefs.getStringSet(SENT_MILESTONES, emptySet()).orEmpty().toMutableSet()
        val due = milestones.firstOrNull { (day, _) -> ageDays >= day && day.toString() !in sent }
        if (due != null) {
            val (day, title) = due
            val body = when (day) {
                1L -> "BQ Sqwad AI is one day old. Wish your AI a happy birthday!"
                7L -> "BQ Sqwad AI is one week old today. Wish your AI a happy birthday!"
                30L -> "BQ Sqwad AI is one month old. Celebrate the journey and keep building!"
                else -> "It is BQ Sqwad AI's birthday! Thank you for growing with your AI."
            }
            post(context, title, body)
            milestones.filter { it.first <= day }.forEach { sent.add(it.first.toString()) }
            prefs.edit().putStringSet(SENT_MILESTONES, sent).apply()
        }
    }

    fun notifyResponseFinished(context: Context, notificationsEnabled: Boolean) {
        if (notificationsEnabled) post(context, "BQ Sqwad AI has replied", "Your response is ready. Return to the app to continue.")
    }

    private fun post(context: Context, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt(NEXT_ID, 4000)
        prefs.edit().putInt(NEXT_ID, if (id >= Int.MAX_VALUE - 1) 4000 else id + 1).apply()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
