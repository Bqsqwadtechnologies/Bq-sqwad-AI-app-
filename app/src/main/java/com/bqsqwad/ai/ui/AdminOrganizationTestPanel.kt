package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AdminOrganizationTestPanel(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var control by remember { mutableStateOf<JSONObject?>(null) }
    var selectedPlan by remember { mutableStateOf("") }
    var targetUid by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            runCatching { WorkspaceApiClient().adminOrganizationControl() }
                .onSuccess { value ->
                    control = value
                    if (selectedPlan.isBlank()) {
                        selectedPlan = value.optJSONArray("plans")?.optJSONObject(0)?.optString("id").orEmpty()
                    }
                    error = null
                }
                .onFailure { error = it.message ?: "Organization admin controls could not load." }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val plans = remember(control) {
        val array = control?.optJSONArray("plans") ?: org.json.JSONArray()
        List(array.length()) { index -> array.optJSONObject(index) ?: JSONObject() }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Organization AI Admin Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("No subscription prices", style = MaterialTheme.typography.labelMedium)
        }
        Text(
            "Organization plans are operational profiles, not customer-priced subscriptions. The administrator activates a profile and customer usage is billed separately from the Organization USD wallet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to Admin Control Center") }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Admin test session", style = MaterialTheme.typography.titleLarge)
                val session = control?.optJSONObject("testSession")
                Text(
                    if (session?.optBoolean("enabled", false) == true)
                        "TEST MODE: " + session.optString("planId") + " • expires " + session.optString("expiresAt")
                    else "No Organization plan test session is active."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = selectedPlan.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { WorkspaceApiClient().setAdminOrganizationTestSession(selectedPlan, true, 120) }
                                    .onSuccess { notice = "Organization test session activated for $selectedPlan."; refresh() }
                                    .onFailure { error = it.message ?: "Could not start Organization test session." }
                                busy = false
                            }
                        }
                    ) { Text("Test selected plan") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { WorkspaceApiClient().setAdminOrganizationTestSession(selectedPlan.ifBlank { "orgcore" }, false) }
                                    .onSuccess { notice = "Organization test session stopped."; refresh() }
                                    .onFailure { error = it.message ?: "Could not stop Organization test session." }
                                busy = false
                            }
                        }
                    ) { Text("Exit test") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Activate a plan for an organization user", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = targetUid,
                    onValueChange = { targetUid = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Firebase user UID") },
                    singleLine = true
                )
                Text("Select the operational profile to activate. Prices are intentionally absent.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    enabled = targetUid.isNotBlank() && selectedPlan.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { WorkspaceApiClient().setAdminOrganizationEntitlement(targetUid.trim(), selectedPlan, true) }
                                .onSuccess { notice = "Activated $selectedPlan for the selected organization user." }
                                .onFailure { error = it.message ?: "Organization activation failed." }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Activate selected plan for user") }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(plans, key = { it.optString("id") }) { plan ->
                val id = plan.optString("id")
                val active = plan.optBoolean("active", false)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(plan.optString("name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(id, style = MaterialTheme.typography.labelSmall)
                            }
                            FilterChip(selected = selectedPlan == id, onClick = { selectedPlan = id }, label = { Text("Test") })
                        }
                        Text(plan.optString("description"))
                        Text("Billing: " + plan.optString("billingModel") + " • Price: none")
                        val features = plan.optJSONArray("features") ?: org.json.JSONArray()
                        if (features.length() > 0) {
                            Text("Features: " + List(features.length()) { features.optString(it) }.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !active && !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        runCatching { WorkspaceApiClient().setAdminOrganizationPlanStatus(id, true) }
                                            .onSuccess { notice = "$id activated globally."; refresh() }
                                            .onFailure { error = it.message ?: "Could not activate $id." }
                                        busy = false
                                    }
                                }
                            ) { Text(if (active) "Active" else "Activate") }
                            OutlinedButton(
                                enabled = active && !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        runCatching { WorkspaceApiClient().setAdminOrganizationPlanStatus(id, false, true) }
                                            .onSuccess { notice = "$id deactivated globally."; refresh() }
                                            .onFailure { error = it.message ?: "Could not deactivate $id." }
                                        busy = false
                                    }
                                }
                            ) { Text("Deactivate") }
                        }
                    }
                }
            }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
