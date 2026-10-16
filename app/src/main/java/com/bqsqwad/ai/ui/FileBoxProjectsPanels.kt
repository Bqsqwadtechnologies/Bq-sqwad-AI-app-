package com.bqsqwad.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import android.provider.OpenableColumns
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun FileBoxPanel(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = remember { WorkspaceApiClient() }
    val rows = remember { mutableStateListOf<JSONObject>() }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var retention by remember { mutableStateOf(30) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    val importAnyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }?.take(160)?.ifBlank { "upload" } ?: "upload"
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 25 * 1024 * 1024) error("Files must be 25 MB or smaller.")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("Could not read the selected file.")
                    }
                    api.uploadFileBoxAsset(name, name, mime, bytes)
                }.onSuccess {
                    notice = "File uploaded to your File Box. It will expire after $retention days."
                    refresh()
                }.onFailure { notice = it.message?.take(180) ?: "File upload failed. Please try again." }
                busy = false
            }
        }
    }
    val importTextFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                var wasTruncated = false
                runCatching {
                    val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                            val buffer = CharArray(4096)
                            val output = StringBuilder()
                            while (output.length <= 25000) {
                                val count = reader.read(buffer, 0, minOf(buffer.size, 25001 - output.length))
                                if (count <= 0) break
                                output.append(buffer, 0, count)
                            }
                            output.toString()
                        } ?: error("Could not open the selected file.")
                    }
                    wasTruncated = imported.length > 25000
                    val safeContent = imported.take(25000)
                    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }?.take(180)?.ifBlank { "Imported text" } ?: "Imported text"
                    api.saveFileBoxItem(displayName, safeContent, context.contentResolver.getType(uri) ?: "text/plain")
                }.onSuccess {
                    notice = if (wasTruncated) "Text file imported. Content was capped at 25,000 characters." else "Text file imported into File Box."
                    refresh()
                }.onFailure { notice = "Import failed. Choose a readable text file and try again." }
                busy = false
            }
        }
    }

    fun refresh() {
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
            Text("Store uploaded pictures, documents and text files in your account, then return here to preview or download them when needed. Files expire according to your retention period (7, 14, 30 or 90 days).", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
            OutlinedTextField(value = title, onValueChange = { title = it.take(180) }, modifier = Modifier.fillMaxWidth(), label = { Text("Document name") }, singleLine = true)
            OutlinedTextField(value = content, onValueChange = { content = it.take(25000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Text content") }, minLines = 3)
            Button(enabled = !busy, onClick = { importAnyFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Upload file or picture") }
            OutlinedButton(enabled = !busy, onClick = { importTextFile.launch(arrayOf("text/*", "application/json", "application/xml")) }, modifier = Modifier.fillMaxWidth()) { Text("Import text file") }
            Button(enabled = !busy && title.isNotBlank() && content.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    runCatching { api.saveFileBoxItem(title.trim(), content) }
                        .onSuccess { title = ""; content = ""; notice = "Document saved. Expiry follows the selected retention period."; refresh() }
                        .onFailure { notice = "Could not save this document. It may exceed the supported text-file limit." }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Save to File Box") }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            if (busy) CircularProgressIndicator()
            Text("Saved documents", style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty() && !busy) Text("No saved files yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (busy) CircularProgressIndicator()
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
