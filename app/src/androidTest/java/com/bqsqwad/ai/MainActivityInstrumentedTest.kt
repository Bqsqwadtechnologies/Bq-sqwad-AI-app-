package com.bqsqwad.ai

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator smoke tests for the real native entry point.
 *
 * These tests catch launch-time crashes and malformed Android share-intent
 * handling. They intentionally do not claim to verify live backend, billing,
 * camera, microphone, or provider behavior.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @Test
    fun mainActivityLaunchesAndRemainsActive() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Activity window should be created", activity.window)
                assertFalse("MainActivity should not finish immediately", activity.isFinishing)
            }
        }
    }

    @Test
    fun sharedTextIntentOpensMainActivityWithoutCrash() {
        val intent = Intent(Intent.ACTION_SEND)
            .setClassName("com.bqsqwad.ai", "com.bqsqwad.ai.MainActivity")
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Instrumented share-intent smoke test")

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Shared-text launch should create an Activity window", activity.window)
                assertFalse("Shared-text launch should not immediately finish", activity.isFinishing)
            }
        }
    }

    @Test
    fun processTextIntentOpensMainActivityWithoutCrash() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .setClassName("com.bqsqwad.ai", "com.bqsqwad.ai.MainActivity")
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "Instrumented selected-text smoke test")

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Process-text launch should create an Activity window", activity.window)
                assertFalse("Process-text launch should not immediately finish", activity.isFinishing)
            }
        }
    }
}
