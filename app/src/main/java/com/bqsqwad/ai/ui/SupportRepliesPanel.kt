package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch

@Composable
fun SupportRepliesPanel() {
    var replies by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        try {
            val payload = WorkspaceApiClient().supportMessages()
            val items = payload.optJSONArray("replies")
            replies = if (items == null) emptyList() else (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                item.optString("createdAt") to item.optString("message")
            }
            loaded = true
        } catch (_: Exception) {
            loaded = false
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Support replies", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { scope.launch { refresh() } }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (loading) "Refreshing replies…" else "Refresh support replies")
        }
        if (replies.isEmpty()) {
            Text(
                if (loaded) "No support replies yet." else "Sign in and refresh to check for support replies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            replies.forEach { (createdAt, message) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Text(createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
