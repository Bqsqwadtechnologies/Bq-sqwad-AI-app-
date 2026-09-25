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
import com.bqsqwad.ai.core.AdminProviderRoute
import com.bqsqwad.ai.core.AdminProviderSecretStatus
import kotlinx.coroutines.launch

private val CORE_PROVIDERS = listOf(
    "openai" to "OpenAI",
    "gemini" to "Google Gemini",
    "tavily" to "Tavily Web Search",
    "youtube_client_secret" to "YouTube OAuth",
    "x_client_secret" to "X OAuth"
)

@Composable
fun AdminProviderSecretsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember { AdminProviderClient() }
    var statuses by remember { mutableStateOf<List<AdminProviderSecretStatus>>(emptyList()) }
    var routes by remember { mutableStateOf<List<AdminProviderRoute>>(emptyList()) }
    var providerId by remember { mutableStateOf("openai") }
    var secret by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            runCatching { client.providerOverview() }
                .onSuccess { (nextStatuses, nextRoutes) ->
                    statuses = nextStatuses
                    routes = nextRoutes
                    error = null
                }
                .onFailure { error = it.message ?: "Could not load provider configuration." }
                .also { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Providers & Secure Keys") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AI Runtime", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "OpenAI is the default provider for general AI capabilities. Gemini is the configured fallback/provider option. Provider secrets are encrypted on the backend and are never displayed after saving.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AssistChip(
                            onClick = { providerId = "openai" },
                            label = { Text("Default: OpenAI") }
                        )
                    }
                }
            }

            item {
                Text("Add or replace a provider key", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Choose a known provider or enter another backend provider ID. Only the server receives the secret.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(CORE_PROVIDERS) { (id, label) ->
                        FilterChip(
                            selected = providerId == id,
                            onClick = { providerId = id; error = null },
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                OutlinedTextField(
                    value = providerId,
                    onValueChange = { providerId = it.trim().lowercase(); error = null },
                    label = { Text("Provider ID") },
                    supportingText = { Text("Examples: openai, gemini, tavily, youtube_client_secret, x_client_secret") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it; error = null },
                    label = { Text("API key / secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !loading && providerId.length >= 2 && secret.isNotEmpty(),
                        onClick = {
                            loading = true
                            message = null
                            scope.launch {
                                runCatching { client.save(providerId, secret) }
                                    .onSuccess {
                                        secret = ""
                                        message = "Credential saved securely. Backend requests can use it immediately."
                                        refresh()
                                    }
                                    .onFailure { error = it.message ?: "Could not save credential." }
                                loading = false
                            }
                        }
                    ) { Text(if (loading) "Saving…" else "Save / Replace Key") }

                    OutlinedButton(
                        enabled = !loading && providerId.length >= 2,
                        onClick = {
                            loading = true
                            scope.launch {
                                runCatching { client.remove(providerId) }
                                    .onSuccess {
                                        message = "Credential removed."
                                        refresh()
                                    }
                                    .onFailure { error = it.message ?: "Could not remove credential." }
                                loading = false
                            }
                        }
                    ) { Text("Remove") }
                }
            }

            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }

            item {
                HorizontalDivider()
                Text("Provider status", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            }

            items(CORE_PROVIDERS, key = { it.first }) { (id, label) ->
                val status = statuses.firstOrNull { it.providerId == id }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text(id, style = MaterialTheme.typography.labelSmall)
                        Text(
                            if (status?.configured == true) "Configured • secret hidden"
                            else "Not configured",
                            color = if (status?.configured == true)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Text("Source: " + (status?.source ?: "unknown"), style = MaterialTheme.typography.bodySmall)
                        status?.updatedAt?.let { Text("Updated: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            item {
                HorizontalDivider()
                Text("AI routing", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                Text(
                    "Primary provider controls are server-side. General capabilities default to OpenAI with Gemini available as fallback. Save a route only when you want to override that default.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(routes, key = { it.capability }) { route ->
                ProviderRouteEditor(route = route, client = client, onSaved = { refresh() })
            }
        }
    }
}

@Composable
private fun ProviderRouteEditor(
    route: AdminProviderRoute,
    client: AdminProviderClient,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var primary by remember(route.capability, route.primaryProviderId) {
        mutableStateOf(route.primaryProviderId ?: "openai")
    }
    var model by remember(route.capability, route.primaryModelId) {
        mutableStateOf(route.primaryModelId ?: "")
    }
    var fallbacks by remember(route.capability, route.fallbackProviderIds) {
        mutableStateOf(route.fallbackProviderIds.joinToString(","))
    }
    var enabled by remember(route.capability, route.enabled) { mutableStateOf(route.enabled) }
    var saving by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(route.capability, style = MaterialTheme.typography.titleMedium)
            Text(
                "Primary: $primary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = primary == "openai",
                    onClick = { primary = "openai" },
                    label = { Text("OpenAI primary") }
                )
                FilterChip(
                    selected = primary == "gemini",
                    onClick = { primary = "gemini" },
                    label = { Text("Gemini primary") }
                )
            }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Primary model") },
                supportingText = { Text("Leave blank to use the backend provider default.") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fallbacks,
                onValueChange = { fallbacks = it },
                label = { Text("Fallback providers") },
                supportingText = { Text("Comma separated. Example: gemini") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Text("Enabled", modifier = Modifier.padding(top = 12.dp))
            }
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching {
                            client.saveRoute(
                                capability = route.capability,
                                primaryProviderId = primary,
                                primaryModelId = model.ifBlank { null },
                                fallbackProviderIds = fallbacks.split(",").map(String::trim).filter(String::isNotBlank),
                                enabled = enabled
                            )
                        }.onSuccess { onSaved() }
                        saving = false
                    }
                }
            ) { Text(if (saving) "Saving…" else "Save routing") }
        }
    }
}
