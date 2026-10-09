package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.AdminAdvancedClient
import com.bqsqwad.ai.core.WorkspaceApiClient
import com.bqsqwad.ai.core.AdminOperationalConfig
import com.bqsqwad.ai.core.AdminPlayCatalogItem
import com.bqsqwad.ai.core.AdvancedAdminOption
import kotlinx.coroutines.launch

@Composable
fun AdminControlCenter() {
    val scope = rememberCoroutineScope()
    val client = remember { AdminAdvancedClient() }
    var options by remember { mutableStateOf<List<AdvancedAdminOption>>(emptyList()) }
    var selectedSection by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPlanCatalog by remember { mutableStateOf(false) }
    var showContactSales by remember { mutableStateOf(false) }
    var showProviderSecrets by remember { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
    var showRuntime by remember { mutableStateOf(false) }
    var showBusinessTest by remember { mutableStateOf(false) }
    var showOrganizationTest by remember { mutableStateOf(false) }
    var showAdminOperations by remember { mutableStateOf(false) }
    var showAppEditor by remember { mutableStateOf(false) }
    var playCatalog by remember { mutableStateOf<List<AdminPlayCatalogItem>>(emptyList()) }
    var testMode by remember { mutableStateOf(false) }
    var testModeSaving by remember { mutableStateOf(false) }
    var showGrantCredits by remember { mutableStateOf(false) }
    var grantEmail by remember { mutableStateOf("") }
    var grantAmount by remember { mutableStateOf("100") }
    var grantReason by remember { mutableStateOf("Admin free-credit grant") }
    var grantSaving by remember { mutableStateOf(false) }
    var adminCredits by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var adminIssueRows by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        runCatching { client.list() }
            .onSuccess { options = it; error = null }
            .onFailure { error = it.message ?: "Unable to load Admin Control Center" }
        loading = false
        runCatching { client.playCatalog() }.onSuccess { playCatalog = it }
        runCatching { client.testMode() }.onSuccess { testMode = it }
        runCatching { WorkspaceApiClient().credits() }.onSuccess { adminCredits = it }
        runCatching { WorkspaceApiClient().adminIssues() }.onSuccess { body ->
            val items = body.optJSONArray("items")
            adminIssueRows = if (items == null) emptyList() else List(items.length()) { i ->
                val item = items.optJSONObject(i) ?: org.json.JSONObject()
                "HTTP " + item.optInt("status", 500) + " • " +
                    item.optString("route", "/") + " • " + item.optString("code", "backend_unavailable")
            }
        }
    }

    val sections = listOf(
        "all" to "All",
        "ai_models" to "AI & Models",
        "providers" to "Providers",
        "routing" to "Routing",
        "capabilities" to "Capabilities",
        "credits" to "Credits",
        "pricing" to "Pricing",
        "plans" to "Plans",
        "payments" to "Payments",
        "subscriptions" to "Subscriptions",
        "business" to "Business",
        "agents" to "Agents",
        "device" to "Device Intelligence",
        "privacy" to "Privacy",
        "security" to "Security",
        "users" to "Users",
        "notifications" to "Notifications",
        "referrals" to "Referrals",
        "codron" to "Codron",
        "plugins" to "Plugins",
        "experiments" to "Experiments",
        "rollouts" to "Rollouts",
        "monitoring" to "Monitoring",
        "incidents" to "Incidents",
        "audit" to "Audit",
        "data" to "Data",
        "system" to "System",
        "emergency" to "Emergency Controls"
    )


    if (showPlanCatalog) {
        AdminPlanCatalogScreen { showPlanCatalog = false }
        return
    }
    if (showContactSales) {
        AdminContactSalesScreen { showContactSales = false }
        return
    }
    if (showProviderSecrets) {
        AdminProviderSecretsScreen { showProviderSecrets = false }
        return
    }
    if (showSecurity) {
        AdminSecurityScreen { showSecurity = false }
        return
    }
    if (showRuntime) {
        AdminRuntimeScreen { showRuntime = false }
        return
    }
    if (showBusinessTest) {
        BusinessAITestPanel { showBusinessTest = false }
        return
    }
    if (showOrganizationTest) {
        AdminOrganizationTestPanel { showOrganizationTest = false }
        return
    }
    if (showAdminOperations) {
        AdminOperationsPanel { showAdminOperations = false }
        return
    }
    if (showAppEditor) {
        AdminAppEditorPanel { showAppEditor = false }
        return
    }

    if (showGrantCredits) {
        AlertDialog(
            onDismissRequest = { if (!grantSaving) showGrantCredits = false },
            title = { Text("Grant Free Credits") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add server-side credits to a customer balance. This is recorded in the admin audit ledger.")
                    OutlinedTextField(grantEmail, { grantEmail = it }, label = { Text("User email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(grantAmount, { grantAmount = it }, label = { Text("Credits") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(grantReason, { grantReason = it }, label = { Text("Reason") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    enabled = !grantSaving && grantEmail.isNotBlank() && (grantAmount.toDoubleOrNull() ?: 0.0) > 0,
                    onClick = {
                        grantSaving = true
                        scope.launch {
                            runCatching { client.grantCredits(grantEmail, grantAmount.toDouble(), grantReason) }
                                .onSuccess {
                                    error = null
                                    showGrantCredits = false
                                    grantEmail = ""
                                    grantAmount = "100"
                                }
                                .onFailure { error = it.message ?: "Credit grant failed." }
                            grantSaving = false
                        }
                    }
                ) { Text(if (grantSaving) "Granting…" else "Grant credits") }
            },
            dismissButton = { TextButton(onClick = { showGrantCredits = false }, enabled = !grantSaving) { Text("Cancel") } }
        )
    }

    val filtered = if (selectedSection == "all") options else options.filter { it.section == selectedSection }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Admin Control Center", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Server-authoritative operations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Total credits", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        if (adminCredits?.optBoolean("adminUnlimited", false) == true) "Unlimited" else String.format(java.util.Locale.US, "%.0f", adminCredits?.optDouble("available", 0.0) ?: 0.0),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        OutlinedButton(onClick = { showPlanCatalog = true }, modifier = Modifier.fillMaxWidth()) { Text("Open Plan & Product ID Catalog") }
        if (adminIssueRows.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Open service incidents", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("The backend has recorded issues that need administrator review.", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    adminIssueRows.take(5).forEach { row ->
                        Text(row, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Admin Test Mode", fontWeight = FontWeight.Bold)
                    Text("Server-controlled QA access. When OFF, admin navigation follows normal entitlement rules.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = testMode,
                    enabled = !testModeSaving,
                    onCheckedChange = { enabled ->
                        testModeSaving = true
                        scope.launch {
                            runCatching { client.setTestMode(enabled) }
                                .onSuccess { testMode = it }
                                .onFailure { error = it.message ?: "Could not change Admin Test Mode." }
                            testModeSaving = false
                        }
                    }
                )
            }
        }
        OutlinedButton(onClick = { showGrantCredits = true }, modifier = Modifier.fillMaxWidth()) { Text("Grant Free Credits to User") }
        OutlinedButton(onClick = { showContactSales = true }, modifier = Modifier.fillMaxWidth()) { Text("Open Contact Sales Inbox") }
        OutlinedButton(onClick = { showProviderSecrets = true }, modifier = Modifier.fillMaxWidth()) { Text("Open API & Provider Secrets") }
        OutlinedButton(onClick = { showSecurity = true }, modifier = Modifier.fillMaxWidth()) { Text("Open Admin Security & Authenticator") }
        OutlinedButton(onClick = { showRuntime = true }, modifier = Modifier.fillMaxWidth()) { Text("Open Admin AI Runtime (Agents)") }
        OutlinedButton(onClick = { showBusinessTest = true }, modifier = Modifier.fillMaxWidth()) { Text("Open Business AI End-to-End Test Lab") }
        OutlinedButton(onClick = { showOrganizationTest = true }, modifier = Modifier.fillMaxWidth()) { Text("Open Organization AI Activation & Test Lab") }
        OutlinedButton(onClick = { showAdminOperations = true }, modifier = Modifier.fillMaxWidth()) { Text("Open Admin Operations, Notifications & Staff") }
        OutlinedButton(onClick = { showAppEditor = true }, modifier = Modifier.fillMaxWidth()) { Text("Open App Editor & Publish") }
        SurfaceCard(
            "Admin Control Center",
            "Single authoritative control plane. Backend services read adminAdvancedOptions; the retired adminControls store is no longer used."
        )
        if (playCatalog.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Google Play Plan Catalog", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Admin-only internal IDs. Customer screens do not display these IDs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    playCatalog.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            Text("Plan ID: " + item.planId, style = MaterialTheme.typography.labelSmall)
                            item.productId?.let { Text("Product ID: " + it, style = MaterialTheme.typography.labelSmall) }
                            Text("Billing: " + item.billingPeriod + (item.basePlanId?.let { " • Base plan: " + it } ?: ""), style = MaterialTheme.typography.labelSmall)
                            Text("Price: " + (item.priceUsd?.let { "USD %.2f".format(it) } ?: if (item.contactSales) "Contact Sales" else "Not priced"), style = MaterialTheme.typography.labelSmall)
                            if (item.trialOfferId != null) Text("Trial: " + item.trialOfferId + " • " + (item.trialDays ?: 3) + " days", style = MaterialTheme.typography.labelSmall)
                            if (item.quantityTiers.isNotBlank()) Text("Quantity tiers: " + item.quantityTiers, style = MaterialTheme.typography.labelSmall)
                            if (item.limits.isNotBlank()) Text("Limits: " + item.limits, style = MaterialTheme.typography.labelSmall)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (selectedSection == "all") "All controls" else sections.firstOrNull { it.first == selectedSection }?.second ?: selectedSection,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${filtered.size} server-controlled option(s) • changes are saved through the admin backend",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sections.take(9)) { (id, label) ->
                        AssistChip(
                            onClick = { selectedSection = id },
                            label = { Text(label) },
                            leadingIcon = if (selectedSection == id) ({ Text("✓") }) else null
                        )
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sections.drop(9)) { (id, label) ->
                        AssistChip(
                            onClick = { selectedSection = id },
                            label = { Text(label) },
                            leadingIcon = if (selectedSection == id) ({ Text("✓") }) else null
                        )
                    }
                }
            }
            item { HorizontalDivider() }
            items(filtered, key = { it.id }) { option ->
                AdminOptionEditor(
                    option = option,
                    onSave = { updated ->
                        options = options.map { if (it.id == updated.id) updated else it }
                        scope.launch {
                            runCatching { client.update(updated) }
                                .onFailure { error = it.message ?: "Admin update failed" }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AdminOptionEditor(
    option: AdvancedAdminOption,
    onSave: (AdvancedAdminOption) -> Unit
) {
    var local by remember(option.id, option.version) { mutableStateOf(option) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(option.label, fontWeight = FontWeight.Bold)
                    Text(option.section, style = MaterialTheme.typography.labelSmall)
                    Text(option.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = local.enabled && !local.killSwitch,
                    onCheckedChange = { local = local.copy(enabled = it); onSave(local) }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { local = local.copy(killSwitch = !local.killSwitch); onSave(local) },
                    label = { Text(if (local.killSwitch) "Kill ON" else "Kill switch") })
                AssistChip(onClick = { local = local.copy(requiresApproval = !local.requiresApproval); onSave(local) },
                    label = { Text(if (local.requiresApproval) "Approval required" else "No approval") })
            }

            AdminNumberField("Rollout %", local.rolloutPercent.toString()) {
                local = local.copy(rolloutPercent = it.toIntOrNull()?.coerceIn(0, 100) ?: local.rolloutPercent)
            }
            AdminNumberField("Credit cost", local.creditCost.toString()) {
                local = local.copy(creditCost = it.toLongOrNull()?.coerceAtLeast(0) ?: local.creditCost)
            }
            AdminNumberField("Daily limit", local.dailyLimit?.toString() ?: "") {
                local = local.copy(dailyLimit = it.toLongOrNull()?.coerceAtLeast(0L))
            }
            AdminNumberField("Concurrency limit", local.concurrencyLimit?.toString() ?: "") {
                local = local.copy(concurrencyLimit = it.toLongOrNull()?.coerceAtLeast(0))
            }
            OutlinedTextField(local.providerId ?: "", { local = local.copy(providerId = it.ifBlank { null }) },
                label = { Text("Provider") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(local.modelId ?: "", { local = local.copy(modelId = it.ifBlank { null }) },
                label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(local.allowedPlanIds.joinToString(","), {
                local = local.copy(allowedPlanIds = it.split(",").map(String::trim).filter(String::isNotBlank))
            }, label = { Text("Allowed plan IDs (comma separated)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(local.schedule ?: "", { local = local.copy(schedule = it.ifBlank { null }) },
                label = { Text("Schedule / rollout window") }, modifier = Modifier.fillMaxWidth())
            AdminNumberField("Alert threshold", local.alertThreshold?.toString() ?: "") {
                local = local.copy(alertThreshold = it.toDoubleOrNull()?.coerceAtLeast(0.0))
            }
            AdminNumberField("Configuration version", local.version.toString()) {
                local = local.copy(version = it.toIntOrNull()?.coerceAtLeast(1) ?: local.version)
            }

            Text("Operational policy", fontWeight = FontWeight.SemiBold)
            val c = local.config
            AdminNumberField("Max tool calls", c.maxToolCalls.toString()) {
                local = local.copy(config = c.copy(maxToolCalls = it.toLongOrNull()?.coerceAtLeast(0) ?: c.maxToolCalls))
            }
            AdminNumberField("Max execution seconds", c.maxExecutionSeconds.toString()) {
                local = local.copy(config = c.copy(maxExecutionSeconds = it.toLongOrNull()?.coerceAtLeast(1) ?: c.maxExecutionSeconds))
            }
            AdminNumberField("Response max tokens", c.responseMaxTokens?.toString() ?: "") {
                local = local.copy(config = c.copy(responseMaxTokens = it.toLongOrNull()?.coerceAtLeast(1)))
            }
            AdminNumberField("Context window limit", c.contextWindowLimit?.toString() ?: "") {
                local = local.copy(config = c.copy(contextWindowLimit = it.toLongOrNull()?.coerceAtLeast(1)))
            }
            AdminNumberField("Rate limit / minute", c.rateLimitPerMinute?.toString() ?: "") {
                local = local.copy(config = c.copy(rateLimitPerMinute = it.toLongOrNull()?.coerceAtLeast(0)))
            }
            AdminNumberField("Rate limit / day", c.rateLimitPerDay?.toString() ?: "") {
                local = local.copy(config = c.copy(rateLimitPerDay = it.toLongOrNull()?.coerceAtLeast(0)))
            }
            AdminNumberField("Latency budget ms", c.latencyBudgetMs?.toString() ?: "") {
                local = local.copy(config = c.copy(latencyBudgetMs = it.toLongOrNull()?.coerceAtLeast(0)))
            }
            AdminNumberField("Error threshold %", c.errorThresholdPercent?.toString() ?: "") {
                local = local.copy(config = c.copy(errorThresholdPercent = it.toDoubleOrNull()?.coerceAtLeast(0.0)))
            }
            AdminNumberField("Queue limit", c.queueLimit?.toString() ?: "") {
                local = local.copy(config = c.copy(queueLimit = it.toLongOrNull()?.coerceAtLeast(0)))
            }
            AdminNumberField("Max file size MB", c.maxFileSizeMb?.toString() ?: "") {
                local = local.copy(config = c.copy(maxFileSizeMb = it.toLongOrNull()?.coerceAtLeast(0)))
            }
            AdminNumberField("Upload concurrency", c.maxUploadConcurrency?.toString() ?: "") {
                local = local.copy(config = c.copy(maxUploadConcurrency = it.toLongOrNull()?.coerceAtLeast(0)))
            }
            if (local.id == "web_search_pricing") {
                AdminNumberField("Credits per normal search", c.creditsPerSearch.toString()) {
                    local = local.copy(config = c.copy(creditsPerSearch = it.toDoubleOrNull()?.coerceAtLeast(0.0) ?: c.creditsPerSearch))
                }
                AdminNumberField("Credits per deep research", c.creditsPerDeepResearch.toString()) {
                    local = local.copy(config = c.copy(creditsPerDeepResearch = it.toDoubleOrNull()?.coerceAtLeast(0.0) ?: c.creditsPerDeepResearch))
                }
                AdminNumberField("Credits per result page", c.creditsPerResultPage.toString()) {
                    local = local.copy(config = c.copy(creditsPerResultPage = it.toDoubleOrNull()?.coerceAtLeast(0.0) ?: c.creditsPerResultPage))
                }
                AdminNumberField("Maximum results", c.maxResults.toString()) {
                    local = local.copy(config = c.copy(maxResults = it.toLongOrNull()?.coerceIn(1, 20) ?: c.maxResults))
                }
                AdminNumberField("Daily user search limit", c.dailyUserLimit?.toString() ?: "") {
                    local = local.copy(config = c.copy(dailyUserLimit = it.toLongOrNull()?.coerceAtLeast(0)))
                }
            }
            OutlinedTextField(c.systemPromptVersion ?: "", { local = local.copy(config = c.copy(systemPromptVersion = it.ifBlank { null })) },
                label = { Text("System prompt/config version") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(c.fallbackProviderIds.joinToString(","), {
                local = local.copy(config = c.copy(fallbackProviderIds = it.split(",").map(String::trim).filter(String::isNotBlank)))
            }, label = { Text("Fallback providers (comma separated)") }, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = c.confidenceRequired, onCheckedChange = { local = local.copy(config = c.copy(confidenceRequired = it)); onSave(local) })
                Text("Require confidence indicator", Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = c.sourceVerificationRequired, onCheckedChange = { local = local.copy(config = c.copy(sourceVerificationRequired = it)); onSave(local) })
                Text("Require source verification", Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = c.confirmationRequired, onCheckedChange = { local = local.copy(config = c.copy(confirmationRequired = it)); onSave(local) })
                Text("Require action confirmation", Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = c.permissionRequired, onCheckedChange = { local = local.copy(config = c.copy(permissionRequired = it)); onSave(local) })
                Text("Require device permission", Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = c.maintenanceMode, onCheckedChange = { local = local.copy(config = c.copy(maintenanceMode = it)); onSave(local) })
                Text("Maintenance mode", Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = c.backgroundAllowed, onCheckedChange = { local = local.copy(config = c.copy(backgroundAllowed = it)); onSave(local) })
                Text("Background execution allowed", Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = c.fallbackEnabled, onCheckedChange = { local = local.copy(config = c.copy(fallbackEnabled = it)); onSave(local) })
                Text("Automatic fallback enabled", Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = c.notificationEnabled, onCheckedChange = { local = local.copy(config = c.copy(notificationEnabled = it)); onSave(local) })
                Text("Notifications enabled", Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = c.auditRequired, onCheckedChange = { local = local.copy(config = c.copy(auditRequired = it)); onSave(local) })
                Text("Audit logging required", Modifier.padding(top = 12.dp))
            }

            Text("Tap Save after editing numeric/text fields.", style = MaterialTheme.typography.labelSmall)
            AssistChip(onClick = { onSave(local) }, label = { Text("Save all settings") })
        }
    }
}

@Composable
private fun AdminNumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}
