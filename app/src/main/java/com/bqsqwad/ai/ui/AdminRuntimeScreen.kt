package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.AdminRuntimeClient
import com.bqsqwad.ai.core.AdminRuntimeRecord
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AdminRuntimeScreen(onBack: () -> Unit) {
    val client = remember { AdminRuntimeClient() }
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("agents") }
    var records by remember { mutableStateOf<List<AdminRuntimeRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var capability by remember { mutableStateOf("chat") }

    fun refresh() {
        loading = true
        scope.launch {
            runCatching { client.list(type) }
                .onSuccess { records = it; error = null }
                .onFailure { error = it.message ?: "Could not load runtime records." }
            loading = false
        }
    }

    LaunchedEffect(type) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin AI Runtime") },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹") } }
            )
        }
    ) { p ->
        LazyColumn(
            Modifier.padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Admin-only agents and runtime controls", style = MaterialTheme.typography.headlineSmall)
                Text("Agents, workflows, evaluations, plugins and jobs are server-authorized for the administrator only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("agents","workflows","evaluations","plugins","jobs").forEach { value ->
                        FilterChip(selected = type == value, onClick = { type = value }, label = { Text(value.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Create " + type.removeSuffix("s"), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                        if (type == "agents") {
                            OutlinedTextField(capability, { capability = it }, label = { Text("AI capability") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(systemPrompt, { systemPrompt = it }, label = { Text("System prompt / instructions") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        }
                        Button(
                            enabled = name.isNotBlank() && !loading,
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        client.create(
                                            type, name.trim(), description.trim(),
                                            JSONObject().apply {
                                                if (type == "agents") {
                                                    put("capability", capability.trim().ifBlank { "chat" })
                                                    put("systemPrompt", systemPrompt.trim())
                                                }
                                            }
                                        )
                                    }.onSuccess {
                                        name = ""; description = ""; systemPrompt = ""; refresh()
                                    }.onFailure { error = it.message ?: "Create failed." }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Create") }
                    }
                }
            }
            error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error) } }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(records, key = { it.id }) { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.name.ifBlank { record.id }, style = MaterialTheme.typography.titleMedium)
                        Text(record.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Status: " + record.status + " • Version: " + record.version, style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (type == "agents") {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching { client.update(type, record.id, "active", record.config) }
                                            .onSuccess { refresh() }
                                            .onFailure { error = it.message ?: "Activation failed." }
                                    }
                                }) { Text("Activate") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching { client.update(type, record.id, "paused", record.config) }
                                            .onSuccess { refresh() }
                                            .onFailure { error = it.message ?: "Pause failed." }
                                    }
                                }) { Text("Pause") }
                            }
                        }
                    }
                }
            }
        }
    }
}
