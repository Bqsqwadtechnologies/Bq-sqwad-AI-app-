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
import com.bqsqwad.ai.InAppNotificationBus
import com.bqsqwad.ai.R
import com.google.firebase.auth.FirebaseAuth
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import java.util.Calendar

/** Privacy-safe local lifecycle notices. Notification content never includes account or project details. */
object BqLifecycleNotifications {
    private const val PREFS = "bq_lifecycle_notices"
    private const val REMINDERS_CHANNEL_ID = "bq_sqwad_reminders"
    private const val REPLIES_CHANNEL_ID = "bq_sqwad_replies"
    private const val BIRTHDAY_CHANNEL_ID = "bq_sqwad_birthday"
    private const val ACCOUNT_UID = "lifecycle_account_uid"
    private const val FIRST_OPEN = "first_open_at"
    private const val LAST_OPEN = "last_open_at"
    private const val NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val COMEBACK_COUNT = "comeback_count"
    private const val SENT_MILESTONES = "sent_age_milestones"
    private const val LAST_MILESTONE_DAY = "last_milestone_local_day"
    private const val NEXT_ID = "next_notification_id"
    private const val INACTIVITY_REQUEST_CODE = 4012
    private const val BIRTHDAY_REQUEST_CODE = 4013
    const val ACTION_BIRTHDAY_MILESTONE = "com.bqsqwad.ai.ACTION_BIRTHDAY_MILESTONE"

    fun notificationsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(NOTIFICATIONS_ENABLED, true)

    fun birthTimestamp(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getLong(FIRST_OPEN, 0L)
        if (saved > 0L) return saved
        return FirebaseAuth.getInstance().currentUser?.metadata?.creationTimestamp?.takeIf { it > 0L }
            ?: System.currentTimeMillis()
    }

    fun onAppOpened(context: Context, notificationsEnabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val previousUid = prefs.getString(ACCOUNT_UID, null)
            if (previousUid != currentUser.uid) {
                val accountCreatedAt = currentUser.metadata?.creationTimestamp?.takeIf { it > 0L } ?: now
                prefs.edit()
                    .putString(ACCOUNT_UID, currentUser.uid)
                    .putLong(FIRST_OPEN, accountCreatedAt)
                    .remove(LAST_OPEN)
                    .remove(SENT_MILESTONES)
                    .putInt(COMEBACK_COUNT, 0)
                    .apply()
            }
        }
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

        processBirthdayMilestone(context, firstOpen, now, allowPost = !AppVisibility.isForeground)
    }

    fun onBirthdayAlarm(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(NOTIFICATIONS_ENABLED, true)) return
        val firstOpen = prefs.getLong(FIRST_OPEN, 0L)
        if (firstOpen <= 0L) return
        processBirthdayMilestone(context, firstOpen, System.currentTimeMillis(), allowPost = !AppVisibility.isForeground)
    }

    private data class AgeMilestone(
        val key: String,
        val at: Long,
        val title: String,
        val body: String,
        val recurring: Boolean
    )

    private fun processBirthdayMilestone(context: Context, firstOpen: Long, now: Long, allowPost: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = prefs.getStringSet(SENT_MILESTONES, emptySet()).orEmpty().toMutableSet()
        val candidates = birthdayMilestones(firstOpen, now)
        val overdue = candidates.filter { it.at <= now && it.key !in sent }
        val todayKey = localMilestoneDay(now)
        // One celebration per local calendar day. If several milestones were missed,
        // catch up in chronological order on successive polite morning reminders.
        val due = if (prefs.getString(LAST_MILESTONE_DAY, null) == todayKey) {
            null
        } else {
            overdue.firstOrNull { it.key.startsWith("year:") }
                ?: overdue.firstOrNull { it.key.startsWith("month:") }
                ?: overdue.minByOrNull { it.at }
        }
        if (due != null) {
            if (allowPost && !AppVisibility.isForeground) {
                post(context, due.title, due.body)
            } else {
                InAppNotificationBus.show("${due.title} · ${due.body}")
            }
            sent.add(due.key)
            if (due.key.startsWith("year:")) {
                sent.add("day:1")
                sent.add("day:7")
                val monthIndex = calendarMonthIndex(now) - calendarMonthIndex(firstOpen)
                if (monthIndex > 0) sent.add("month:$monthIndex")
            } else if (due.key.startsWith("month:")) {
                sent.add("day:1")
                sent.add("day:7")
            }
            prefs.edit()
                .putStringSet(SENT_MILESTONES, sent)
                .putString(LAST_MILESTONE_DAY, todayKey)
                .apply()
        }
        scheduleNextBirthdayReminder(context, firstOpen, sent, now)
    }

    private fun localMilestoneDay(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun nextMilestoneCatchupTime(now: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        if (currentHour >= 9) calendar.add(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 9)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun birthdayMilestones(firstOpen: Long, now: Long): List<AgeMilestone> {
        val oneDay = TimeUnit.DAYS.toMillis(1)
        val oneWeek = TimeUnit.DAYS.toMillis(7)
        val result = mutableListOf(
            AgeMilestone("day:1", firstOpen + oneDay, "BQ Sqwad AI is one day old!", "One day of growing together. Open BQ Sqwad AI to celebrate!", false),
            AgeMilestone("day:7", firstOpen + oneWeek, "BQ Sqwad AI is one week old!", "One week of ideas and progress. Keep growing with your AI!", false)
        )
        val currentMonthIndex = calendarMonthIndex(now) - calendarMonthIndex(firstOpen)
        if (currentMonthIndex >= 1) {
            val monthAt = monthAnniversary(firstOpen, currentMonthIndex)
            result += AgeMilestone(
                "month:$currentMonthIndex", monthAt, "BQ Sqwad AI monthiversary!",
                "Another month of building together. Celebrate your AI journey!", true
            )
        } else {
            result += AgeMilestone("month:1", monthAnniversary(firstOpen, 1), "BQ Sqwad AI monthiversary!", "Your first month together is here. Celebrate your AI journey!", true)
        }
        val firstCalendar = Calendar.getInstance().apply { timeInMillis = firstOpen }
        val nowCalendar = Calendar.getInstance().apply { timeInMillis = now }
        val yearIndex = (nowCalendar.get(Calendar.YEAR) - firstCalendar.get(Calendar.YEAR)).coerceAtLeast(1)
        val yearAt = yearAnniversary(firstOpen, yearIndex)
        result += AgeMilestone(
            "year:$yearIndex", yearAt, "Happy BQ Sqwad AI Birthday!",
            "Another year of growing, learning, and creating together. Celebrate your AI birthday!", true
        )
        return result
    }

    private fun calendarMonthIndex(timestamp: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH)
    }

    private fun monthAnniversary(firstOpen: Long, months: Int): Long = anniversary(firstOpen, Calendar.MONTH, months)
    private fun yearAnniversary(firstOpen: Long, years: Int): Long = anniversary(firstOpen, Calendar.YEAR, years)

    private fun anniversary(firstOpen: Long, field: Int, amount: Int): Long =
        bqAnniversaryTimestamp(firstOpen, field, amount)

    private fun scheduleNextBirthdayReminder(context: Context, firstOpen: Long, sent: Set<String>, now: Long = System.currentTimeMillis()) {
        val candidates = birthdayMilestones(firstOpen, now)
        val overdue = candidates.any { it.key !in sent && it.at <= now }
        val futureAt = if (overdue) {
            nextMilestoneCatchupTime(now)
        } else {
            val future = candidates.filter { it.key !in sent && it.at > now }.minByOrNull { it.at }
            future?.at ?: run {
                // Monthly and yearly milestones repeat forever; advance to the next
                // calendar anniversaries once the currently represented milestone is sent.
                val monthIndex = (calendarMonthIndex(now) - calendarMonthIndex(firstOpen) + 1).coerceAtLeast(1)
                val yearIndex = (Calendar.getInstance().get(Calendar.YEAR) -
                    Calendar.getInstance().apply { timeInMillis = firstOpen }.get(Calendar.YEAR) + 1).coerceAtLeast(1)
                val monthAt = monthAnniversary(firstOpen, monthIndex)
                val yearAt = yearAnniversary(firstOpen, yearIndex)
                minOf(monthAt, yearAt).takeIf { it > now } ?: return
            }
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, BqInactivityReminderReceiver::class.java).setAction(ACTION_BIRTHDAY_MILESTONE)
        val pending = PendingIntent.getBroadcast(
            context, BIRTHDAY_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val trigger = if (overdue) futureAt else politeBirthdayTrigger(futureAt, now)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            else alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    private fun politeBirthdayTrigger(targetAt: Long, now: Long): Long {
        val calendar = Calendar.getInstance()
        if (targetAt > now) {
            calendar.timeInMillis = targetAt
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            if (hour in 9..19) return targetAt
            calendar.set(Calendar.HOUR_OF_DAY, 9)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            if (calendar.timeInMillis < now) calendar.add(Calendar.DAY_OF_YEAR, 1)
            return calendar.timeInMillis
        }
        calendar.timeInMillis = now
        if (AppVisibility.isForeground || calendar.get(Calendar.HOUR_OF_DAY) >= 20) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 9)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
        if (calendar.get(Calendar.HOUR_OF_DAY) < 9) {
            calendar.set(Calendar.HOUR_OF_DAY, 9)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
        return now + 60_000L
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
        val titleLower = title.lowercase()
        val channelId = when {
            titleLower.contains("birthday") || titleLower.contains("monthiversary") || titleLower.contains("milestone") || titleLower.contains("week together") || titleLower.contains("day old") || titleLower.contains("week old") || titleLower.contains("month old") || titleLower.contains("year old") -> BIRTHDAY_CHANNEL_ID
            titleLower.contains("reply") -> REPLIES_CHANNEL_ID
            else -> REMINDERS_CHANNEL_ID
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(
                NotificationChannel(REPLIES_CHANNEL_ID, "Replies", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(REMINDERS_CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(BIRTHDAY_CHANNEL_ID, "Birthday", NotificationManager.IMPORTANCE_DEFAULT)
            ).forEach { channel ->
                if (manager.getNotificationChannel(channel.id) == null) manager.createNotificationChannel(channel)
            }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt(NEXT_ID, 4000)
        prefs.edit().putInt(NEXT_ID, if (id >= Int.MAX_VALUE - 1) 4000 else id + 1).apply()
        if (AppVisibility.isForeground) return
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.bq_notification_mark)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.bq_sqwad_logo))
            .setColor(android.graphics.Color.rgb(39, 112, 235))
            .setContentTitle("BQ Sqwad AI")
            .setContentText("$title · $body")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title · $body"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}


/** Calendar anniversary helper shared by birthday scheduling and regression tests.
 * Month-end dates clamp to the last valid day (for example, January 31 -> February 28/29).
 */
internal fun bqAnniversaryTimestamp(firstOpen: Long, field: Int, amount: Int): Long {
    val source = Calendar.getInstance().apply { timeInMillis = firstOpen }
    val dayOfMonth = source.get(Calendar.DAY_OF_MONTH)
    source.set(Calendar.DAY_OF_MONTH, 1)
    source.add(field, amount)
    source.set(Calendar.DAY_OF_MONTH, minOf(dayOfMonth, source.getActualMaximum(Calendar.DAY_OF_MONTH)))
    return source.timeInMillis
}
