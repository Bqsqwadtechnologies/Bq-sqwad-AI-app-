package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable
fun AdminOperationsPanel(onBack: () -> Unit) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var staffUid by remember { mutableStateOf("") }
    var staffRole by remember { mutableStateOf("moderator") }
    var staff by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reloadStaff() {
        scope.launch {
            runCatching {
                val arr = api.adminStaff().optJSONArray("staff") ?: org.json.JSONArray()
                List(arr.length()) { arr.getJSONObject(it) }
            }.onSuccess { staff = it }.onFailure { status = it.message }
        }
    }
    LaunchedEffect(Unit) { reloadStaff() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Admin Operations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Broadcast notification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Sends the same notification to all currently enabled app notification tokens.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && title.isNotBlank() && body.isNotBlank(), onClick = {
                        busy = true
                        scope.launch {
                            runCatching { api.broadcastAdminNotification(title.trim(), body.trim()) }
                                .onSuccess { status = "Notification sent: " + it.optInt("sent") + " devices." }
                                .onFailure { status = it.message }
                            busy = false
                        }
                    }) { Text(if (busy) "Sending…" else "Send to everyone") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Admin staff & roles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Add controlled admin-console roles without exposing provider secrets.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(staffUid, { staffUid = it }, label = { Text("Firebase UID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(staffRole, { staffRole = it }, label = { Text("Role: moderator/editor/customer_agent/designer/analyst/operations") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && staffUid.isNotBlank(), onClick = {
                        busy = true
                        scope.launch {
                            runCatching { api.setAdminStaff(staffUid.trim(), staffRole.trim()) }
                                .onSuccess { status = "Staff role saved."; staffUid = ""; reloadStaff() }
                                .onFailure { status = it.message }
                            busy = false
                        }
                    }) { Text("Add / update staff") }
                }
            }
        }
        item { Text("Current staff", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(staff) { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(item.optString("email").ifBlank { item.optString("uid") }, fontWeight = FontWeight.Bold)
                        Text(item.optString("role") + " • " + if (item.optBoolean("active", false)) "active" else "disabled")
                    }
                    OutlinedButton(onClick = {
                        scope.launch { runCatching { api.removeAdminStaff(item.optString("uid")) }.onSuccess { reloadStaff() }.onFailure { status = it.message } }
                    }) { Text("Disable") }
                }
            }
        }
        status?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.primary) } }
    }
}
