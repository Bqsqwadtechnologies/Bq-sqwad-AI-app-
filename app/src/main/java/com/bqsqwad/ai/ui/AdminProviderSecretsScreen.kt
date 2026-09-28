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
import org.json.JSONObject

private val CORE_PROVIDERS = listOf(
    "openai" to "OpenAI",
    "gemini" to "Google Gemini",
    "tavily" to "Tavily Web Search",
    "youtube_client_secret" to "YouTube OAuth",
    "x_client_secret" to "X OAuth"
)

private val CLOUDFLARE_SECRET_LABELS = listOf(
    "BQ_FIREBASE_SERVICE_ACCOUNT_JSON" to "Firebase service account",
    "FIREBASE_WEB_API_KEY" to "Firebase Web API key",
    "OPENAI_API_KEY" to "OpenAI",
    "GEMINI_API_KEY" to "Google Gemini",
    "TAVILY_API_KEY" to "Tavily Web Search",
    "YOUTUBE_CLIENT_SECRET" to "YouTube OAuth",
    "X_CLIENT_SECRET" to "X OAuth",
    "X_API_SECRET" to "X API secret",
    "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" to "Google Play",
    "BQ_SOCIAL_ENCRYPTION_KEY" to "Social token encryption"
)

@Composable
fun AdminProviderSecretsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember { AdminProviderClient() }
    var statuses by remember { mutableStateOf<List<AdminProviderSecretStatus>>(emptyList()) }
    var routes by remember { mutableStateOf<List<AdminProviderRoute>>(emptyList()) }
    var socialEnabled by remember { mutableStateOf(false) }
    var xEnabled by remember { mutableStateOf(false) }
    var xClientId by remember { mutableStateOf("") }
    var providerId by remember { mutableStateOf("openai") }
    var secret by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var cloudflareSecrets by remember { mutableStateOf<JSONObject?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            runCatching { client.providerOverview() }
                .onSuccess { (nextStatuses, nextRoutes, social) ->
                    statuses = nextStatuses
                    routes = nextRoutes
                    socialEnabled = social.enabled
                    xEnabled = social.xEnabled
                    xClientId = social.xClientId
                    error = null
                }
                .onFailure { error = it.message ?: "Could not load provider configuration." }
            runCatching { client.cloudflareSecretStatus() }
                .onSuccess { cloudflareSecrets = it.optJSONObject("configured") }
                .onFailure { error = it.message ?: "Could not verify Cloudflare Worker secrets." }
            loading = false
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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Cloudflare Worker secrets", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Runtime credentials are checked on the Worker without exposing secret values. Green means the Worker environment contains the expected secret.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { refresh() }) { Text("Refresh") }
                        }
                        CLOUDFLARE_SECRET_LABELS.forEach { (key, label) ->
                            val configured = cloudflareSecrets?.optBoolean(key, false) == true
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    Modifier.size(9.dp),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                ) {}
                                Text(label, Modifier.weight(1f), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(
                                    if (configured) "Connected" else "Missing",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }


            item {
                Text("Add or replace a provider key", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Choose a known provider or enter another backend provider ID. Only the server receives the secret.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CORE_PROVIDERS.forEach { (id, label) ->
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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("X OAuth connection", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "The X client ID is configuration, not a secret. The X client secret remains encrypted in the server secret store and is never shown or stored in the APK.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Social integrations enabled")
                            Switch(checked = socialEnabled, onCheckedChange = { socialEnabled = it })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("X enabled")
                            Switch(checked = xEnabled, onCheckedChange = { xEnabled = it })
                        }
                        OutlinedTextField(
                            value = xClientId,
                            onValueChange = { xClientId = it },
                            label = { Text("X OAuth 2.0 Client ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Required X scopes: tweet.read, tweet.write, users.read, offline.access",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    loading = true
                                    runCatching {
                                        client.saveSocialConfig(
                                            socialEnabled,
                                            xEnabled,
                                            xClientId,
                                            listOf("tweet.read", "tweet.write", "users.read", "offline.access")
                                        )
                                    }.onSuccess {
                                        message = "X OAuth configuration saved. The client secret remains server-side."
                                    }.onFailure {
                                        error = it.message ?: "Could not save X OAuth configuration."
                                    }
                                    loading = false
                                    refresh()
                                }
                            },
                            enabled = !loading && xClientId.isNotBlank()
                        ) { Text("Save X OAuth configuration") }
                    }
                }

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
