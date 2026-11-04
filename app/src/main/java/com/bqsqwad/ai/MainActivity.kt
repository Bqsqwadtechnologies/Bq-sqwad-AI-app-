package com.bqsqwad.ai

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bqsqwad.ai.core.PresenceClient
import com.bqsqwad.ai.ui.BQSqwadApp
import com.bqsqwad.ai.ui.theme.BQSqwadTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var openConnectorsNonce by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One-time: users without an explicit theme choice start in White,
        // regardless of the device's system appearance.
        run {
            val device = getSharedPreferences("bq_device_prefs", MODE_PRIVATE)
            if (!device.getBoolean("white_default_v1", false)) {
                if (!device.getBoolean("theme_chosen", false)) {
                    getSharedPreferences("bq_sqwad_ui", MODE_PRIVATE)
                        .edit().putString("theme_mode", "light").apply()
                }
                device.edit().putBoolean("white_default_v1", true).apply()
            }
        }
        captureSharedText(intent)
        startPresenceHeartbeat()
        setContent {
            BQSqwadTheme {
                BQSqwadApp(
                    modifier = Modifier,
                    sharedText = sharedText,
                    onSharedTextConsumed = { sharedText = null },
                    openConnectorsNonce = openConnectorsNonce
                )
            }
        }
    }

    /** POST /presence/ping while the activity is started and the user is signed in. */
    private fun startPresenceHeartbeat() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val client = PresenceClient()
                while (isActive) {
                    runCatching { client.ping() }
                    delay(30_000L)
                }
            }
        }
    }

    // Keep the root chat visible when Android Back is pressed.
    @Deprecated("Deprecated in Android API; retained for the platform Back dispatcher contract.")
    override fun onBackPressed() {
        if (!isTaskRoot) super.onBackPressed()
        // Nested Compose destinations continue to handle Back themselves.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureSharedText(intent)
    }

    private fun captureSharedText(source: Intent?) {
        if (source == null) return
        val uri = source.data
        if (uri?.scheme == "https" && uri.host == "bqsqwadai.com" && uri.path.orEmpty().startsWith("/connectors")) {
            openConnectorsNonce += 1
        }
        val text = when (source.action) {
            Intent.ACTION_SEND -> source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> source.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null) sharedText = text
    }
}
