package com.bqsqwad.ai.ui

import android.content.Intent
import android.net.Uri

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import com.bqsqwad.ai.core.ApiConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BusinessWorkspaceScreen(onOpenPlans: () -> Unit = {}) {
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

    val enabledGroups = remember(data.toString()) {
        val groups = data.optJSONArray("enabledFeatureGroups") ?: JSONArray()
        buildSet<String> { for (i in 0 until groups.length()) add(groups.optString(i)) }
    }
    val tabs = buildList {
        add("Overview")
        add("Team")
        if ("analytics" in enabledGroups) add("Analytics")
        if ("assistant" in enabledGroups) {
            add("Plan Builder")
            add("Assistants")
        }
        if ("knowledge" in enabledGroups) {
            add("Knowledge")
            add("FAQs")
            add("Vault")
        }
        if ("crm" in enabledGroups) {
            add("Contacts")
            add("Leads")
            add("Pipeline")
            add("Appointments")
            add("Lead Capture")
        }
        if ("channels" in enabledGroups) {
            add("Inbox")
            add("Channels")
        }
        if ("automation" in enabledGroups) add("Automation")
        if ("advanced" in enabledGroups) {
            add("Advanced AI")
            add("Integrations")
            add("Enterprise")
        }
        add("Settings")
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val planContext = data.optJSONObject("planContext") ?: JSONObject()
                val planId = data.optString("planId").ifBlank { planContext.optString("id") }
                val planName = planContext.optString("name").ifBlank { "Business Workspace" }
                val planSubtitle = when (planId) {
                    "bizstarter" -> "A focused AI workspace for getting your business online and organized."
                    "bizbasic" -> "A richer knowledge and assistant workspace for growing teams."
                    "bizstandard" -> "A connected CRM and communications workspace for active businesses."
                    "bizprofessional" -> "A full operations workspace with channels, roles and workflow automation."
                    "bizbusiness" -> "An advanced AI operations workspace with voice, documents, integrations and governance."
                    "bizenterprise" -> "An enterprise-scale omnichannel workspace for departments, agents and advanced operations."
                    "businessenterprise" -> "A custom enterprise workspace configured around your organization."
                    else -> "AI, customers, team, knowledge, channels and automation in one connected workspace."
                }
                Text(
                    planName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    data.optString("name").ifBlank { "My Business" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(planSubtitle, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { tab = "Overview" }, label = { Text("Dashboard") })
                    AssistChip(onClick = { tab = "Settings" }, label = { Text("Settings") })
                    AssistChip(onClick = { tab = "Inbox" }, label = { Text("Inbox") })
                    AssistChip(onClick = { tab = "Channels" }, label = { Text("Channels") })
                    AssistChip(onClick = { tab = "Advanced AI" }, label = { Text("AI") })
                }
                val features = planContext.optJSONArray("features") ?: JSONArray()
                if (features.length() > 0) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items((0 until minOf(features.length(), 8)).map { features.optString(it) }) { feature ->
                            AssistChip(onClick = {}, label = { Text(feature) })
                        }
                    }
                }
                if (planContext.optBoolean("adminTest", false)) {
                    Text(
                        "Authorized workspace access is active.",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        Text("Workspace tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
            items(tabs) { label ->
                FilterChip(selected = tab == label, onClick = { tab = label }, label = { Text(label) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null && data.length() == 0 && !loading) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Business plan access required", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "A verified Business entitlement is required to use these workspace tools.",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(onClick = onOpenPlans) { Text("View Business Plans") }
                    TextButton(onClick = { load() }) { Text("Check access again") }
                }
            }
        } else {
            error?.let { SurfaceCard("Workspace error", it) }
        }

        when (tab) {
            "Overview" -> {
                SurfaceCard("Workspace", data.optString("name").ifBlank { "My Business" })
                val planContext = data.optJSONObject("planContext") ?: JSONObject()
                val planName = planContext.optString("name").ifBlank { data.optString("planId").ifBlank { "Business Starter" } }
                SurfaceCard(
                    "Business Dashboard",
                    buildString {
                        append(planName)
                        if (planContext.optBoolean("popular", false)) append(" • Popular")
                        if (planContext.optBoolean("trialEligible", false) && planContext.optInt("trialDays", 0) > 0) {
                            append(" • " + planContext.optInt("trialDays") + "-day trial eligible")
                        }
                        val expires = planContext.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" }
                        if (expires != null) append(" • Access through " + expires)
                        if (planContext.optBoolean("adminTest", false)) append(" • Authorized access")
                    }
                )
                val limits = planContext.optJSONObject("limits") ?: JSONObject()
                if (limits.length() > 0) {
                    Text("Plan limits & included capacity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    limits.keys().forEach { key ->
                        Text(key.replace('_', ' ').replaceFirstChar { it.uppercase() } + ": " + limits.optString(key), style = MaterialTheme.typography.bodySmall)
                    }
                }
                val planFeatures = planContext.optJSONArray("features") ?: JSONArray()
                if (planFeatures.length() > 0) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 2.dp
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Included in this plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            for (i in 0 until planFeatures.length()) {
                                val feature = planFeatures.optString(i)
                                if (feature.isNotBlank()) Text("• " + feature, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
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
                var timezone by remember(settings.toString()) { mutableStateOf(settings.optString("timezone").ifBlank { "UTC" }) }
                Text("Business AI settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("These settings are persisted in the business workspace and apply to its AI and automation behavior.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = timezone,
                    onValueChange = { timezone = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Workspace timezone") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { api.updateBusinessSettings(data.optString("workspaceId"), timezone = timezone) }
                                .onSuccess { data = it }
                                .onFailure { error = it.message ?: "Timezone could not be saved." }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save timezone") }
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
    var mediaUrl by remember { mutableStateOf("") }
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
            "Connect YouTube, X, or TikTok securely, then publish and manage the connected account from this workspace. TikTok profile/video access and approved Content Posting are available; unsupported TikTok messaging/comment/like APIs are not exposed as fake controls.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("X privacy & control", fontWeight = FontWeight.SemiBold)
                Text(
                    "Before connecting X: BQ Sqwad AI uses only the X permissions you approve for this connection. It does not ask for your X password, sell your X data, or use your X account for unrelated purposes.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "What BQ Sqwad AI may do: read the public X account identity needed to identify the connected account, publish posts when you explicitly choose Publish or use an automation you configured, and perform an action you explicitly request such as reply, repost, like, or delete when that permission is granted.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "What it will not do: read your X password, access private messages or unrelated private account data, publish silently without an action or configured automation, sell your X data, or use the connection for unrelated advertising.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Access and refresh tokens are encrypted server-side and are never stored in the Android app. Disconnecting deletes the stored connection and attempts remote OAuth revocation. If X reports a revocation failure, the local connection is still removed; you can also revoke BQ Sqwad AI from your X account settings.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "X is not used as a live-broadcast destination in BQ Sqwad AI. This connection is for authorized social publishing and account actions. Live broadcasting remains on supported live-ingest destinations such as YouTube.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

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
                                "${ApiConfig.baseUrl}/social/oauth/callback",
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
                                "${ApiConfig.baseUrl}/social/oauth/callback",
                                workspaceId
                            )
                        }.onSuccess { result ->
                            val url = result.optString("authorizationUrl")
                            if (url.isBlank()) error("X OAuth URL was not returned.")
                            else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { error(it.message ?: "Could not open X sign-in.") }
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

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = connecting == null,
                onClick = {
                    connecting = "tiktok"
                    scope.launch {
                        runCatching {
                            api.startSocialOAuth(
                                "tiktok",
                                "${ApiConfig.baseUrl}/social/oauth/callback",
                                workspaceId
                            )
                        }.onSuccess { result ->
                            val url = result.optString("authorizationUrl")
                            if (url.isBlank()) error("TikTok OAuth URL was not returned.")
                            else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { error(it.message ?: "Could not open TikTok authorization.") }
                        }.onFailure { error(it.message ?: "TikTok connection failed.") }
                        delay(1500)
                        repeat(20) {
                            refreshAccounts()
                            if ((0 until accounts.length()).any { i -> accounts.optJSONObject(i)?.optString("platform") == "tiktok" }) return@repeat
                            delay(1500)
                        }
                        connecting = null
                    }
                }
            ) { Text(if (connecting == "tiktok") "Connecting…" else "Connect TikTok") }
        }

        if (accounts.length() == 0) {
            SurfaceCard("No connected channels", "Connect YouTube, X, or TikTok above to add a channel.")
        }

        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            val id = a.optString("id")
            val platform = a.optString("platform")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { accountId = id }) {
                    Text(platform.replaceFirstChar { it.uppercase() } + if (accountId == id) " • Selected" else "")
                }
                if (platform == "x" || platform == "tiktok") {
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { api.disconnectSocialAccount(id) }
                                .onSuccess {
                                    if (accountId == id) accountId = ""
                                    refreshAccounts()
                                }
                                .onFailure { error(it.message ?: "Social account could not be disconnected.") }
                        }
                    }) { Text("Disconnect") }
                }
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Post text / caption") }
        )
        OutlinedTextField(
            value = mediaUrl,
            onValueChange = { mediaUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Media URL (required for TikTok/YouTube video publishing)") },
            supportingText = { Text("Use an HTTPS media URL that the connected provider accepts.") }
        )
        Button(
            enabled = accountId.isNotBlank() && text.isNotBlank(),
            onClick = {
                scope.launch {
                    runCatching { api.publishSocial(accountId, text, mediaUrl.takeIf { it.isNotBlank() }) }
                        .onFailure { error(it.message ?: "Publishing failed.") }
                        .onSuccess { text = ""; mediaUrl = "" }
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
    val context = LocalContext.current
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

        var tiktokAccounts by remember { mutableStateOf(JSONArray()) }
        var selectedTikTok by remember { mutableStateOf("") }
        var tiktokTitle by remember { mutableStateOf("") }
        var tiktokMediaUrl by remember { mutableStateOf("") }
        var tiktokBusy by remember { mutableStateOf(false) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("TikTok AI connection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Connect TikTok through OAuth. Credentials remain server-side and the Business workspace plan gate remains enforced.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !tiktokBusy, onClick = {
                        tiktokBusy = true
                        scope.launch {
                            runCatching { api.startSocialOAuth("tiktok", "${ApiConfig.baseUrl}/social/oauth/callback", workspaceId) }
                                .onSuccess { result ->
                                    val url = result.optString("authorizationUrl")
                                    if (url.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                                .onFailure { error(it.message ?: "TikTok connection could not start.") }
                            tiktokBusy = false
                        }
                    }) { Text("Connect TikTok") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { api.socialAccounts(workspaceId) }.onSuccess { body ->
                                val all = body.optJSONArray("accounts") ?: JSONArray()
                                val filtered = JSONArray()
                                for (j in 0 until all.length()) {
                                    val account = all.optJSONObject(j)
                                    if (account?.optString("platform") == "tiktok") filtered.put(account)
                                }
                                tiktokAccounts = filtered
                                if (selectedTikTok.isBlank() && filtered.length() > 0) selectedTikTok = filtered.optJSONObject(0)?.optString("id").orEmpty()
                            }.onFailure { error(it.message ?: "TikTok accounts could not be loaded.") }
                        }
                    }) { Text("Refresh") }
                }
                if (tiktokAccounts.length() == 0) {
                    Text("No TikTok account connected to this Business workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    for (j in 0 until tiktokAccounts.length()) {
                        val account = tiktokAccounts.optJSONObject(j) ?: continue
                        val id = account.optString("id")
                        FilterChip(selected = selectedTikTok == id, onClick = { selectedTikTok = id }, label = { Text("TikTok account") })
                    }
                    OutlinedTextField(value = tiktokTitle, onValueChange = { tiktokTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("AI post title") })
                    OutlinedTextField(value = tiktokMediaUrl, onValueChange = { tiktokMediaUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Video URL") })
                    Button(enabled = selectedTikTok.isNotBlank() && tiktokTitle.isNotBlank() && tiktokMediaUrl.isNotBlank() && !tiktokBusy, onClick = {
                        tiktokBusy = true
                        scope.launch {
                            runCatching { api.publishTikTok(selectedTikTok, workspaceId, tiktokMediaUrl, tiktokTitle) }
                                .onFailure { error(it.message ?: "TikTok publishing failed.") }
                            tiktokBusy = false
                        }
                    }) { Text(if (tiktokBusy) "Working…" else "AI publish to TikTok") }
                    Text("Profile/video access and Direct Post use approved TikTok scopes. Other actions are enabled only when TikTok provides and approves the required API permission.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

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
                            if (requiresCredentials) "Configured securely for this workspace." else "Available without adding a provider API key."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(if (isConfigured) "Connected" else if (noKey) "Available" else "Not configured yet")
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
