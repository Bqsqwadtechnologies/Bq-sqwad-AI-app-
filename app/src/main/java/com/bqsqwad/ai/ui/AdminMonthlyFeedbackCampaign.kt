package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AdminMonthlyFeedbackCampaign() {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var enabled by remember { mutableStateOf(false) }
    var receiverUid by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("Monthly feedback") }
    var message by remember { mutableStateOf("Tell us how BQ Sqwad AI worked for you this month.") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        runCatching { api.adminMonthlyFeedback() }
            .onSuccess {
                data = it
                enabled = it.optBoolean("enabled")
                receiverUid = it.optString("receiverUid")
                title = it.optString("campaignTitle", "Monthly feedback")
                message = it.optString("campaignMessage", "Tell us how BQ Sqwad AI worked for you this month.")
            }
            .onFailure { error = "Monthly feedback campaign could not be loaded. Verify admin access and retry." }
        loading = false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Monthly feedback campaign", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Configure the same campaign used by the website. Submissions are assigned to one verified administrator.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (enabled) "Campaign enabled" else "Campaign disabled")
                Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !loading && !saving)
            }
            OutlinedTextField(value = title, onValueChange = { title = it.take(100) }, modifier = Modifier.fillMaxWidth(), label = { Text("Campaign title") }, singleLine = true)
            OutlinedTextField(value = message, onValueChange = { message = it.take(500) }, modifier = Modifier.fillMaxWidth(), label = { Text("Campaign message") }, minLines = 2, maxLines = 4)
            OutlinedTextField(value = receiverUid, onValueChange = { receiverUid = it.trim().take(160) }, modifier = Modifier.fillMaxWidth(), label = { Text("Receiving administrator Firebase UID") }, singleLine = true)
            Text("The server checks that the receiver is an administrator before saving or delivering feedback.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !loading && !saving, onClick = {
                    saving = true
                    error = null
                    notice = null
                    scope.launch {
                        runCatching { api.saveAdminMonthlyFeedback(enabled, receiverUid, title, message) }
                            .onSuccess { notice = "Campaign settings saved."; refresh++ }
                            .onFailure { error = it.message ?: "Campaign settings could not be saved." }
                        saving = false
                    }
                }) { Text(if (saving) "Saving…" else "Save campaign") }
                OutlinedButton(enabled = !loading && !saving, onClick = { refresh++ }) { Text("Refresh") }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Latest assigned submissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val rows = data?.optJSONArray("submissions")
            if (rows == null || rows.length() == 0) {
                Text("No monthly survey submissions assigned to this administrator yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                (0 until minOf(rows.length(), 20)).forEach { index ->
                    val row = rows.optJSONObject(index) ?: JSONObject()
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${row.optString("period")} · ${row.optInt("rating")}/5 · ${row.optString("category")}", fontWeight = FontWeight.SemiBold)
                        if (row.optString("comment").isNotBlank()) Text(row.optString("comment"))
                        Text("${row.optString("email")} · ${row.optString("status")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
