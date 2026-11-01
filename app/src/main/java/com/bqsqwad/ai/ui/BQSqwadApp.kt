@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.OnboardingProfile

/**
 * Temporary host after a failed large-file API update. Full 8k-line source is in
 * git history: commit 2accfc5b40c7f099bed7ff89b8b38ea6a8377297.
 */
@Composable
fun BQSqwadApp(
    modifier: Modifier = Modifier,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    openConnectorsNonce: Int = 0,
) {
    var selectedModelKey by rememberSaveable { mutableStateOf("bq-7-star") }
    var showOnboarding by remember { mutableStateOf(false) }

    // Consume shared text so MainActivity state does not stick.
    if (sharedText != null) {
        onSharedTextConsumed()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (showOnboarding) {
            OnboardingSurveyScreen(
                onComplete = { _: OnboardingProfile -> showOnboarding = false },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("BQ Sqwad AI", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Main UI is recovering from a large-file push limit. Model selector is live.",
                    style = MaterialTheme.typography.bodyMedium
                )
                ModelSelector(
                    selectedKey = selectedModelKey,
                    onSelect = { selectedModelKey = it.modelKey },
                    onBuyCredits = { }
                )
                PlusAttachmentButton(onAction = { })
                Text(
                    "Selected model: $selectedModelKey",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
