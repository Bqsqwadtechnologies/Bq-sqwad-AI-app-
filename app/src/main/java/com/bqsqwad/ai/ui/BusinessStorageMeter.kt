package com.bqsqwad.ai.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BusinessStorageMeter(
    usedBytes: Long,
    totalBytes: Long,
    showAddon: Boolean,
    showRenewalOnly: Boolean,
    modifier: Modifier = Modifier
) {
    val pct = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(pct, label = "storage")
    Column(modifier.fillMaxWidth().padding(12.dp)) {
        Text("Business storage", style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            "${usedBytes / (1024 * 1024)} MB of ${totalBytes / (1024 * 1024)} MB",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        when {
            showRenewalOnly -> Text("Renew your plan to restore full service.", color = MaterialTheme.colorScheme.tertiary)
            showAddon -> Text("You are near your storage limit. A storage add-on is available.")
        }
    }
}

@Composable
fun BusinessPlanExpiryBanner(
    expiresAtEpochMs: Long,
    serverNowEpochMs: Long,
    modifier: Modifier = Modifier
) {
    val remaining = (expiresAtEpochMs - serverNowEpochMs).coerceAtLeast(0)
    val days = remaining / 86_400_000
    val hours = (remaining % 86_400_000) / 3_600_000
    val mins = (remaining % 3_600_000) / 60_000
    val label = if (remaining <= 0) {
        "Your Business plan has expired. Renew to restore AI replies."
    } else {
        "Your Business plan expires in ${days}d ${hours}h ${mins}m"
    }
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            remaining <= 3 * 86_400_000 -> MaterialTheme.colorScheme.tertiary
            remaining <= 12 * 3_600_000 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier.padding(12.dp)
    )
}
