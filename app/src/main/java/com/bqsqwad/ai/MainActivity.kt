package com.bqsqwad.ai

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bqsqwad.ai.ui.BQSqwadApp
import com.bqsqwad.ai.ui.theme.BQSqwadTheme

class MainActivity : FragmentActivity() {
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureSharedText(intent)
        setContent {
            BQSqwadTheme {
                BQSqwadApp(
                    modifier = Modifier,
                    sharedText = sharedText,
                    onSharedTextConsumed = { sharedText = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureSharedText(intent)
    }

    private fun captureSharedText(source: Intent?) {
        if (source == null) return
        val text = when (source.action) {
            Intent.ACTION_SEND -> source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> source.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null) sharedText = text
    }
}
