package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

private val EDITOR_WORKSPACES = listOf(
    "CHAT","VIDEO","SEARCH","TRUTH_GUARD","FRONTIER_LAB","CAMERA","VOICE","PERSONAL","LIFE",
    "COMPANION","LEARNING","BUSINESS","ORGANIZATION_STUDIO","DATA_ANALYSIS","ADMIN"
)

@Composable
fun AdminAppEditorPanel(onBack: () -> Unit) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var orderText by remember { mutableStateOf(EDITOR_WORKSPACES.joinToString(",")) }
    var disabledText by remember { mutableStateOf("") }
    var themeMode by remember { mutableStateOf("system") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { api.publicAppConfig() }.onSuccess { cfg ->
            val order = cfg.optJSONArray("workspaceOrder")
            if (order != null) orderText = List(order.length()) { order.optString(it) }.joinToString(",")
            val enabled = cfg.optJSONObject("workspaceEnabled")
            if (enabled != null) {
                disabledText = EDITOR_WORKSPACES.filter { !enabled.optBoolean(it, true) }.joinToString(",")
            }
            themeMode = cfg.optString("themeMode", "system")
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("App Editor & Publish", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onBack, enabled = !busy) { Text("Back") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Live navigation layout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Change the order and visibility of the main workspaces. Saving publishes the configuration to the server so the app can pick it up without changing the APK.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        orderText, { orderText = it },
                        label = { Text("Workspace order (comma-separated IDs)") },
                        minLines = 4, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        disabledText, { disabledText = it },
                        label = { Text("Hidden workspace IDs (comma-separated)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        themeMode, { themeMode = it.lowercase() },
                        label = { Text("Theme mode: system / light / dark") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text("Valid IDs: " + EDITOR_WORKSPACES.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, onClick = {
                        busy = true
                        status = null
                        scope.launch {
                            val order = orderText.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }
                            val disabled = disabledText.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
                            val enabled = JSONObject()
                            EDITOR_WORKSPACES.forEach { enabled.put(it, it !in disabled) }
                            runCatching { api.setAdminAppLayout(order, enabled, themeMode) }
                                .onSuccess { status = "Published. Connected app clients will use the new navigation configuration." }
                                .onFailure { status = it.message ?: "Publish failed." }
                            busy = false
                        }
                    }) { Text(if (busy) "Publishing…" else "Publish app configuration") }
                }
            }
        }
        status?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.primary) } }
    }
}
