package com.bqsqwad.ai.core

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import com.bqsqwad.ai.AppVisibility
import com.bqsqwad.ai.R
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
    private const val NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val COMEBACK_COUNT = "comeback_count"
    private const val SENT_MILESTONES = "sent_age_milestones"
    private const val NEXT_ID = "next_notification_id"
    private const val INACTIVITY_REQUEST_CODE = 4012
    private const val BIRTHDAY_REQUEST_CODE = 4013
    const val ACTION_BIRTHDAY_MILESTONE = "com.bqsqwad.ai.ACTION_BIRTHDAY_MILESTONE"

    fun onAppOpened(context: Context, notificationsEnabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().putBoolean(NOTIFICATIONS_ENABLED, notificationsEnabled).apply()
        if (!notificationsEnabled) {
            prefs.getLong(FIRST_OPEN, 0L).takeIf { it > 0L }
                ?: prefs.edit().putLong(FIRST_OPEN, now).apply()
            prefs.edit().putLong(LAST_OPEN, now).apply()
            cancelInactivityReminder(context)
            cancelBirthdayReminder(context)
            return
        }
        val firstOpen = prefs.getLong(FIRST_OPEN, 0L).let { saved ->
            if (saved > 0L) saved else now.also { prefs.edit().putLong(FIRST_OPEN, it).apply() }
        }
        val previousOpen = prefs.getLong(LAST_OPEN, 0L)
        if (previousOpen > 0L && now - previousOpen >= TimeUnit.DAYS.toMillis(2) && !AppVisibility.isForeground) {
            post(context, "Your AI is waiting for you", "It has been a couple of days. Come back and pick up where you left off.")
        }
        prefs.edit().putLong(LAST_OPEN, now).apply()
        prefs.edit().putInt(COMEBACK_COUNT, 0).apply()
        scheduleInactivityReminder(context, now + TimeUnit.DAYS.toMillis(2))

        val ageDays = TimeUnit.MILLISECONDS.toDays((now - firstOpen).coerceAtLeast(0L))
        val milestones = listOf(
            365L to "BQ Sqwad AI Birthday",
            30L to "BQ Sqwad AI is one month old!",
            7L to "BQ Sqwad AI is one week old!",
            1L to "BQ Sqwad AI is one day old!"
        )
        val sent = prefs.getStringSet(SENT_MILESTONES, mutableSetOf<String>()).orEmpty().toMutableSet()
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
        scheduleNextBirthdayReminder(context, firstOpen, sent)
    }

    fun onBirthdayAlarm(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val firstOpen = prefs.getLong(FIRST_OPEN, 0L)
        if (firstOpen <= 0L) return
        val now = System.currentTimeMillis()
        val ageDays = TimeUnit.MILLISECONDS.toDays((now - firstOpen).coerceAtLeast(0L))
        val milestones = listOf(
            365L to "BQ Sqwad AI Birthday",
            30L to "BQ Sqwad AI is one month old!",
            7L to "BQ Sqwad AI is one week old!",
            1L to "BQ Sqwad AI is one day old!"
        )
        val sent = prefs.getStringSet(SENT_MILESTONES, mutableSetOf<String>()).orEmpty().toMutableSet()
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
        scheduleNextBirthdayReminder(context, firstOpen, sent)
    }

    private fun scheduleNextBirthdayReminder(context: Context, firstOpen: Long, sent: Set<String>) {
        val nextDay = listOf(1L, 7L, 30L, 365L).firstOrNull { it.toString() !in sent } ?: return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, BqInactivityReminderReceiver::class.java).setAction(ACTION_BIRTHDAY_MILESTONE)
        val pending = PendingIntent.getBroadcast(
            context, BIRTHDAY_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val trigger = maxOf(System.currentTimeMillis() + 60_000L, firstOpen + TimeUnit.DAYS.toMillis(nextDay))
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            else alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun notifyResponseFinished(context: Context, notificationsEnabled: Boolean) {
        if (notificationsEnabled && !AppVisibility.isForeground) {
            post(context, "Your reply is ready", "BQ Sqwad AI has finished. Tap to read it.")
        }
    }

    fun onInactivityAlarm(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastOpen = prefs.getLong(LAST_OPEN, 0L)
        val now = System.currentTimeMillis()
        if (!prefs.getBoolean(NOTIFICATIONS_ENABLED, true)) return
        if (AppVisibility.isForeground) {
            scheduleInactivityReminder(context, now + TimeUnit.DAYS.toMillis(2))
            return
        }
        val count = prefs.getInt(COMEBACK_COUNT, 0)
        if (lastOpen > 0L && now - lastOpen >= TimeUnit.DAYS.toMillis(2) && count < 3) {
            val reminders = listOf(
                "Your AI is waiting for you" to "It has been a couple of days. Come back and pick up where you left off.",
                "A new idea is one tap away" to "Open BQ Sqwad AI and let your AI help with today's goals.",
                "We saved your place" to "Your chats and projects are right where you left them."
            )
            val (title, body) = reminders[count.coerceIn(0, reminders.lastIndex)]
            post(context, title, body)
            prefs.edit().putInt(COMEBACK_COUNT, count + 1).apply()
            if (count + 1 < 3) scheduleInactivityReminder(context, now + TimeUnit.DAYS.toMillis(2))
        } else if (lastOpen > 0L && count < 3) {
            scheduleInactivityReminder(context, lastOpen + TimeUnit.DAYS.toMillis(2))
        }
    }

    private fun scheduleInactivityReminder(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, BqInactivityReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, INACTIVITY_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }
    }

    private fun cancelInactivityReminder(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, BqInactivityReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, INACTIVITY_REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    private fun cancelBirthdayReminder(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, BqInactivityReminderReceiver::class.java).setAction(ACTION_BIRTHDAY_MILESTONE)
        val pending = PendingIntent.getBroadcast(
            context, BIRTHDAY_REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    fun onBoot(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(NOTIFICATIONS_ENABLED, true)) return
        val now = System.currentTimeMillis()
        val lastOpen = prefs.getLong(LAST_OPEN, 0L)
        if (lastOpen > 0L && prefs.getInt(COMEBACK_COUNT, 0) < 3) {
            scheduleInactivityReminder(context, maxOf(now + 60_000L, lastOpen + TimeUnit.DAYS.toMillis(2)))
        }
        val firstOpen = prefs.getLong(FIRST_OPEN, 0L)
        if (firstOpen > 0L) {
            val sent = prefs.getStringSet(SENT_MILESTONES, emptySet()).orEmpty()
            scheduleNextBirthdayReminder(context, firstOpen, sent)
        }
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
        if (AppVisibility.isForeground) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.bq_notification_mark)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.bq_sqwad_logo))
            .setColor(android.graphics.Color.rgb(39, 112, 235))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
