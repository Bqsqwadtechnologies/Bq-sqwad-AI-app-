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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.storage.FirebaseStorage
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
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
    var authVideoUrl by remember { mutableStateOf("") }
    var authVideoEnabled by remember { mutableStateOf(false) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching {
                val ref = FirebaseStorage.getInstance().reference.child("auth-background/" + System.currentTimeMillis() + ".mp4")
                kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                    ref.putFile(uri).addOnSuccessListener {
                        ref.downloadUrl.addOnSuccessListener { url -> if (cont.isActive) cont.resume(url.toString()) }
                            .addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
                    }.addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
                }
            }.onSuccess { url ->
                authVideoUrl = url
                runCatching { api.setAdminAuthBackgroundVideo(url, true) }
                    .onSuccess { authVideoEnabled = true; status = "Sign-in background video published." }
                    .onFailure { status = it.message }
            }.onFailure { status = it.message }
            busy = false
        }
    }

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
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sign-in background video", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Optional branded video behind the sign-in form. The sign-in controls remain in the foreground.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, onClick = { videoPicker.launch("video/*") }) { Text(if (busy) "Uploading…" else "Upload / replace video") }
                    OutlinedTextField(authVideoUrl, { authVideoUrl = it }, label = { Text("Published video URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && authVideoUrl.startsWith("https://"), onClick = {
                            busy = true
                            scope.launch {
                                runCatching { api.setAdminAuthBackgroundVideo(authVideoUrl.trim(), true) }
                                    .onSuccess { authVideoEnabled = true; status = "Sign-in video enabled." }
                                    .onFailure { status = it.message }
                                busy = false
                            }
                        }) { Text("Publish URL") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                runCatching { api.setAdminAuthBackgroundVideo("", false) }
                                    .onSuccess { authVideoEnabled = false; status = "Sign-in video disabled." }
                                    .onFailure { status = it.message }
                                busy = false
                            }
                        }) { Text("Disable") }
                    }
                    Text(if (authVideoEnabled) "Status: enabled" else "Status: disabled", style = MaterialTheme.typography.labelMedium)
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
