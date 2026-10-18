package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Science
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.AdminAdvancedClient
import com.bqsqwad.ai.core.AdvancedAdminOption
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminFeatureTestLab(onBack: () -> Unit) {
    val client = remember { AdminAdvancedClient() }
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<List<AdvancedAdminOption>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var testMode by remember { mutableStateOf(false) }
    var testModeAvailable by remember { mutableStateOf(false) }
    var selfTest by remember { mutableStateOf<JSONObject?>(null) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        runCatching { client.list() }
            .onSuccess { options = it.sortedBy { option -> option.label.lowercase() }; error = null }
            .onFailure { error = "Could not load server capability controls. Check Admin Diagnostics." }
        runCatching { client.testMode() }
            .onSuccess { testMode = it; testModeAvailable = true }
            .onFailure { testModeAvailable = false }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    val filtered = options.filter { option ->
        val matchesSearch = search.isBlank() ||
            option.label.contains(search, ignoreCase = true) ||
            option.id.contains(search, ignoreCase = true)
        val isCapability = option.id.startsWith("capability_")
        val matchesFilter = when (filter) {
            "Capabilities" -> isCapability
            "Admin controls" -> !isCapability
            "Enabled" -> option.enabled && !option.killSwitch
            "Disabled" -> !option.enabled || option.killSwitch
            else -> true
        }
        matchesSearch && matchesFilter
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Feature Test Lab") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Column(Modifier.weight(1f)) {
                                Text("Android feature controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Server-authoritative switches", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            "Use this bench to enable or disable configured server capabilities while testing the native Android app. These are shared server-side controls, not device-only overrides. A switch blocks the operations wired to that capability; other workspace management endpoints can have separate access controls. Turn a feature back on after testing if it should remain available.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            if (testModeAvailable) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Super-admin plan-gate test mode", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Temporarily bypasses supported plan gates for the authorized root super-admin. It does not bypass disabled capabilities, consent, provider configuration, or security checks.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = testMode,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            busyId = "test-mode"
                                            runCatching { client.setTestMode(enabled) }
                                                .onSuccess { testMode = enabled; notice = if (enabled) "Admin test mode enabled." else "Admin test mode disabled."; error = null }
                                                .onFailure { error = "Test mode could not be changed. Verify root super-admin authorization." }
                                            busyId = null
                                        }
                                    },
                                    enabled = busyId == null
                                )
                            }
                            Text(if (testMode) "TEST MODE ON" else "TEST MODE OFF", fontWeight = FontWeight.Bold, color = if (testMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Backend readiness check", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Runs the existing server readiness self-test. It checks configured provider/catalog readiness and creates then discards a short-lived voice session secret; it does not send audio or generate a chat/image.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick = {
                                scope.launch {
                                    busyId = "self-test"
                                    runCatching { WorkspaceApiClient().adminReadinessSelfTest() }
                                        .onSuccess { selfTest = it; notice = "Readiness check completed."; error = null }
                                        .onFailure { error = "Readiness check failed. Open Admin Diagnostics for sanitized details." }
                                    busyId = null
                                }
                            },
                            enabled = busyId == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busyId == "self-test") "Checking…" else "Run readiness self-test") }
                        selfTest?.let { result ->
                            Text("Checked: " + result.optString("checkedAt", "recently"), style = MaterialTheme.typography.labelMedium)
                            val checks = result.optJSONArray("checks")
                            if (checks != null) {
                                for (index in 0 until checks.length()) {
                                    val check = checks.optJSONObject(index) ?: JSONObject()
                                    val status = check.optString("status", "unknown").replace('_', ' ')
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = if (status.contains("pass") || status.contains("ready") || status.contains("created")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                                        Text(check.optString("feature", "Feature").replace('_', ' ') + " • " + status, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Find a feature or capability") }
                )
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Capabilities", "Admin controls", "Enabled", "Disabled").forEach { value ->
                        FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) })
                    }
                }
            }
            item {
                Text(
                    "Configured controls • " + options.size + " total • " + options.count { it.enabled && !it.killSwitch } + " available",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (filtered.isEmpty() && !loading) {
                item { Text("No configured controls match this filter.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(filtered, key = { it.id }) { option ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(option.label.ifBlank { option.id }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(option.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (option.id.startsWith("capability_")) {
                                Switch(
                                    checked = option.enabled && !option.killSwitch,
                                    enabled = busyId == null,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            busyId = option.id
                                            val updated = option.copy(
                                                enabled = enabled,
                                                killSwitch = !enabled,
                                                rolloutPercent = if (enabled) 100 else option.rolloutPercent
                                            )
                                            runCatching { client.update(updated) }
                                                .onSuccess { saved ->
                                                    options = options.map { if (it.id == option.id) saved else it }
                                                    notice = option.label + if (enabled) " enabled on the server." else " disabled on the server."
                                                    error = null
                                                }
                                                .onFailure { error = "Could not save " + option.label + ". Check Admin Diagnostics and retry." }
                                            busyId = null
                                        }
                                    }
                                )
                            } else {
                                Text("ADMIN CONTROL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (option.enabled && !option.killSwitch) "AVAILABLE" else "DISABLED", style = MaterialTheme.typography.labelSmall, color = if (option.enabled && !option.killSwitch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text("Rollout " + option.rolloutPercent + "%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (option.allowedPlanIds.isNotEmpty()) Text("Plan-scoped", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (option.id.startsWith("capability_")) "This is a shared server-side control for requests wired to this capability, not a per-device override." else "This is a general admin setting. Change it in its dedicated admin panel to preserve its specific validation and safeguards.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                TextButton(onClick = { scope.launch { refresh() } }, enabled = busyId == null) { Text("Refresh controls") }
            }
            item { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) } }
        }
    }
}
