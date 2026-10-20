package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import com.bqsqwad.ai.core.ChatAttachmentReader
import com.bqsqwad.ai.core.PreparedChatAttachment
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileBoxPanel(onDismiss: () -> Unit, onUseInChat: (PreparedChatAttachment) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = remember { WorkspaceApiClient() }
    val rows = remember { mutableStateListOf<JSONObject>() }
    var retention by remember { mutableStateOf(30) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var refresh: () -> Unit = {}
    refresh = {
        busy = true
        scope.launch {
            runCatching { api.fileBox() }.onSuccess { body ->
                rows.clear()
                val arr = body.optJSONArray("items")
                if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { rows.add(it) }
                retention = body.optInt("retentionDays", 30).let { if (it in listOf(7,14,30,90)) it else 30 }
                notice = null
            }.onFailure { notice = "File Box could not be loaded. Check your connection and retry." }
            busy = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("File Box", style = MaterialTheme.typography.headlineSmall)
            Text("Reuse chat attachments saved to your account File Box. Files remain available across signed-in devices until their retention period ends (7, 14, 30 or 90 days).", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(7,14,30,90).forEach { days ->
                    FilterChip(selected = retention == days, onClick = {
                        scope.launch {
                            busy = true
                            runCatching { api.setFileBoxRetentionDays(days) }
                                .onSuccess { retention = days; notice = "Retention set to $days days."; refresh() }
                                .onFailure { notice = "Could not save the retention period." }
                            busy = false
                        }
                    }, label = { Text("$days d") })
                }
            }
            OutlinedButton(
                enabled = true,
                onClick = { notice = "Direct File Box uploads are temporarily unavailable. Attach a file in chat to save it here for reuse." },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Uploads temporarily unavailable") }
            Text(
                "Files attached in chat are intended to be saved to your account File Box and shared across your signed-in devices.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            if (busy) BqSqwadLoadingIndicator(Modifier.size(42.dp))
            Text("Stored files & pictures", style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty() && !busy) Text("No files or pictures stored yet. Attach a file in chat to add it to your File Box for later reuse.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(row.optString("title", "Untitled"), style = MaterialTheme.typography.titleSmall)
                         Text((row.optString("fileName").takeIf { it.isNotBlank() } ?: row.optString("mimeType", "Text document")) + " · " + row.optLong("size", row.optString("content").length.toLong()) + " bytes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Expires: " + row.optString("expiresAt", "not available"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (row.optString("objectPath").isNotBlank()) {
                                 TextButton(enabled = !busy, onClick = {
                                     busy = true
                                     scope.launch {
                                         runCatching {
                                             val bytes = api.downloadFileBoxAsset(row.optString("id"))
                                             ChatAttachmentReader.readBytes(
                                                 row.optString("fileName", row.optString("title", "attachment")),
                                                 row.optString("mimeType", "application/octet-stream"),
                                                 bytes
                                             )
                                         }.onSuccess { attachment ->
                                             onUseInChat(attachment)
                                             notice = "Added to the new chat composer."
                                         }.onFailure { notice = it.message?.take(180) ?: "This file could not be attached to chat." }
                                         busy = false
                                     }
                                 }) { Text("Use in New Chat") }
                                 TextButton(enabled = !busy, onClick = {
                                     busy = true
                                     scope.launch {
                                         runCatching { api.downloadFileBoxAsset(row.optString("id")) }
                                             .onSuccess { bytes ->
                                                 runCatching {
                                                     val fileName = row.optString("fileName", row.optString("title", "download")).replace(Regex("[/\\\\]"), "_")
                                                     val values = ContentValues().apply {
                                                         put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                                         put(MediaStore.MediaColumns.MIME_TYPE, row.optString("mimeType", "application/octet-stream"))
                                                         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BQ Sqwad AI")
                                                     }
                                                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                         val destination = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create a Downloads file.")
                                                         context.contentResolver.openOutputStream(destination)?.use { it.write(bytes) } ?: error("Could not write the downloaded file.")
                                                     } else {
                                                         val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BQ Sqwad AI").apply { mkdirs() }
                                                         File(folder, fileName).writeBytes(bytes)
                                                     }
                                                     notice = "Saved to Downloads/BQ Sqwad AI."
                                                 }.onFailure { notice = it.message ?: "Could not save the download." }
                                             }
                                             .onFailure { notice = it.message ?: "Download failed." }
                                         busy = false
                                     }
                                 }) { Text("Download") }
                             } else {
                                 TextButton(enabled = !busy, onClick = {
                                     val name = row.optString("fileName", row.optString("title", "Saved text"))
                                     val mime = row.optString("mimeType", "text/plain")
                                     onUseInChat(PreparedChatAttachment(name, mime, text = row.optString("content")))
                                     onDismiss()
                                 }) { Text("Use in New Chat") }
                                 TextButton(onClick = { preview = row }) { Text("View") }
                             }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { api.deleteFileBoxItem(row.optString("id")) }
                                        .onSuccess { notice = "Document deleted."; refresh() }
                                        .onFailure { notice = "Could not delete this document." }
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    preview?.let { row ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(row.optString("title", "Document")) },
            text = { Text(row.optString("content", "No text content available.")) },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Export to Downloads", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("txt", "pdf", "docx").forEach { format ->
                            TextButton(onClick = {
                                runCatching {
                                    NativeDocumentExporter.export(context, row.optString("title", "Document"), row.optString("content", ""), format)
                                }.onSuccess { notice = it }.onFailure { notice = "Export failed. Please try again." }
                                preview = null
                            }) { Text(format.uppercase()) }
                        }
                    }
                    TextButton(onClick = { preview = null }) { Text("Close") }
                }
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectsPanel(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember { WorkspaceApiClient() }
    val rows = remember { mutableStateListOf<JSONObject>() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        busy = true
        scope.launch {
            runCatching { api.projects() }.onSuccess { body ->
                rows.clear()
                val arr = body.optJSONArray("items")
                if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { rows.add(it) }
                notice = null
            }.onFailure { notice = "Projects could not be loaded. Check your connection and retry." }
            busy = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Projects", style = MaterialTheme.typography.headlineSmall)
            Text("Create project notes and keep a working draft synced to your account.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = title, onValueChange = { title = it.take(160) }, modifier = Modifier.fillMaxWidth(), label = { Text("Project title") }, singleLine = true)
            OutlinedTextField(value = description, onValueChange = { description = it.take(1500) }, modifier = Modifier.fillMaxWidth(), label = { Text("Description") }, minLines = 2)
            OutlinedTextField(value = content, onValueChange = { content = it.take(5000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Working notes") }, minLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && title.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        runCatching { api.saveProject(selectedId, title.trim(), description, content) }
                            .onSuccess { selectedId = null; title = ""; description = ""; content = ""; notice = "Project saved."; refresh() }
                            .onFailure { notice = "Could not save this project." }
                        busy = false
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (selectedId == null) "Create project" else "Save changes") }
                OutlinedButton(onClick = { selectedId = null; title = ""; description = ""; content = "" }) { Text("Clear") }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            if (busy) BqSqwadLoadingIndicator(Modifier.size(42.dp))
            Text("Your projects", style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty() && !busy) Text("No projects yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(row.optString("title", "Untitled project"), style = MaterialTheme.typography.titleSmall)
                        Text(row.optString("description"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                selectedId = row.optString("id")
                                title = row.optString("title")
                                description = row.optString("description")
                                content = row.optString("content")
                            }) { Text("Edit") }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { api.deleteProject(row.optString("id")) }
                                        .onSuccess { notice = "Project deleted."; refresh() }
                                        .onFailure { notice = "Could not delete this project." }
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
