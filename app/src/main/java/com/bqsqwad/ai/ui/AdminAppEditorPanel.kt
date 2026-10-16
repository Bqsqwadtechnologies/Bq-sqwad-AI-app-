package com.bqsqwad.ai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private val EDITOR_WORKSPACES = listOf(
    "CHAT","VIDEO","SEARCH","TRUTH_GUARD","FRONTIER_LAB","CAMERA","VOICE","PERSONAL","LIFE",
    "COMPANION","LEARNING","BUSINESS","ORGANIZATION_STUDIO","DATA_ANALYSIS","ADMIN"
)

@Composable
fun AdminAppEditorPanel(onBack: () -> Unit) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var orderText by remember { mutableStateOf(EDITOR_WORKSPACES.joinToString(",")) }
    var disabledText by remember { mutableStateOf("") }
    var themeMode by remember { mutableStateOf("system") }
    var authVideoUrl by remember { mutableStateOf("") }
    var authVideoEnabled by remember { mutableStateOf(false) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedVideoUri = uri
        if (uri != null) status = "Video selected. Upload it to publish the sign-in background."
    }

    LaunchedEffect(Unit) {
        runCatching { api.publicAppConfig() }.onSuccess { cfg ->
            val order = cfg.optJSONArray("workspaceOrder")
            if (order != null) orderText = List(order.length()) { order.optString(it) }.joinToString(",")
            val enabled = cfg.optJSONObject("workspaceEnabled")
            if (enabled != null) disabledText = EDITOR_WORKSPACES.filter { !enabled.optBoolean(it, true) }.joinToString(",")
            themeMode = cfg.optString("themeMode", "system")
            authVideoUrl = cfg.optString("authBackgroundVideoUrl").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            authVideoEnabled = cfg.optBoolean("authBackgroundVideoEnabled", false)
        }
    }

    fun publishVideoSettings(enabled: Boolean) {
        if (enabled && !authVideoUrl.trim().startsWith("https://", ignoreCase = true)) {
            status = "Choose and upload a video or enter a direct HTTPS video URL first."
            return
        }
        busy = true
        status = null
        scope.launch {
            runCatching { api.setAdminAuthBackgroundVideo(authVideoUrl.trim(), enabled) }
                .onSuccess {
                    authVideoEnabled = enabled
                    status = if (enabled) "Sign-in and sign-up background video published." else "Sign-in and sign-up background video disabled."
                }
                .onFailure { status = safeUserFacingIssue(it, "Background video settings could not be saved.") }
            busy = false
        }
    }

    fun uploadSelectedVideo() {
        val uri = selectedVideoUri
        if (uri == null || busy) return
        val contentType = context.contentResolver.getType(uri).orEmpty().ifBlank { "video/mp4" }
        if (!contentType.startsWith("video/", ignoreCase = true)) {
            status = "Choose a video file, such as MP4 or WebM."
            return
        }
        val knownSize = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
        if (knownSize > 250L * 1024L * 1024L) {
            status = "Choose a video smaller than 250 MB."
            return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            status = "Sign in with the authorized admin account before uploading."
            return
        }
        busy = true
        status = "Uploading video securely…"
        scope.launch {
            var uploadedUrl: String? = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val objectRef = FirebaseStorage.getInstance().reference
                        .child("auth-background/" + uid + "/" + UUID.randomUUID().toString())
                    val metadata = StorageMetadata.Builder().setContentType(contentType).build()
                    Tasks.await(objectRef.putFile(uri, metadata))
                    Tasks.await(objectRef.downloadUrl).toString()
                }
            }.onSuccess { url ->
                uploadedUrl = url
                authVideoUrl = url
                runCatching { api.setAdminAuthBackgroundVideo(url, true) }
                    .onSuccess {
                        authVideoEnabled = true
                        selectedVideoUri = null
                        status = "Video uploaded and published. Sign-in and sign-up now use it behind the form."
                    }
                    .onFailure {
                        status = "Video uploaded, but publishing failed. The URL is retained below; tap Save video settings to retry."
                    }
            }.onFailure {
                status = safeUserFacingIssue(it, "Video upload failed. Confirm admin Storage access and try again.")
            }
            if (uploadedUrl != null && authVideoEnabled) {
                // Keep the published URL in the form so the administrator can reuse or disable it.
                authVideoUrl = uploadedUrl.orEmpty()
            }
            busy = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("App Editor & Publish", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onBack, enabled = !busy) { Text("Back") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Live navigation layout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Change the order and visibility of the main workspaces. Saving publishes the configuration to the server so connected app clients can use it.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(orderText, { orderText = it }, label = { Text("Workspace order (comma-separated IDs)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(disabledText, { disabledText = it }, label = { Text("Hidden workspace IDs (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(themeMode, { themeMode = it.lowercase() }, label = { Text("Theme mode: system / light / dark") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                                .onFailure { status = safeUserFacingIssue(it, "App layout could not be published.") }
                            busy = false
                        }
                    }) { Text(if (busy) "Publishing…" else "Publish app configuration") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sign-in & Sign-up Background Video", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Upload a video to the admin-only Firebase Storage path or publish a direct HTTPS video URL. The authentication form remains interactive over the translucent background. Users can pause/play and mute/unmute the video on the sign-in and sign-up screens.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { videoPicker.launch("video/*") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Choose video") }
                        Button(onClick = ::uploadSelectedVideo, enabled = !busy && selectedVideoUri != null, modifier = Modifier.weight(1f)) { Text(if (busy) "Working…" else "Upload & publish") }
                    }
                    selectedVideoUri?.let { Text("Selected: " + (it.lastPathSegment ?: "video file"), style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(
                        value = authVideoUrl,
                        onValueChange = { authVideoUrl = it },
                        label = { Text("Published video URL (HTTPS)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Show video on authentication screens", fontWeight = FontWeight.SemiBold)
                            Text(if (authVideoEnabled) "Enabled" else "Disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = authVideoEnabled, enabled = !busy, onCheckedChange = { authVideoEnabled = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { publishVideoSettings(authVideoEnabled) },
                            enabled = !busy && (!authVideoEnabled || authVideoUrl.startsWith("https://", ignoreCase = true)),
                            modifier = Modifier.weight(1f)
                        ) { Text(if (authVideoEnabled) "Save & enable video" else "Save & disable video") }
                        OutlinedButton(onClick = { publishVideoSettings(false) }, enabled = !busy && authVideoEnabled, modifier = Modifier.weight(1f)) { Text("Disable video") }
                    }
                }
            }
        }
        status?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.primary) } }
    }
}
