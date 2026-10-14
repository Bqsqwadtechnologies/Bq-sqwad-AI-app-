package com.bqsqwad.ai.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Posts a privacy-safe return reminder after seven days without opening the app. */
class BqInactivityReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BqLifecycleNotifications.onInactivityAlarm(context.applicationContext)
    }
}
