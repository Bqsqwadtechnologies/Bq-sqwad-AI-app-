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
fun HistoryScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var selected by remember { mutableStateOf<Pair<HistoryItem, List<HistoryMessage>>?>(null) }
    var busy by remember { mutableStateOf(true) }
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            if (!busy && items.isEmpty() && error == null) {
                Text("No saved chats yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item ->
                    ElevatedCard(onClick = {
                        scope.launch {
                            runCatching { HistoryApiClient().load(item.id) }
                                .onSuccess { selected = item to it }
                                .onFailure { error = it.message ?: "Could not open this chat." }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text("${item.messageCount} messages", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { HistoryApiClient().delete(item.id) }
                                        .onSuccess { refresh() }
                                        .onFailure { error = it.message ?: "Could not delete this chat." }
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
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
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }
        )
    }
}
