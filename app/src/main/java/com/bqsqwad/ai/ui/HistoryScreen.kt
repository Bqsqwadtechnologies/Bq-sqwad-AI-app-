@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bqsqwad.ai.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.HistoryApiClient
import com.bqsqwad.ai.core.HistoryItem
import com.bqsqwad.ai.core.HistoryMessage
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onContinue: (HistoryItem, List<HistoryMessage>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var historyLimit by remember { mutableStateOf(25) }
    var selected by remember { mutableStateOf<Pair<HistoryItem, List<HistoryMessage>>?>(null) }
    var busy by remember { mutableStateOf(true) }
    var loadingItemId by remember { mutableStateOf<String?>(null) }
    var deletingItem by remember { mutableStateOf<HistoryItem?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSecretVault by remember { mutableStateOf(false) }
    var secretSource by remember { mutableStateOf<Pair<HistoryItem, List<HistoryMessage>>?>(null) }

    fun refresh(limit: Int = historyLimit) {
        busy = true
        error = null
        scope.launch {
            runCatching { HistoryApiClient().list(limit.coerceIn(1, 100)) }
                .onSuccess { items = it }
                .onFailure { error = safeUserFacingIssue(it, "Could not load history.") }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { secretSource = null; showSecretVault = true }) { Text("Secret Chats") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            SurfaceCard("History privacy", "Saved chats preserve text, attachment names and generated-image links. Original files are not retained; reattach a file to analyze it again.")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { message ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { refresh() }, enabled = !busy) { Text("Try again") }
                }
            }
            if (!busy && items.isEmpty() && error == null) {
                Text("No saved chats yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item ->
                    ElevatedCard(onClick = {
                        if (loadingItemId != null || deletingId != null) return@ElevatedCard
                        loadingItemId = item.id
                        error = null
                        scope.launch {
                            runCatching { HistoryApiClient().load(item.id) }
                                .onSuccess { selected = item to it }
                                .onFailure { error = safeUserFacingIssue(it, "Could not open this chat.") }
                            loadingItemId = null
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text("${item.messageCount} messages", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                TextButton(onClick = { deletingItem = item }, enabled = deletingId == null && loadingItemId == null) { Text("Delete") }
                                if (loadingItemId == item.id) BqSqwadLoadingIndicator(Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            if (!busy && error == null && items.size >= historyLimit && historyLimit < 100) {
                OutlinedButton(
                    onClick = {
                        historyLimit = (historyLimit + 25).coerceAtMost(100)
                        refresh(historyLimit)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Load more history (" + items.size + " shown)") }
            }
        }
    }

    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { if (deletingId == null) deletingItem = null },
            title = { Text("Delete saved chat?") },
            text = { Text("‘${item.title}’ will be removed from your saved chat history. This cannot be undone.") },
            confirmButton = {
                TextButton(enabled = deletingId == null, onClick = {
                    deletingId = item.id
                    scope.launch {
                        runCatching { HistoryApiClient().delete(item.id) }
                            .onSuccess {
                                items = items.filterNot { it.id == item.id }
                                error = null
                            }
                            .onFailure { error = safeUserFacingIssue(it, "Could not delete this chat.") }
                        deletingId = null
                        deletingItem = null
                    }
                }) { Text(if (deletingId == item.id) "Deleting…" else "Delete") }
            },
            dismissButton = {
                TextButton(enabled = deletingId == null, onClick = { deletingItem = null }) { Text("Cancel") }
            }
        )
    }

    selected?.let { (item, messages) ->
        fun shareExport(format: String) {
            val content = when (format) {
                "json" -> JSONObject()
                    .put("title", item.title)
                    .put("exportedAt", System.currentTimeMillis())
                    .put("messages", JSONArray().apply {
                        messages.forEach { message ->
                            put(JSONObject()
                                .put("role", if (message.mine) "user" else "assistant")
                                .put("text", message.text)
                                .put("attachments", JSONArray(message.attachmentNames))
                                .put("imageUrl", message.imageUrl ?: JSONObject.NULL))
                        }
                    }).toString(2)
                "csv" -> buildString {
                    appendLine("role,text,attachments,image_url")
                    messages.forEach { message ->
                        fun csv(value: String) = "\\"\\" + value.replace("\\"\\"","\\"\\"\\"\\"").replace("\\r"," ").replace("\\n"," ") + "\\"\\""
                        appendLine(listOf(
                            if (message.mine) "user" else "assistant",
                            csv(message.text),
                            csv(message.attachmentNames.joinToString(" | ")),
                            csv(message.imageUrl.orEmpty())
                        ).joinToString(","))
                    }
                }
                else -> buildString {
                    appendLine(item.title)
                    appendLine("Exported: " + java.time.Instant.now().toString())
                    appendLine()
                    messages.forEach { message ->
                        appendLine((if (message.mine) "You" else "BQ Sqwad AI") + ":")
                        appendLine(message.text)
                        if (message.attachmentNames.isNotEmpty()) appendLine("Attachments: " + message.attachmentNames.joinToString(", "))
                        message.imageUrl?.takeIf { it.isNotBlank() }?.let { appendLine("Image: " + it) }
                        appendLine()
                    }
                }
            }
            val mime = if (format == "json") "application/json" else "text/plain"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_SUBJECT, item.title + " — BQ Sqwad AI conversation")
                putExtra(Intent.EXTRA_TEXT, content)
            }
            context.startActivity(Intent.createChooser(send, "Export conversation"))
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.id }) { message ->
                        SurfaceCard(if (message.mine) "You" else "BQ Sqwad AI", message.text)
                        if (message.attachmentNames.isNotEmpty()) {
                            Text(
                                "Attachments: " + message.attachmentNames.joinToString(", ") { it.substringAfter('|', it) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!message.imageUrl.isNullOrBlank()) {
                            Text("Generated image link is saved with this message.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { shareExport("txt") }) { Text("TXT") }
                    TextButton(onClick = { shareExport("json") }) { Text("JSON") }
                    TextButton(onClick = { shareExport("csv") }) { Text("CSV") }
                    TextButton(onClick = {
                    onContinue(item, messages)
                    selected = null
                    }) { Text("Continue in Chat") }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        secretSource = selected
                        selected = null
                        showSecretVault = true
                    }) { Text("Move to Secret Chat") }
                    TextButton(onClick = { selected = null }) { Text("Close") }
                }
            }
        )
    }

    SecretChatVaultDialog(
        open = showSecretVault,
        createFrom = secretSource,
        onCreateConsumed = {
            secretSource = null
            selected = null
            refresh()
        },
        onDismiss = {
            showSecretVault = false
            secretSource = null
        }
    )
}
