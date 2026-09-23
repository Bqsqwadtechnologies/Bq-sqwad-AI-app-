package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
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

    val tabs = listOf("Overview","Team","Assistants","Knowledge","FAQs","Vault","Contacts","Leads","Pipeline","Appointments","Inbox","Automation","Lead Capture","Channels")
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
                Button(onClick = { load() }, enabled = !loading) { Text("Refresh") }
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
            "Channels" -> {
                SocialChannelsPanel(api, error = { error = it })
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
private fun SocialChannelsPanel(api: WorkspaceApiClient, error: (String) -> Unit) {
    var accounts by remember { mutableStateOf(JSONArray()) }
    var text by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        runCatching { api.socialAccounts() }
            .onSuccess { accounts = it.optJSONArray("accounts") ?: JSONArray() }
            .onFailure { error(it.message ?: "Social channels could not be loaded.") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connected Channels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Connect supported social accounts through the secure OAuth flow, then publish from one workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (accounts.length() == 0) SurfaceCard("No connected channels", "Connect a social account from the account/OAuth flow.")
        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            val id = a.optString("id")
            OutlinedButton(onClick = { accountId = id }) {
                Text(a.optString("platform").replaceFirstChar { it.uppercase() } + if (accountId == id) " • Selected" else "")
            }
        }
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Post text") })
        Button(enabled = accountId.isNotBlank() && text.isNotBlank(), onClick = {
            scope.launch {
                runCatching { api.publishSocial(accountId, text) }
                    .onFailure { error(it.message ?: "Publishing failed.") }
                    .onSuccess { text = "" }
            }
        }) { Text("Publish") }
    }
}

