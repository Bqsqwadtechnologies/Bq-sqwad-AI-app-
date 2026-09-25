package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch

private data class CompanionMessage(val fromUser: Boolean, val text: String)

@Composable
fun CompanionWorkspace() {
    val scope = rememberCoroutineScope()
    var personality by remember { mutableStateOf("Caring") }
    var mode by remember { mutableStateOf("Chat") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var memoryEnabled by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<CompanionMessage>() }

    LaunchedEffect(Unit) {
        runCatching { WorkspaceApiClient().memory() }
            .onSuccess { memoryEnabled = it.optBoolean("enabled", true) }
            .onFailure { notice = it.message }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI Companion", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "A personal space for conversation, memory, voice and camera interactions.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Chat", "Voice", "Camera", "Memory").forEach { item ->
                        FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item) })
                    }
                }
            }
        }

        when (mode) {
            "Voice" -> {
                VoiceWorkspace()
                return@Column
            }
            "Camera" -> {
                CameraWorkspace()
                return@Column
            }
            "Memory" -> {
                SurfaceCard("Memory controls", "Memory is account-scoped and remains under your control.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Companion memory", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (memoryEnabled) "Enabled — you control it" else "Disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = memoryEnabled,
                        onCheckedChange = { enabled ->
                            memoryEnabled = enabled
                            scope.launch {
                                runCatching { WorkspaceApiClient().setMemoryEnabled(enabled) }
                                    .onFailure { notice = it.message ?: "Memory setting could not be saved." }
                            }
                        }
                    )
                }
                DeviceIntelligencePanel("AI Companion")
                return@Column
            }
        }

        Text("Personality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Caring", "Encouraging", "Calm").forEach { value ->
                FilterChip(selected = personality == value, onClick = { personality = value }, label = { Text(value) })
            }
        }

        SurfaceCard(
            "Memory",
            if (memoryEnabled) "Enabled — your companion can use permitted account memory." else "Disabled — the companion will not use saved memory."
        )

        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                Surface(
                    color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(message.text, Modifier.padding(12.dp))
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; notice = null },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Talk to your companion…") },
                maxLines = 4
            )
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    val text = input.trim()
                    input = ""
                    messages.add(CompanionMessage(true, text))
                    busy = true
                    notice = null
                    scope.launch {
                        runCatching {
                            WorkspaceApiClient().workspaceAi(
                                "companion",
                                text,
                                "You are BQ Sqwad AI Companion. Personality: $personality. Be supportive, respectful, and honest about limits. Do not claim to be human or create dependency."
                            )
                        }.onSuccess { messages.add(CompanionMessage(false, it.optString("text"))) }
                            .onFailure { notice = it.message ?: "Companion response failed." }
                        busy = false
                    }
                }
            ) { Text(if (busy) "…" else "Send") }
        }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                val prompt = "Give me a brief, caring daily check-in prompt for today."
                busy = true
                scope.launch {
                    runCatching {
                        WorkspaceApiClient().workspaceAi(
                            "companion",
                            prompt,
                            "You are BQ Sqwad AI Companion. Personality: $personality. Give a brief supportive daily check-in without pretending to be a human."
                        )
                    }.onSuccess { messages.add(CompanionMessage(false, it.text)) }
                        .onFailure { notice = it.message ?: "Check-in failed." }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Daily check-in") }
    }
}
