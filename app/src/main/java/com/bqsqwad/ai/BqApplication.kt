package com.bqsqwad.ai

import android.app.Application
import java.io.File

class BqApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last_crash.txt").writeText(
                    "\${System.currentTimeMillis()}\\n\${error.stackTraceToString()}"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}