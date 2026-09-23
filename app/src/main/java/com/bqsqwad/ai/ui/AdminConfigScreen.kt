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
                        Text("Credits: " + option.creditCost + " • Rollout: " + option.rolloutPercent + "%")
                        Text("Version " + option.version + if (saving) " • Saving…" else "")
                    }
                }
            }
        }
    }
}
