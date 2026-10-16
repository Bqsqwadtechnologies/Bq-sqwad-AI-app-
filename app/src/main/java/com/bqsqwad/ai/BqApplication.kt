package com.bqsqwad.ai

import android.app.Application
import android.app.Activity
import android.os.Bundle
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-wide visibility signal used to suppress background-style notifications while visible. */
object AppVisibility {
    @Volatile var isForeground: Boolean = false
        private set
    private val startedActivities = AtomicInteger(0)

    internal fun activityStarted() {
        isForeground = startedActivities.incrementAndGet() > 0
    }

    internal fun activityStopped() {
        isForeground = startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) } > 0
    }
}

class BqApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { AppVisibility.activityStarted() }
            override fun onActivityStopped(activity: Activity) { AppVisibility.activityStopped() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last_crash.txt").writeText(
                    "${System.currentTimeMillis()}\n${error.stackTraceToString()}"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

object InAppNotificationBus {
    private val _banner = MutableStateFlow<String?>(null)
    val banner: StateFlow<String?> = _banner

    fun show(message: String) {
        _banner.value = message.take(240)
    }

    fun clear() {
        _banner.value = null
    }
}
