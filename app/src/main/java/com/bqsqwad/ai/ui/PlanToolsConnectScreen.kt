package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch

data class PlanToolUi(val id: String, val name: String, val connected: Boolean)

/**
 * Plan-scoped connector handoff. Connection status is read from the authenticated
 * Worker endpoint; catalog presence alone is never shown as a live connection.
 */
@Composable
fun PlanToolsConnectScreen(
    tools: List<PlanToolUi>,
    api: WorkspaceApiClient,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val connectedById = remember { mutableStateMapOf<String, Boolean>() }
    val statusById = remember { mutableStateMapOf<String, String>() }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        refreshing = true
        tools.forEach { tool ->
            runCatching { api.connectorStatus(tool.id) }
                .onSuccess { response ->
                    val connected = response.optBoolean("connected", false)
                    connectedById[tool.id] = connected
                    statusById[tool.id] = if (connected) {
                        response.optString("accountName").takeIf { it.isNotBlank() }?.let { "Connected · $it" } ?: "Connected"
                    } else {
                        "Not connected"
                    }
                }
                .onFailure { error ->
                    val message = error.message.orEmpty()
                    statusById[tool.id] = if (message.contains("unsupported_connector")) {
                        "Not yet supported by the secure connector service"
                    } else {
                        "Status unavailable · refresh to retry"
                    }
                    connectedById[tool.id] = false
                }
        }
        refreshing = false
    }

    LaunchedEffect(tools.map { it.id }.joinToString("|")) { refresh() }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect tools", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Only integrations included in your plan are listed. Connection status is checked against your signed-in account.",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(enabled = !refreshing, onClick = { scope.launch { refresh() } }) {
                Text(if (refreshing) "Checking…" else "Refresh status")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tools, key = { it.id }) { tool ->
                val connected = connectedById[tool.id] ?: false
                val status = statusById[tool.id] ?: "Checking connection…"
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(tool.name, style = MaterialTheme.typography.titleMedium)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!status.startsWith("Not yet supported")) {
                        Button(onClick = { onConnect(tool.id) }) {
                            Text(if (connected) "Reconnect" else "Connect")
                        }
                    }
                }
            }
        }
    }
}
