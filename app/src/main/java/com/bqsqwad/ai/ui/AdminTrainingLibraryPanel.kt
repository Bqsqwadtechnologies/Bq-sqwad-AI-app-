package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AdminTrainingLibraryPanel(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var userCount by remember { mutableIntStateOf(0) }
    var itemCount by remember { mutableIntStateOf(0) }
    var refreshNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshNonce) {
        loading = true
        error = null
        runCatching { WorkspaceApiClient().adminTrainingLibrary() }
            .onSuccess { body ->
                val array = body.optJSONArray("items") ?: JSONArray()
                rows = List(array.length()) { index -> array.optJSONObject(index) ?: JSONObject() }
                userCount = body.optInt("userCount", 0)
                itemCount = body.optInt("itemCount", rows.size)
            }
            .onFailure { error = safeUserFacingIssue(it, "The opt-in training library could not be loaded.") }
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Opt-in AI Training Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Review eligible text contributions from users who explicitly enabled AI Training & Data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$itemCount eligible examples", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("$userCount opted-in accounts with contribution records", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("This is a reviewable dataset, not automatic model-weight training. Private chats, attachments and live camera/audio are excluded. Turning consent off removes that account's stored contributions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Button(onClick = { refreshNonce++ }, enabled = !loading) { Text(if (loading) "Refreshing…" else "Refresh library") }
                }
            }
        }
        if (loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { BqSqwadLoadingIndicator(Modifier.size(48.dp)) } }
        error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error) } }
        if (!loading && rows.isEmpty() && error == null) item {
            Text("No eligible training examples are available yet. The library fills only when users opt in and send eligible text-only chats.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(rows, key = { it.optString("id") + ":" + it.optString("createdAt") }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(row.optString("workspace").ifBlank { "main_chat" }.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Contributor · " + row.optString("owner", "opt-in account"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Prompt", fontWeight = FontWeight.SemiBold)
                    Text(row.optString("prompt").ifBlank { "(empty)" })
                    Text("Response", fontWeight = FontWeight.SemiBold)
                    Text(row.optString("response").ifBlank { "(empty)" })
                    Text(row.optString("createdAt"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
