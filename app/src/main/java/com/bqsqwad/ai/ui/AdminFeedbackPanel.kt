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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AdminFeedbackPanel(onBack: () -> Unit) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        runCatching { api.adminFeedback() }
            .onSuccess { body = it }
            .onFailure { error = "Feedback could not be loaded. Verify admin access and retry." }
        loading = false
    }

    val feedbackRows = body?.optJSONArray("items")
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Admin Inbox · Messages & Feedback", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
                Text(
                    "Contact sales, support requests, complaints, and thumbs-up/down feedback from the app and website.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Helpful", body?.optInt("helpfulCount") ?: 0, Modifier.weight(1f))
                MetricCard("Not helpful", body?.optInt("notHelpfulCount") ?: 0, Modifier.weight(1f))
                MetricCard("Complaints", body?.optInt("complaintCount") ?: 0, Modifier.weight(1f))
            }
        }
        item {
            Button(onClick = { refresh++ }, enabled = !loading) { Text("Refresh feedback") }
            if (loading) BqSqwadLoadingIndicator(Modifier.padding(12.dp).size(48.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!loading && error == null && (feedbackRows == null || feedbackRows.length() == 0)) {
                Text("No feedback or reports have been submitted yet.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (feedbackRows != null) {
            items((0 until feedbackRows.length()).map { feedbackRows.getJSONObject(it) }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.optString("category", "general"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Status: " + row.optString("status", "new") + " · Source: " + row.optString("source", "app feedback"), style = MaterialTheme.typography.bodySmall)
                        row.optString("email").takeIf { it.isNotBlank() && it != "null" }?.let { Text("Contact: $it") }
                        Text("Sentiment: " + row.optString("sentiment", "not provided"))
                        row.optString("reason").takeIf { it.isNotBlank() && it != "null" }?.let { Text("Reason: $it") }
                        row.optString("comment").takeIf { it.isNotBlank() && it != "null" }?.let { Text("Comment: $it") }
                        Text("User ID: " + row.optString("userId", "unknown"), style = MaterialTheme.typography.bodySmall)
                        Text("Message reference: " + row.optString("messageId", "not supplied"), style = MaterialTheme.typography.bodySmall)
                        Text("Submitted: " + row.optString("createdAt", "unknown"), style = MaterialTheme.typography.bodySmall)
                        Text("Privacy: message content is not included; only a one-way message hash is stored.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, count: Int, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
