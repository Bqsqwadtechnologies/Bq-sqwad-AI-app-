@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.AdminProviderClient
import com.bqsqwad.ai.core.AdminProviderSecretStatus
import kotlinx.coroutines.launch

@Composable
fun AdminProviderSecretsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember { AdminProviderClient() }
    var statuses by remember { mutableStateOf<List<AdminProviderSecretStatus>>(emptyList()) }
    var providerId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            runCatching { client.statuses() }
                .onSuccess { statuses = it; error = null }
                .onFailure { error = it.message ?: "Could not load provider status." }
                .also { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API & Provider Secrets") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Central provider control", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Secrets are sent only to the authenticated backend and are encrypted server-side. The dashboard never reads them back.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    providerId,
                    { providerId = it.trim().lowercase(); error = null },
                    label = { Text("Provider ID") },
                    supportingText = { Text("Examples: gemini, openai, tavily, or your own provider ID.") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    secret,
                    { secret = it; error = null },
                    label = { Text("API key / secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !loading && providerId.length >= 2 && secret.isNotEmpty(),
                        onClick = {
                            loading = true; message = null
                            scope.launch {
                                runCatching { client.save(providerId, secret) }
                                    .onSuccess {
                                        secret = ""
                                        message = "Credential saved. New backend requests can use it immediately."
                                        refresh()
                                    }
                                    .onFailure { error = it.message ?: "Could not save credential." }
                                loading = false
                            }
                        }
                    ) { Text(if (loading) "Saving…" else "Save / Replace") }
                    OutlinedButton(
                        enabled = !loading && providerId.length >= 2,
                        onClick = {
                            loading = true
                            scope.launch {
                                runCatching { client.remove(providerId) }
                                    .onSuccess { message = "Credential removed."; refresh() }
                                    .onFailure { error = it.message ?: "Could not remove credential." }
                                loading = false
                            }
                        }
                    ) { Text("Remove") }
                    OutlinedButton(enabled = !loading, onClick = { refresh() }) { Text("Refresh") }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            item { HorizontalDivider() }
            item { Text("Configured providers", style = MaterialTheme.typography.titleLarge) }
            items(statuses, key = { it.providerId }) { status ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(status.providerId, style = MaterialTheme.typography.titleMedium)
                        Text(if (status.configured) "Configured • secret hidden" else "Not configured")
                        Text("Source: " + status.source, style = MaterialTheme.typography.bodySmall)
                        status.updatedAt?.let { Text("Updated: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
