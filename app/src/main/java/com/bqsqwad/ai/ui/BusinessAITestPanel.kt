@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BusinessAITestPanel(onBack: () -> Unit) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableStateOf("") }
    var session by remember { mutableStateOf(JSONObject().put("enabled", false)) }
    var suite by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDeactivate by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true
        scope.launch {
            runCatching {
                val catalog = api.adminPlanCatalog()
                val rows = catalog.optJSONArray("plans") ?: JSONArray()
                plans = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("module") == "business" }
                session = api.adminBusinessTestSession()
                if (selected.isBlank()) selected = session.optString("planId").ifBlank { plans.firstOrNull()?.optString("id").orEmpty() }
            }.onFailure { error = it.message ?: "Business test controls could not be loaded." }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    pendingDeactivate?.let { planId ->
        AlertDialog(
            onDismissRequest = { pendingDeactivate = null },
            title = { Text("Deactivate Business plan?") },
            text = { Text("This immediately blocks the plan entitlement for affected accounts. The action will be written to the admin audit log.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = planId
                    pendingDeactivate = null
                    busy = true
                    scope.launch {
                        runCatching { api.setAdminBusinessPlanStatus(id, false, confirmDeactivation = true) }
                            .onFailure { error = it.message ?: "Could not deactivate plan." }
                        busy = false
                        load()
                    }
                }) { Text("Deactivate") }
            },
            dismissButton = { TextButton(onClick = { pendingDeactivate = null }) { Text("Cancel") } }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Business AI — Admin Test Lab") }, navigationIcon = { IconButton(onClick = onBack) { Text("‹") } }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SurfaceCard(
                    "Safe admin testing",
                    "Test one Business plan at a time without granting that test entitlement to customers. Test sessions are server-side and temporary."
                )
            }
            error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error) } }
            item {
                Text("Business plans", style = MaterialTheme.typography.titleLarge)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            items(plans, key = { it.optString("id") }) { plan ->
                val id = plan.optString("id")
                val active = plan.optBoolean("active", true)
                Card(
                    onClick = { selected = id; suite = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(plan.optString("name", id), style = MaterialTheme.typography.titleMedium)
                        Text(id, style = MaterialTheme.typography.labelSmall)
                        Text(if (active) "Catalogue status: Active" else "Catalogue status: Inactive")
                        Text("Features: " + (plan.optJSONArray("features")?.length() ?: 0))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected == id, { selected = id; suite = null }, label = { Text("Select") })
                            OutlinedButton(enabled = !busy, onClick = {
                                if (active) {
                                    pendingDeactivate = id
                                } else {
                                    busy = true
                                    scope.launch {
                                        runCatching { api.setAdminBusinessPlanStatus(id, true) }
                                            .onFailure { error = it.message ?: "Could not reactivate plan." }
                                        busy = false
                                        load()
                                    }
                                }
                            }) { Text(if (active) "Deactivate" else "Reactivate") }
                        }
                    }
                }
            }
            item {
                val current = plans.firstOrNull { it.optString("id") == selected }
                if (current != null) {
                    Text("Selected: " + current.optString("name"), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && current.optBoolean("active", true), onClick = {
                            busy = true
                            scope.launch {
                                runCatching { session = api.setAdminBusinessTestSession(selected, true, 120) }
                                    .onFailure { error = it.message ?: "Could not start test session." }
                                busy = false
                            }
                        }) { Text("Unlock for 2h") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                runCatching { session = api.setAdminBusinessTestSession(selected, false) }
                                    .onFailure { error = it.message ?: "Could not stop test session." }
                                busy = false
                            }
                        }) { Text("Deactivate test") }
                    }
                    Text(
                        if (session.optBoolean("enabled") && session.optString("planId") == selected)
                            "Admin test session active for " + selected
                        else "No active admin test session for this plan.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Button(
                    enabled = !busy && selected.isNotBlank() && session.optBoolean("enabled") && session.optString("planId") == selected,
                    onClick = {
                        busy = true
                        suite = null
                        scope.launch {
                            runCatching { suite = api.runAdminBusinessTestSuite(selected) }
                                .onFailure { error = it.message ?: "Business test suite failed to run." }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Running…" else "Run End-to-End Business Test Suite") }
            }
            suite?.let { result ->
                item {
                    val checks = result.optJSONArray("checks") ?: JSONArray()
                    val passed = (0 until checks.length()).count { checks.getJSONObject(it).optString("status") == "passed" }
                    val failed = checks.length() - passed
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(if (result.optBoolean("ok")) "TEST SUITE PASSED" else "TEST SUITE NEEDS ATTENTION", style = MaterialTheme.typography.titleMedium)
                            Text("$passed passed • $failed failed")
                            for (i in 0 until checks.length()) {
                                val check = checks.getJSONObject(i)
                                Text(
                                    (if (check.optString("status") == "passed") "✓ " else "✕ ") +
                                        check.optString("name") +
                                        check.optString("error").takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
