@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.AdminAdvancedClient
import com.bqsqwad.ai.core.AdvancedAdminOption
import kotlinx.coroutines.launch

@Composable
fun AdminConfigScreen() {
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<List<AdvancedAdminOption>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loading = true; error = null
        scope.launch {
            try { options = AdminAdvancedClient().list() }
            catch (t: Throwable) { error = t.message ?: "Admin configuration could not be loaded." }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(topBar = { TopAppBar(title = { Text("Admin configuration") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Live controls", style = MaterialTheme.typography.headlineSmall) }
            item { Text("Changes are stored server-side and audited. Commercial values remain configuration-driven.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Button(onClick = { reload() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh") } }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            items(options, key = { it.id }) { option ->
                var enabled by remember(option.id, option.version) { mutableStateOf(option.enabled) }
                var saving by remember(option.id, option.version) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(option.label.ifBlank { option.id }, style = MaterialTheme.typography.titleMedium)
                        Text(option.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Enabled")
                            Switch(checked = enabled, onCheckedChange = { value ->
                                enabled = value; saving = true
                                scope.launch {
                                    try {
                                        AdminAdvancedClient().update(option.copy(enabled = value, version = option.version))
                                    } catch (t: Throwable) {
                                        enabled = !value
                                        error = t.message ?: "Could not save configuration."
                                    } finally { saving = false }
                                }
                            }, enabled = !saving)
                        }
                        if (option.id == "web_search_pricing") {
                            var searchCredits by remember(option.id, option.version) { mutableStateOf(option.config.creditsPerSearch.toString()) }
                            var researchCredits by remember(option.id, option.version) { mutableStateOf(option.config.creditsPerDeepResearch.toString()) }
                            var maxResults by remember(option.id, option.version) { mutableStateOf(option.config.maxResults.toString()) }
                            Text("Web Search pricing & limits", style = MaterialTheme.typography.titleSmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(searchCredits, { searchCredits = it }, label = { Text("Credits/search") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(researchCredits, { researchCredits = it }, label = { Text("Credits/deep research") }, modifier = Modifier.weight(1f))
                            }
                            OutlinedTextField(maxResults, { maxResults = it }, label = { Text("Max results") }, modifier = Modifier.fillMaxWidth())
                            Button(
                                enabled = !saving,
                                onClick = {
                                    saving = true
                                    scope.launch {
                                        try {
                                            val cfg = option.config.copy(
                                                creditsPerSearch = searchCredits.toDoubleOrNull() ?: option.config.creditsPerSearch,
                                                creditsPerDeepResearch = researchCredits.toDoubleOrNull() ?: option.config.creditsPerDeepResearch,
                                                maxResults = (maxResults.toLongOrNull() ?: option.config.maxResults).coerceIn(1, 20)
                                            )
                                            AdminAdvancedClient().update(option.copy(config = cfg))
                                            options = AdminAdvancedClient().list()
                                        } catch (t: Throwable) {
                                            error = t.message ?: "Could not save web-search pricing."
                                        } finally { saving = false }
                                    }
                                }
                            ) { Text("Save Web Search settings") }
                        }
                        Text("Credits: " + option.creditCost + " • Rollout: " + option.rolloutPercent + "%")
                        Text("Version " + option.version + if (saving) " • Saving…" else "")
                    }
                }
            }
        }
    }
}
