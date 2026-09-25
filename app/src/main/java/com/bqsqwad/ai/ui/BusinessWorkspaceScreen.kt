package com.bqsqwad.ai.ui

import android.content.Intent
import android.net.Uri

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BusinessWorkspaceScreen() {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Overview") }
    var data by remember { mutableStateOf(JSONObject()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var extended by remember { mutableStateOf(JSONObject()) }

    fun load() {
        loading = true; error = null
        scope.launch {
            runCatching { api.businessWorkspace() }
                .onSuccess { data = it }
                .onFailure { error = it.message ?: "Business workspace could not be loaded." }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load(); scope.launch { runCatching { api.businessExtended() }.onSuccess { extended = it } } }

    val tabs = listOf("Overview","Analytics","Plan Builder","Team","Assistants","Knowledge","FAQs","Vault","Contacts","Leads","Pipeline","Appointments","Inbox","Automation","Lead Capture","Channels","Advanced AI","Integrations","Enterprise","Settings")
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Business AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Workspace, knowledge and CRM data are isolated to the authenticated business workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0)) {
            tabs.forEach { label -> Tab(tab == label, { tab = label }, text = { Text(label) }) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { SurfaceCard("Error", it) }

        when (tab) {
            "Overview" -> {
                SurfaceCard("Workspace", data.optString("name").ifBlank { "My Business" })
                SurfaceCard("Plan", data.optString("planId").ifBlank { "bizstarter" })
                val a = data.optJSONObject("analytics") ?: JSONObject()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { SurfaceCard("Members", a.optInt("members").toString()) }
                    Column(Modifier.weight(1f)) { SurfaceCard("Knowledge", a.optInt("knowledge").toString()) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { SurfaceCard("Contacts", a.optInt("contacts").toString()) }
                    Column(Modifier.weight(1f)) { SurfaceCard("Leads", a.optInt("leads").toString()) }
                }
                val warnings = data.optJSONArray("usageWarnings") ?: JSONArray()
                if (warnings.length() > 0) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Usage needs attention", fontWeight = FontWeight.Bold)
                            for (i in 0 until warnings.length()) {
                                val w = warnings.optJSONObject(i) ?: continue
                                Text(w.optString("message"))
                            }
                            Text("Review eligible add-ons or upgrade the Business plan before the billing period ends.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val addOns = data.optJSONArray("recommendedAddOns") ?: JSONArray()
                if (addOns.length() > 0) {
                    SurfaceCard("Recommended add-ons", "Available from the server catalogue for your current plan.")
                    for (i in 0 until minOf(addOns.length(), 6)) {
                        val addOn = addOns.optJSONObject(i) ?: continue
                        SurfaceCard(addOn.optString("name"), addOn.optString("description").ifBlank { "Business capacity add-on" })
                    }
                }
                Button(onClick = { load() }, enabled = !loading) { Text("Refresh") }
            }
            "Analytics" -> {
                val a = data.optJSONObject("analytics") ?: JSONObject()
                Text("Business Analytics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Live workspace metrics from the server. No fabricated numbers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    "AI requests" to a.optInt("requests", 0),
                    "Team members" to a.optInt("members", 0),
                    "Assistants" to a.optInt("assistants", 0),
                    "Knowledge items" to a.optInt("knowledge", 0),
                    "Contacts" to a.optInt("contacts", 0),
                    "Leads" to a.optInt("leads", 0),
                    "Pipeline records" to a.optInt("pipelines", 0),
                    "Appointments" to a.optInt("appointments", 0),
                    "Inbox items" to a.optInt("inbox", 0),
                    "Automations" to a.optInt("automations", 0)
                ).forEach { (label, value) -> SurfaceCard(label, value.toString()) }
            }
            "Plan Builder" -> {
                BusinessPlanBuilderPanel(api, data.optString("workspaceId"), { error = it })
            }
            "Team" -> {
                val members = data.optJSONArray("members") ?: JSONArray()
                MemberForm(api, data.optString("workspaceId"), saving, { saving = it }, { load() }, { error = it })
                Records(members, "Team member")
            }
            "Assistants" -> {
                RecordCreateForm("Assistant", listOf("name","instructions")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessRecord("assistant", JSONObject().put("workspaceId", data.optString("workspaceId")).put("name", values[0]).put("instructions", values[1])) }
                            .onFailure { error = it.message ?: "Assistant could not be created." }
                            .onSuccess { load() }
                        saving = false
                    }
                }
                Records(data.optJSONArray("assistants") ?: JSONArray(), "Assistant")
            }
            "Knowledge" -> {
                RecordCreateForm("Knowledge item", listOf("title","content")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessRecord("knowledge", JSONObject().put("workspaceId", data.optString("workspaceId")).put("title", values[0]).put("content", values[1]).put("type","note")) }
                            .onFailure { error = it.message ?: "Knowledge could not be saved." }
                            .onSuccess { load() }
                        saving = false
                    }
                }
                Records(data.optJSONArray("knowledge") ?: JSONArray(), "Knowledge")
            }
            "FAQs" -> {
                RecordCreateForm("FAQ", listOf("question","answer")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessExtended("faq", JSONObject().put("workspaceId", data.optString("workspaceId")).put("question", values[0]).put("answer", values[1])) }
                            .onFailure { error = it.message ?: "FAQ could not be saved." }
                            .onSuccess { extended = api.businessExtended(data.optString("workspaceId")) }
                        saving = false
                    }
                }
                Records(extended.optJSONArray("faqs") ?: JSONArray(), "FAQ")
            }
            "Vault" -> {
                RecordCreateForm("Business Vault", listOf("title","content")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessExtended("vault", JSONObject().put("workspaceId", data.optString("workspaceId")).put("title", values[0]).put("content", values[1])) }
                            .onFailure { error = it.message ?: "Vault item could not be saved." }
                            .onSuccess { extended = api.businessExtended(data.optString("workspaceId")) }
                        saving = false
                    }
                }
                Records(extended.optJSONArray("vault") ?: JSONArray(), "Vault")
            }
            "Inbox" -> {
                RecordCreateForm("Inbox message", listOf("sender","channel","message")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessExtended("inbox", JSONObject().put("workspaceId", data.optString("workspaceId")).put("sender", values[0]).put("channel", values[1]).put("message", values[2])) }
                            .onFailure { error = it.message ?: "Inbox message could not be saved." }
                            .onSuccess { extended = api.businessExtended(data.optString("workspaceId")) }
                        saving = false
                    }
                }
                Records(extended.optJSONArray("inbox") ?: JSONArray(), "Inbox")
            }
            "Automation" -> {
                RecordCreateForm("Automation", listOf("name")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessExtended("automation", JSONObject().put("workspaceId", data.optString("workspaceId")).put("name", values[0]).put("enabled", true)) }
                            .onFailure { error = it.message ?: "Automation could not be saved." }
                            .onSuccess { extended = api.businessExtended(data.optString("workspaceId")) }
                        saving = false
                    }
                }
                val automations = extended.optJSONArray("automations") ?: JSONArray()
                Records(automations, "Automation")
                if (automations.length() > 0) {
                    Button(onClick = {
                        val id = automations.optJSONObject(0)?.optString("id").orEmpty()
                        if (id.isNotBlank()) {
                            scope.launch { runCatching { api.runBusinessAutomation(id, JSONObject().put("source", "native_test").put("workspaceId", data.optString("workspaceId"))) }
                                .onFailure { error = it.message ?: "Automation could not run." } }
                        }
                    }) { Text("Run selected automation") }
                }
            }
            "Advanced AI" -> {
                AdvancedAiPanel(api, data.optString("workspaceId"), { error = it })
            }
            "Integrations" -> {
                BusinessIntegrationsPanel(api, data.optString("workspaceId"), { error = it })
            }
            "Enterprise" -> {
                BusinessEnterprisePanel(api, data.optString("workspaceId"), { error = it })
            }
            "Settings" -> {
                val settings = data.optJSONObject("settings") ?: JSONObject()
                var assistantEnabled by remember(settings.toString()) { mutableStateOf(settings.optBoolean("assistantEnabled", true)) }
                var automationEnabled by remember(settings.toString()) { mutableStateOf(settings.optBoolean("automationEnabled", true)) }
                var notificationsEnabled by remember(settings.toString()) { mutableStateOf(settings.optBoolean("notificationsEnabled", true)) }
                var webSearchEnabled by remember(settings.toString()) { mutableStateOf(settings.optBoolean("webSearchEnabled", true)) }
                Text("Business AI settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("These settings are persisted in the business workspace and apply to its AI and automation behavior.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    Triple("Business AI enabled", assistantEnabled) { next: Boolean -> assistantEnabled = next; scope.launch { runCatching { api.updateBusinessSettings(data.optString("workspaceId"), assistantEnabled = next) }.onSuccess { data = it }.onFailure { error = it.message } }; Unit },
                    Triple("Automation enabled", automationEnabled) { next: Boolean -> automationEnabled = next; scope.launch { runCatching { api.updateBusinessSettings(data.optString("workspaceId"), automationEnabled = next) }.onSuccess { data = it }.onFailure { error = it.message } }; Unit },
                    Triple("Notifications enabled", notificationsEnabled) { next: Boolean -> notificationsEnabled = next; scope.launch { runCatching { api.updateBusinessSettings(data.optString("workspaceId"), notificationsEnabled = next) }.onSuccess { data = it }.onFailure { error = it.message } }; Unit },
                    Triple("Web Search for business workflows", webSearchEnabled) { next: Boolean -> webSearchEnabled = next; scope.launch { runCatching { api.updateBusinessSettings(data.optString("workspaceId"), webSearchEnabled = next) }.onSuccess { data = it }.onFailure { error = it.message } }; Unit }
                ).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.first)
                        Switch(row.second, onCheckedChange = row.third)
                    }
                }
            }
            "Channels" -> {
                SocialChannelsPanel(api, data.optString("workspaceId"), error = { error = it })
            }
            "Lead Capture" -> {
                RecordCreateForm("Lead capture form", listOf("name","source")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessExtended("leadCapture", JSONObject().put("workspaceId", data.optString("workspaceId")).put("name", values[0]).put("source", values[1])) }
                            .onFailure { error = it.message ?: "Lead capture form could not be created." }
                            .onSuccess { extended = api.businessExtended(data.optString("workspaceId")) }
                        saving = false
                    }
                }
                Records(extended.optJSONArray("leadCaptures") ?: JSONArray(), "Lead capture")
            }
            "Contacts" -> {
                RecordCreateForm("Contact", listOf("name","email","phone","company")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessRecord("contact", JSONObject().put("workspaceId", data.optString("workspaceId")).put("name", values[0]).put("email", values[1]).put("phone", values[2]).put("company", values[3])) }
                            .onFailure { error = it.message ?: "Contact could not be saved." }
                            .onSuccess { load() }
                        saving = false
                    }
                }
                Records(data.optJSONArray("contacts") ?: JSONArray(), "Contact")
            }
            "Leads" -> {
                RecordCreateForm("Lead", listOf("name","email","phone","notes")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessRecord("lead", JSONObject().put("workspaceId", data.optString("workspaceId")).put("name", values[0]).put("email", values[1]).put("phone", values[2]).put("notes", values[3]).put("status","new")) }
                            .onFailure { error = it.message ?: "Lead could not be saved." }
                            .onSuccess { load() }
                        saving = false
                    }
                }
                Records(data.optJSONArray("leads") ?: JSONArray(), "Lead")
            }
            "Pipeline" -> {
                RecordCreateForm("Pipeline", listOf("name")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessRecord("pipeline", JSONObject().put("workspaceId", data.optString("workspaceId")).put("name", values[0])) }
                            .onFailure { error = it.message ?: "Pipeline could not be created." }
                            .onSuccess { load() }
                        saving = false
                    }
                }
                Records(data.optJSONArray("pipelines") ?: JSONArray(), "Pipeline")
            }
            "Appointments" -> {
                RecordCreateForm("Appointment", listOf("title","contactId","startAt","endAt","notes")) { values ->
                    saving = true
                    scope.launch {
                        runCatching { api.createBusinessRecord("appointment", JSONObject().put("workspaceId", data.optString("workspaceId")).put("title", values[0]).put("contactId", values[1]).put("startAt", values[2]).put("endAt", values[3]).put("notes", values[4]).put("status","scheduled")) }
                            .onFailure { error = it.message ?: "Appointment could not be created." }
                            .onSuccess { load() }
                        saving = false
                    }
                }
                Records(data.optJSONArray("appointments") ?: JSONArray(), "Appointment")
            }
        }
    }
}

@Composable
private fun MemberForm(api: WorkspaceApiClient, workspaceId: String, saving: Boolean, setSaving: (Boolean)->Unit, reload: ()->Unit, setError: (String)->Unit) {
    var uid by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("member") }
    val scope = rememberCoroutineScope()
    OutlinedTextField(uid, { uid = it }, label = { Text("Member Firebase UID") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(role, { role = it }, label = { Text("Role: viewer/member/manager/admin") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = uid.isNotBlank() && !saving, onClick = {
        setSaving(true)
        scope.launch {
            runCatching { api.addBusinessMember(workspaceId, uid.trim(), role.trim()) }
                .onFailure { setError(it.message ?: "Member could not be added.") }
                .onSuccess { uid = ""; reload() }
            setSaving(false)
        }
    }) { Text(if (saving) "Saving…" else "Add member") }
}

@Composable
private fun RecordCreateForm(title: String, fields: List<String>, onSave: (List<String>)->Unit) {
    val values = remember(fields) { fields.map { mutableStateOf("") } }
    SurfaceCard(title, "Create and store a real workspace record.")
    fields.forEachIndexed { i, field ->
        OutlinedTextField(values[i].value, { values[i].value = it }, label = { Text(field.replaceFirstChar { it.uppercase() }) }, modifier = Modifier.fillMaxWidth(), minLines = if (field in listOf("content","instructions","notes")) 3 else 1)
    }
    Button(enabled = values.all { it.value.isNotBlank() }, onClick = { onSave(values.map { it.value.trim() }) }) { Text("Save $title") }
}

@Composable
private fun Records(array: JSONArray, title: String) {
    if (array.length() == 0) SurfaceCard(title, "No records yet.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        (0 until minOf(array.length(), 50)).forEach { i ->
            SurfaceCard(title, (array.optJSONObject(i) ?: JSONObject()).toString())
        }
    }
}
@Composable
private fun SocialChannelsPanel(api: WorkspaceApiClient, workspaceId: String, error: (String) -> Unit) {
    var accounts by remember { mutableStateOf(JSONArray()) }
    var text by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun refreshAccounts() {
        runCatching { api.socialAccounts(workspaceId) }
            .onSuccess {
                accounts = it.optJSONArray("accounts") ?: JSONArray()
                if (accountId.isNotBlank() &&
                    (0 until accounts.length()).none { i -> accounts.optJSONObject(i)?.optString("id") == accountId }
                ) {
                    accountId = ""
                }
            }
            .onFailure { error(it.message ?: "Social channels could not be loaded.") }
    }

    LaunchedEffect(workspaceId) {
        refreshAccounts()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connected Channels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Connect YouTube or X securely, then publish and manage the connected account from this workspace.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = connecting == null,
                onClick = {
                    connecting = "youtube"
                    scope.launch {
                        runCatching {
                            api.startSocialOAuth(
                                "youtube",
                                "https://europe-west1-bqsqwadai.cloudfunctions.net/bqSqwadApi/social/oauth/callback",
                                workspaceId
                            )
                        }.onSuccess { result ->
                            val url = result.optString("authorizationUrl")
                            if (url.isBlank()) error("YouTube OAuth URL was not returned.")
                            else {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                }.onFailure { error(it.message ?: "Could not open YouTube sign-in.") }
                            }
                        }.onFailure { error(it.message ?: "YouTube connection failed.") }
                        delay(1500)
                        repeat(20) {
                            refreshAccounts()
                            if ((0 until accounts.length()).any { i -> accounts.optJSONObject(i)?.optString("platform") == "youtube" }) return@repeat
                            delay(1500)
                        }
                        connecting = null
                    }
                }
            ) { Text(if (connecting == "youtube") "Connecting…" else "Connect YouTube") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = connecting == null,
                onClick = {
                    connecting = "x"
                    scope.launch {
                        runCatching {
                            api.startSocialOAuth(
                                "x",
                                "https://europe-west1-bqsqwadai.cloudfunctions.net/bqSqwadApi/social/oauth/callback",
                                workspaceId
                            )
                        }.onSuccess { result ->
                            val url = result.optString("authorizationUrl")
                            if (url.isBlank()) error("X OAuth URL was not returned.")
                            else {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                }.onFailure { error(it.message ?: "Could not open X sign-in.") }
                            }
                        }.onFailure { error(it.message ?: "X connection failed.") }
                        delay(1500)
                        repeat(20) {
                            refreshAccounts()
                            if ((0 until accounts.length()).any { i -> accounts.optJSONObject(i)?.optString("platform") == "x" }) return@repeat
                            delay(1500)
                        }
                        connecting = null
                    }
                }
            ) { Text(if (connecting == "x") "Connecting…" else "Connect X") }
        }

        if (accounts.length() == 0) {
            SurfaceCard("No connected channels", "Connect YouTube or X above to add a channel.")
        }

        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            val id = a.optString("id")
            OutlinedButton(onClick = { accountId = id }) {
                Text(
                    a.optString("platform").replaceFirstChar { it.uppercase() } +
                        if (accountId == id) " • Selected" else ""
                )
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Post text") }
        )
        Button(
            enabled = accountId.isNotBlank() && text.isNotBlank(),
            onClick = {
                scope.launch {
                    runCatching { api.publishSocial(accountId, text) }
                        .onFailure { error(it.message ?: "Publishing failed.") }
                        .onSuccess { text = "" }
                }
            }
        ) { Text("Publish") }
    }
}

@Composable
private fun AdvancedAiPanel(api: WorkspaceApiClient, workspaceId: String, error: (String)->Unit) {
    var prompt by remember { mutableStateOf("") }
    var imageBase64 by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Advanced AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Use the server-side AI gateway for document, image, OCR, translation and other configured AI capabilities.")
        OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Task / instruction") }, minLines = 3)
        Button(enabled = prompt.isNotBlank(), onClick = {
            scope.launch { runCatching { api.businessAdvancedAi(JSONObject().put("workspaceId", workspaceId).put("capability","chat").put("message",prompt)) }
                .onSuccess { result = it.optString("text") }.onFailure { error(it.message ?: "Advanced AI failed.") } }
        }) { Text("Run AI task") }
        if (result.isNotBlank()) SurfaceCard("Result", result)
    }
}

@Composable
private fun BusinessIntegrationsPanel(api: WorkspaceApiClient, workspaceId: String, error: (String)->Unit) {
    val scope = rememberCoroutineScope()
    var integrations by remember { mutableStateOf(JSONArray()) }
    var configured by remember { mutableStateOf(JSONArray()) }
    var automations by remember { mutableStateOf(JSONArray()) }

    fun reload() {
        scope.launch {
            runCatching { api.businessIntegrations(workspaceId) }
                .onSuccess {
                    integrations = it.optJSONArray("integrations") ?: JSONArray()
                    configured = it.optJSONArray("configured") ?: JSONArray()
                }
                .onFailure { error(it.message ?: "Integrations could not be loaded.") }
            runCatching { api.businessExtended(workspaceId) }
                .onSuccess { automations = it.optJSONArray("automations") ?: JSONArray() }
        }
    }

    LaunchedEffect(workspaceId) { reload() }

    val configuredMap = remember(configured.toString()) {
        buildMap<String, Boolean> {
            for (i in 0 until configured.length()) {
                val item = configured.optJSONObject(i) ?: continue
                put(item.optString("integration"), item.optBoolean("enabled"))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Business Integrations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Integrations are connected through the Business backend. Provider credentials never belong in the Android app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        for (i in 0 until integrations.length()) {
            val item = integrations.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val requiresCredentials = item.optBoolean("requiresProviderCredentials", true)
            val isConfigured = configuredMap[id] == true
            val noKey = !requiresCredentials

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        id.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        item.optString("description").ifBlank {
                            if (requiresCredentials) "Configured securely by the administrator." else "Available without adding a provider API key."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(if (isConfigured) "Connected" else if (noKey) "Available" else "Waiting for admin configuration")
                            Text(
                                if (noKey) "No provider key required" else "Credentials are server-side",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (noKey) {
                            Switch(
                                checked = isConfigured,
                                onCheckedChange = { next ->
                                    scope.launch {
                                        runCatching {
                                            api.configureBusinessIntegration(
                                                JSONObject()
                                                    .put("workspaceId", workspaceId)
                                                    .put("integration", id)
                                                    .put("enabled", next)
                                            )
                                        }.onSuccess { reload() }
                                            .onFailure { error(it.message ?: "Integration could not be configured.") }
                                    }
                                }
                            )
                        }
                    }

                    if (id == "webhooks") {
                        Text("Webhook automations", fontWeight = FontWeight.SemiBold)
                        if (automations.length() == 0) {
                            Text("Create a Business automation with a webhook trigger to receive external events.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            for (j in 0 until minOf(automations.length(), 10)) {
                                val automation = automations.optJSONObject(j) ?: continue
                                val token = automation.optString("webhookToken")
                                if (token.isBlank()) continue
                                val endpoint = "https://europe-west1-bqsqwadai.cloudfunctions.net/bqSqwadApi/business/automations/webhook"
                                SurfaceCard(
                                    automation.optString("name").ifBlank { "Automation" },
                                    "POST $endpoint\nToken: $token"
                                )
                            }
                        }
                    }

                    if (id == "rest_api") {
                        SurfaceCard(
                            "Authenticated API",
                            "Use the existing Firebase-authenticated Business endpoints from your server or automation. No provider API key is stored in the app."
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun BusinessEnterprisePanel(api: WorkspaceApiClient, workspaceId: String, error: (String)->Unit) {
    var config by remember { mutableStateOf(JSONObject()) }
    LaunchedEffect(workspaceId) { runCatching { api.businessEnterprise(workspaceId) }.onSuccess { config = it.optJSONObject("config") ?: JSONObject() }.onFailure { error(it.message ?: "Enterprise configuration could not be loaded.") } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Enterprise Controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SurfaceCard("SSO", (config.optJSONObject("sso") ?: JSONObject()).optBoolean("enabled").toString())
        SurfaceCard("Audit Logs", (config.optJSONObject("auditLogs") ?: JSONObject()).optBoolean("enabled", true).toString())
        SurfaceCard("White Label", (config.optJSONObject("whiteLabel") ?: JSONObject()).optBoolean("enabled").toString())
        SurfaceCard("SLA", (config.optJSONObject("sla") ?: JSONObject()).optDouble("targetPercent", 99.9).toString() + "%")
        SurfaceCard("Security", "MFA, session and IP policy are admin-configurable.")
    }
}



@Composable
private fun BusinessPlanBuilderPanel(api: WorkspaceApiClient, workspaceId: String, error: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var brief by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Business Plan Builder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Generate a structured business plan from the company's supplied information. The AI must distinguish facts from assumptions and does not provide legal, tax, regulatory or financial guarantees.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = brief,
            onValueChange = { brief = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 7,
            label = { Text("Business brief") },
            placeholder = { Text("Describe the business, customers, goals, products/services, current situation and constraints.") }
        )
        Button(
            enabled = brief.isNotBlank() && !busy,
            onClick = {
                busy = true
                scope.launch {
                    runCatching { api.businessPlanBuilder(workspaceId, brief) }
                        .onSuccess { result = it.optString("answer") }
                        .onFailure { error(it.message ?: "Business plan could not be generated.") }
                    busy = false
                }
            }
        ) { Text(if (busy) "Generating…" else "Generate Business Plan") }
        if (result.isNotBlank()) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp
            ) {
                Text(result, Modifier.padding(16.dp))
            }
        }
    }
}
