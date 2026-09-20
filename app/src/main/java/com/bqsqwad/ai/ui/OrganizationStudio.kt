package com.bqsqwad.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.ChatApiClient
import kotlinx.coroutines.launch

data class ProductionSource(val id: Int, val name: String, val uri: Uri)

@Composable
fun OrganizationStudioWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var outputStatus by remember { mutableStateOf("Program output idle") }
    val sources = remember { mutableStateListOf<ProductionSource>() }
    var selectedSourceId by remember { mutableStateOf<Int?>(null) }
    var nextId by remember { mutableStateOf(1) }
    var directorPrompt by remember { mutableStateOf("") }
    var directorResult by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            sources.add(ProductionSource(nextId, "Media source $nextId", uri))
            if (selectedSourceId == null) selectedSourceId = nextId
            nextId += 1
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(4.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Organization Studio AI", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Professional live-production control for organization events: sources, scenes, AI direction, captions and broadcast orchestration.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(onClick = {}, label = { Text("Production") })
                        SuggestionChip(onClick = {}, label = { Text("AI Director") })
                        SuggestionChip(onClick = {}, label = { Text("Broadcast") })
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sources & cameras", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Import a real media source now. Network camera/phone pairing and multi-platform broadcast are routed through the native backend integration when its provider endpoints are configured.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { picker.launch(arrayOf("video/*", "audio/*")) }) {
                        Icon(Icons.Default.Add, null)
                        Text(" Add media source")
                    }
                    if (sources.isEmpty()) Text("No production sources connected.")
                }
            }
        }
        items(sources, key = { it.id }) { source ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text(source.uri.lastPathSegment ?: source.uri.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (source.id == selectedSourceId) "Selected program source" else "Tap Select to make program source", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { selectedSourceId = source.id }) { Text("Select") }
                    IconButton(onClick = { sources.remove(source); if (selectedSourceId == source.id) selectedSourceId = sources.firstOrNull()?.id }) {
                        Icon(Icons.Default.Delete, "Remove source")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Production control", style = MaterialTheme.typography.titleLarge)
                    Text(outputStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val source = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()
                            if (source == null) outputStatus = "Add a source first"
                            else runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(source.uri, context.contentResolver.getType(source.uri) ?: "video/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                                outputStatus = "Program preview opened"
                            }.onFailure { outputStatus = "No compatible player is available" }
                        }, enabled = sources.isNotEmpty()) {
                            Icon(Icons.Default.PlayArrow, null)
                            Text(" Preview program")
                        }
                        Button(onClick = {
                            val source = sources.firstOrNull()
                            if (source == null) outputStatus = "Add a source first"
                            else runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(source.uri, context.contentResolver.getType(source.uri) ?: "video/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                                outputStatus = "Display handoff opened; choose an available display/player"
                            }.onFailure { outputStatus = "No compatible display route is available" }
                        }, enabled = sources.isNotEmpty()) {
                            Icon(Icons.Default.Cast, null)
                            Text(" TV / display")
                        }
                    }
                    Text(
                        "Broadcast destinations, credentials and platform adapters remain server/admin controlled; credentials are never stored in the APK.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AI production director", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = directorPrompt,
                        onValueChange = { directorPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Direct the production") },
                        placeholder = { Text("Example: prepare a two-camera interview scene and draft live captions.") }
                    )
                    Button(
                        enabled = directorPrompt.isNotBlank() && !busy,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    directorResult = ChatApiClient().sendMessage(
                                        "Organization Studio AI production director request:\n$directorPrompt"
                                    , capability = "organization_studio").text
                                } catch (t: Throwable) {
                                    error = t.message ?: "AI director request failed."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) {
                        Text(if (busy) "AI is working…" else "Run AI director")
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (directorResult.isNotBlank()) Text("AI Director", style = MaterialTheme.typography.titleMedium)
                    Text(directorResult)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Integration readiness", style = MaterialTheme.typography.titleLarge)
                    Text("AI routing: native backend gateway (admin/provider controlled)")
                    Text("OpenAI / Gemini / other configured providers: routed by backend policy")
                    Text("Live camera network transport: requires configured signaling/stream endpoint")
                    Text("Multi-platform broadcast: requires configured platform adapter credentials")
                    Text("QR device pairing: requires configured pairing service")
                    Text("Live captions and post-broadcast intelligence: AI backend capability")
                }
            }
        }
    }
}
