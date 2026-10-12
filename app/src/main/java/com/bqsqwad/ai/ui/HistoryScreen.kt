@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.HistoryApiClient
import com.bqsqwad.ai.core.HistoryItem
import com.bqsqwad.ai.core.HistoryMessage
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onContinue: (HistoryItem, List<HistoryMessage>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var selected by remember { mutableStateOf<Pair<HistoryItem, List<HistoryMessage>>?>(null) }
    var busy by remember { mutableStateOf(true) }
    var loadingItemId by remember { mutableStateOf<String?>(null) }
    var deletingItem by remember { mutableStateOf<HistoryItem?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        busy = true
        error = null
        scope.launch {
            runCatching { HistoryApiClient().list() }
                .onSuccess { items = it }
                .onFailure { error = it.message ?: "Could not load history." }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item ->
                    ElevatedCard(onClick = {
                        if (loadingItemId != null || deletingId != null) return@ElevatedCard
                        loadingItemId = item.id
                        error = null
                        scope.launch {
                            runCatching { HistoryApiClient().load(item.id) }
                                .onSuccess { selected = item to it }
                                .onFailure { error = it.message ?: "Could not open this chat." }
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
                                if (loadingItemId == item.id) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
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
                            .onFailure { error = it.message ?: "Could not delete this chat." }
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
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.id }) { message ->
                        SurfaceCard(if (message.mine) "You" else "BQ Sqwad AI", message.text)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onContinue(item, messages)
                    selected = null
                }) { Text("Continue in Chat") }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            }
        )
    }
}
