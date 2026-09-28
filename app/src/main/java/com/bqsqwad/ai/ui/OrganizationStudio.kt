package com.bqsqwad.ai.ui

import android.content.Intent
import androidx.core.content.ContextCompat
import com.bqsqwad.ai.core.LiveMediaForegroundService
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ProductionSource(val id: Int, val name: String, val uri: Uri)

@Composable
fun OrganizationStudioWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { WorkspaceApiClient() }
    val sources = remember { mutableStateListOf<ProductionSource>() }
    var selectedSourceId by remember { mutableStateOf<Int?>(null) }
    var nextId by remember { mutableStateOf(1) }
    var organization by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var channelName by remember { mutableStateOf("") }
    var channelType by remember { mutableStateOf("chat") }
    var knowledgeText by remember { mutableStateOf("") }
    var memberId by remember { mutableStateOf("") }
    var memberRole by remember { mutableStateOf("member") }
    var assistantPrompt by remember { mutableStateOf("") }
    var assistantResult by remember { mutableStateOf("") }
    var directorPrompt by remember { mutableStateOf("") }
    var directorResult by remember { mutableStateOf("") }
    var outputStatus by remember { mutableStateOf("Program output idle") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var assistantEnabled by remember { mutableStateOf(true) }
    var sharedMemory by remember { mutableStateOf(false) }
    var sharedKnowledge by remember { mutableStateOf(false) }
    var production by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var cameraName by remember { mutableStateOf("") }
    var cameraEndpoint by remember { mutableStateOf("") }
    var cameraRole by remember { mutableStateOf("wide") }
    var destinationName by remember { mutableStateOf("") }
    var destinationType by remember { mutableStateOf("youtube") }
    var aiDirectorEnabled by remember { mutableStateOf(true) }
    var autoSwitchEnabled by remember { mutableStateOf(false) }
    var productionStatus by remember { mutableStateOf("idle") }
    var liveSessionId by remember { mutableStateOf<String?>(null) }
    var liveEndpoint by remember { mutableStateOf<String?>(null) }
    var scheduledName by remember { mutableStateOf("") }
    var scheduledPrompt by remember { mutableStateOf("") }
    var scheduledSchedule by remember { mutableStateOf("daily") }
    var scheduledTasks by remember { mutableStateOf(org.json.JSONArray()) }
    var connectedPlatforms by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var youtubeAccounts by remember { mutableStateOf(org.json.JSONArray()) }
    var selectedYoutubeAccountId by remember { mutableStateOf("") }
    var aiLiveMode by remember { mutableStateOf("host_only") }
    var aiLivePrivacyAccepted by remember { mutableStateOf(false) }
    var aiLiveViewerReplies by remember { mutableStateOf(false) }
    var aiLiveScreenAwareness by remember { mutableStateOf(false) }
    var aiLiveCreditsPerMinute by remember { mutableStateOf(0.25) }
    val youtubeChatLog = remember { mutableStateListOf<String>() }
    val processedYouTubeMessages = remember { mutableStateOf(emptySet<String>()) }

    fun applyOrganization(value: org.json.JSONObject) {
        organization = value
        name = value.optString("name").ifBlank { "My Organization" }
        description = value.optString("description")
        val settings = value.optJSONObject("settings")
        assistantEnabled = settings?.optBoolean("assistantEnabled", true) ?: true
        sharedMemory = settings?.optBoolean("sharedMemoryEnabled", false) ?: false
        sharedKnowledge = settings?.optBoolean("sharedKnowledgeEnabled", false) ?: false
    }

    fun refresh() {
        scope.launch {
            runCatching { api.organization() }
                .onSuccess { applyOrganization(it) }
                .onFailure { error = it.message ?: "Organization could not be loaded." }
        }
    }

    fun refreshProduction() {
        scope.launch {
            runCatching { api.organizationProduction() }
                .onSuccess {
                    production = it
                    val rules = it.optJSONObject("rules")
                    aiDirectorEnabled = it.optBoolean("aiDirectorEnabled", true)
                    autoSwitchEnabled = rules?.optBoolean("autoSwitchEnabled", false) ?: false
                    productionStatus = it.optString("status", "idle")
                }
                .onFailure { error = it.message ?: "Production configuration could not be loaded." }
        }
    }

    fun saveProductionConfig() {
        scope.launch {
            runCatching {
                api.updateOrganizationProductionConfig(org.json.JSONObject()
                    .put("autoSwitchEnabled", autoSwitchEnabled)
                    .put("minimumConfidence", 0.8)
                    .put("fallbackEnabled", true)
                    .put("operatorOverride", true)
                    .put("holdLastValidOutput", true)
                )
            }.onSuccess { production = it }
             .onFailure { error = it.message ?: "Production rules could not be saved." }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        refreshProduction()
        runCatching { api.organizationScheduledTasks() }
            .onSuccess { scheduledTasks = it.optJSONArray("tasks") ?: org.json.JSONArray() }
            .onFailure { error = it.message ?: "Scheduled AI tasks could not be loaded." }
        runCatching {
            val orgId = organization?.optString("uid").orEmpty().ifBlank { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }
            if (orgId.isBlank()) error("organization_id_missing")
            api.organizationYouTubeAccounts(orgId)
        }.onSuccess { body ->
            val accounts = body.optJSONArray("accounts") ?: org.json.JSONArray()
            youtubeAccounts = accounts
            if (selectedYoutubeAccountId.isBlank() && accounts.length() > 0) {
                selectedYoutubeAccountId = accounts.optJSONObject(0)?.optString("id").orEmpty()
            }
            connectedPlatforms = connectedPlatforms.toMutableMap().apply { this["youtube"] = accounts.length() > 0 }
        }.onFailure { /* disconnected status remains fail-closed */ }

        runCatching { api.organizationAiLiveConfig() }
            .onSuccess {
                aiLiveMode = it.optString("mode", "host_only")
                aiLivePrivacyAccepted = it.optBoolean("privacyAccepted", false)
                aiLiveViewerReplies = it.optBoolean("viewerReplies", false)
                aiLiveScreenAwareness = it.optBoolean("screenAwareness", false)
                aiLiveCreditsPerMinute = it.optDouble("creditRatePerMinute", 0.25)
            }
            .onFailure { /* first-run defaults remain safe */ }
    }

    LaunchedEffect(liveSessionId, selectedYoutubeAccountId, aiLiveMode) {
        if (liveSessionId.isNullOrBlank() || selectedYoutubeAccountId.isBlank() || aiLiveMode == "host_only") return@LaunchedEffect
        val organizationId = organization?.optString("uid").orEmpty().ifBlank { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }
        while (liveSessionId != null) {
            runCatching {
                val session = api.organizationProduction()
                val live = session.optString("status")
                if (live != "idle") {
                    val chatId = session.optString("liveChatId")
                    if (chatId.isNotBlank()) {
                        val body = api.organizationYouTubeLiveChat(organizationId, selectedYoutubeAccountId, chatId)
                        val items = body.optJSONArray("items") ?: org.json.JSONArray()
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val id = item.optString("id")
                            if (id.isBlank() || processedYouTubeMessages.value.contains(id)) continue
                            processedYouTubeMessages.value = processedYouTubeMessages.value + id
                            val author = item.optString("author", "Viewer")
                            val text = item.optString("text")
                            youtubeChatLog.add("$author: $text")
                            if (body.optBoolean("viewerReplies", false) && text.isNotBlank()) {
                                runCatching {
                                    api.replyToOrganizationYouTubeChat(
                                        organizationId,
                                        selectedYoutubeAccountId,
                                        chatId,
                                        text,
                                        item.optString("authorChannelId")
                                    )
                                }.onSuccess { reply ->
                                    if (reply.optBoolean("ignored", false)) return@onSuccess
                                    val answer = reply.optString("reply")
                                    if (answer.isNotBlank()) youtubeChatLog.add("BQ Sqwad AI: $answer")
                                }.onFailure { e ->
                                    youtubeChatLog.add("AI reply unavailable: " + (e.message ?: "unknown error"))
                                }
                            }
                        }
                    }
                }
            }.onFailure { e ->
                if (e.message?.contains("live_chat", ignoreCase = true) == true) {
                    youtubeChatLog.add("Live chat: " + (e.message ?: "unavailable"))
                }
            }
            delay(5000)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            sources.add(ProductionSource(nextId, "Media source $nextId", uri))
            if (selectedSourceId == null) selectedSourceId = nextId
            nextId += 1
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(4.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Organization AI / Studio", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Native organization control for production, shared knowledge, AI behavior, channels, members and broadcast orchestration.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(onClick = {}, label = { Text("Production") })
                        SuggestionChip(onClick = {}, label = { Text("AI Director") })
                        SuggestionChip(onClick = {}, label = { Text("Organization") })
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Organization profile", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Organization name") })
                    OutlinedTextField(
                        description, { description = it }, Modifier.fillMaxWidth(),
                        minLines = 3, label = { Text("Description") }
                    )
                    Button(
                        enabled = name.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { api.updateOrganization(name, description) }
                                    .onSuccess { applyOrganization(it) }
                                    .onFailure { error = it.message ?: "Organization could not be saved." }
                                busy = false
                            }
                        }
                    ) { Text(if (busy) "Saving…" else "Save organization") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI & data controls", style = MaterialTheme.typography.titleLarge)
                    Text("These controls are stored with the organization and do not erase personal memory.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Organization AI enabled")
                        Switch(assistantEnabled, onCheckedChange = {
                            assistantEnabled = it
                            scope.launch { runCatching { api.updateOrganizationSettings(assistantEnabled = it) }.onFailure { e -> error = e.message } }
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Shared organization memory")
                        Switch(sharedMemory, onCheckedChange = {
                            sharedMemory = it
                            scope.launch { runCatching { api.updateOrganizationSettings(sharedMemoryEnabled = it) }.onFailure { e -> error = e.message } }
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Shared organization knowledge")
                        Switch(sharedKnowledge, onCheckedChange = {
                            sharedKnowledge = it
                            scope.launch { runCatching { api.updateOrganizationSettings(sharedKnowledgeEnabled = it) }.onFailure { e -> error = e.message } }
                        })
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Organization AI assistant", style = MaterialTheme.typography.titleLarge)
                    Text("Ask the organization AI about approved organization knowledge, settings and workflows. Personal memory is used only when the shared-memory control is enabled.")
                    OutlinedTextField(
                        assistantPrompt, { assistantPrompt = it }, Modifier.fillMaxWidth(),
                        minLines = 3, label = { Text("Ask Organization AI") }
                    )
                    Button(
                        enabled = assistantPrompt.isNotBlank() && assistantEnabled && !busy,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching {
                                        val org = organization
                                        val knowledge = org?.optJSONArray("knowledge")?.let { items ->
                                            (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() } }
                                                .takeLast(50).joinToString("\n")
                                        }.orEmpty()
                                        val settings = org?.optJSONObject("settings")
                                        val system = listOf(
                                            "You are the BQ Sqwad AI Organization AI / Studio assistant.",
                                            "Keep organization data isolated from unrelated personal workspace data.",
                                            "Do not invent organization facts.",
                                            "Organization: " + (org?.optString("name") ?: "My Organization"),
                                            "Description: " + (org?.optString("description") ?: ""),
                                            "Organization knowledge:\n" + if (settings?.optBoolean("sharedKnowledgeEnabled", false) == true) knowledge else "(shared knowledge disabled)"
                                        ).joinToString("\n")
                                        // Route Organization AI through the server-side organization endpoint.
                                        // The backend owns organization membership, knowledge isolation and credit charging.
                                        val reply = api.organizationAssistant(assistantPrompt)
                                        assistantResult = reply.optString("answer").ifBlank { "The organization AI returned no answer." }
                                        assistantPrompt = ""
                                    }
                                    .onFailure { e -> error = e.message ?: "Organization AI request failed." }
                                busy = false
                            }
                        }
                    ) { Text(if (busy) "Working…" else "Ask Organization AI") }
                    if (assistantResult.isNotBlank()) {
                        Text("Organization AI", style = MaterialTheme.typography.titleMedium)
                        Text(assistantResult)
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Organization knowledge", style = MaterialTheme.typography.titleLarge)
                    Text("Add persistent organization instructions, procedures and facts. This data stays scoped to the organization.")
                    OutlinedTextField(
                        knowledgeText, { knowledgeText = it }, Modifier.fillMaxWidth(),
                        minLines = 4, label = { Text("Knowledge / instruction") }
                    )
                    Button(
                        enabled = knowledgeText.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                runCatching { api.addOrganizationKnowledge(knowledgeText) }
                                    .onSuccess { applyOrganization(it); knowledgeText = "" }
                                    .onFailure { e -> error = e.message ?: "Knowledge could not be saved." }
                            }
                        }
                    ) { Text("Add knowledge") }
                    val knowledge = organization?.optJSONArray("knowledge")
                    Text("Stored items: ${knowledge?.length() ?: 0}")
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Members & roles", style = MaterialTheme.typography.titleLarge)
                    Text("Owner, admin, editor, viewer and member roles are persisted server-side.")
                    OutlinedTextField(memberId, { memberId = it }, Modifier.fillMaxWidth(), label = { Text("Member Firebase UID") })
                    OutlinedTextField(memberRole, { memberRole = it }, Modifier.fillMaxWidth(), label = { Text("Role") })
                    Button(
                        enabled = memberId.isNotBlank(),
                        onClick = {
                            scope.launch {
                                runCatching { api.upsertOrganizationMember(memberId, memberRole) }
                                    .onSuccess { applyOrganization(it); memberId = "" }
                                    .onFailure { e -> error = e.message ?: "Member could not be saved." }
                            }
                        }
                    ) { Text("Add / update member") }
                    val members = organization?.optJSONArray("members")
                    if (members != null) {
                        for (i in 0 until members.length()) {
                            val m = members.optJSONObject(i) ?: continue
                            val id = m.optString("id")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(id)
                                    Text(m.optString("role") + " • " + m.optString("status", "active"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (m.optString("role") != "owner") {
                                    TextButton(onClick = {
                                        scope.launch {
                                            runCatching { api.setOrganizationMemberStatus(id, if (m.optString("status") == "inactive") "active" else "inactive") }
                                                .onSuccess { applyOrganization(it) }
                                                .onFailure { e -> error = e.message }
                                        }
                                    }) { Text(if (m.optString("status") == "inactive") "Activate" else "Deactivate") }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Channels", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(channelName, { channelName = it }, Modifier.weight(1f), label = { Text("Channel name") })
                        OutlinedTextField(channelType, { channelType = it }, Modifier.width(120.dp), label = { Text("Type") })
                    }
                    Button(
                        enabled = channelName.isNotBlank(),
                        onClick = {
                            scope.launch {
                                runCatching { api.addOrganizationChannel(channelName, channelType) }
                                    .onSuccess { applyOrganization(it); channelName = "" }
                                    .onFailure { e -> error = e.message ?: "Channel could not be added." }
                            }
                        }
                    ) { Text("Add channel") }
                    val channels = organization?.optJSONArray("channels")
                    if (channels == null || channels.length() == 0) Text("No channels configured.")
                    else for (i in 0 until channels.length()) {
                        val channel = channels.optJSONObject(i) ?: continue
                        val id = channel.optString("id")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(channel.optString("name"))
                                Text(channel.optString("type") + " • " + if (channel.optBoolean("enabled")) "Enabled" else "Disabled")
                            }
                            Switch(channel.optBoolean("enabled"), onCheckedChange = { next ->
                                scope.launch {
                                    runCatching { api.setOrganizationChannel(id, next) }
                                        .onSuccess { applyOrganization(it) }
                                        .onFailure { e -> error = e.message ?: "Channel state could not be changed." }
                                }
                            })
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Production sources", style = MaterialTheme.typography.titleLarge)
                    Text("Import real video or audio sources for local preview and display handoff.")
                    Button(onClick = { picker.launch(arrayOf("video/*", "audio/*")) }) {
                        Icon(Icons.Default.Add, null)
                        Text(" Add media source")
                    }
                    if (sources.isEmpty()) Text("No production sources connected.")
                }
            }
        }

        items(sources, key = { it.id }) { source ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text(source.uri.lastPathSegment ?: source.uri.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { selectedSourceId = source.id }) { Text("Select") }
                    IconButton(onClick = {
                        sources.remove(source)
                        if (selectedSourceId == source.id) selectedSourceId = sources.firstOrNull()?.id
                    }) { Icon(Icons.Default.Delete, "Remove source") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Production control", style = MaterialTheme.typography.titleLarge)
                    Text(outputStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = sources.isNotEmpty(),
                            onClick = {
                                val source = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()
                                if (source != null) runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(source.uri, context.contentResolver.getType(source.uri) ?: "video/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    })
                                    outputStatus = "Program preview opened"
                                }.onFailure { outputStatus = "No compatible player is available" }
                            }
                        ) { Icon(Icons.Default.PlayArrow, null); Text(" Preview") }
                        Button(
                            enabled = sources.isNotEmpty(),
                            onClick = {
                                val source = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()
                                if (source != null) runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(source.uri, context.contentResolver.getType(source.uri) ?: "video/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    })
                                    outputStatus = "Display handoff opened"
                                }.onFailure { outputStatus = "No compatible display route is available" }
                            }
                        ) { Icon(Icons.Default.Cast, null); Text(" Display") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("YouTube live production", style = MaterialTheme.typography.titleLarge)
                    Text("Organization AI creates the provider session server-side, then the native camera broadcaster sends H.264 video and AAC audio directly to the provider ingestion endpoint.")
                    Text("YouTube account", style = MaterialTheme.typography.labelLarge)
                    if (youtubeAccounts.length() == 0) {
                        Text("No Organization YouTube channel is connected. Connect it below; the client secret stays in the encrypted server secret store.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val orgId = organization?.optString("uid").orEmpty().ifBlank { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }
                                    api.startOrganizationYouTubeOAuth(orgId, "https://europe-west1-bqsqwadai.cloudfunctions.net/bqSqwadApi/social/oauth/callback")
                                }.onSuccess {
                                    val url = it.optString("authorizationUrl")
                                    if (url.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure { e -> error = e.message ?: "YouTube connection could not start." }
                            }
                        }) { Text("Connect YouTube") }
                    } else {
                        for (i in 0 until youtubeAccounts.length()) {
                            val account = youtubeAccounts.optJSONObject(i) ?: continue
                            val id = account.optString("id")
                            val label = account.optJSONObject("metadata")?.optString("channelTitle").takeUnless { it.isNullOrBlank() } ?: "Connected YouTube channel"
                            FilterChip(selected = selectedYoutubeAccountId == id, onClick = { selectedYoutubeAccountId = id }, label = { Text(label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = liveSessionId == null && !busy && selectedYoutubeAccountId.isNotBlank() && aiLivePrivacyAccepted,
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val organizationId = organization?.optString("uid").orEmpty().ifBlank { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }
                                        val result = api.createYouTubeLive(
                                            org.json.JSONObject()
                                                .put("organizationId", organizationId)
                                                .put("accountId", selectedYoutubeAccountId)
                                                .put("title", name.ifBlank { "BQ Sqwad AI Live" })
                                                .put("privacyStatus", "private")
                                                .put("enableAutoStart", true)
                                                .put("enableAutoStop", true)
                                                .put("resolution", "1080p")
                                                .put("frameRate", "30fps")
                                                .put("aiLiveMode", aiLiveMode)
                                                .put("aiLivePrivacyAccepted", aiLivePrivacyAccepted)
                                        )
                                        val sessionId = result.optString("sessionId").takeIf { it.isNotBlank() }
                                            ?: error("YouTube live session was not created.")
                                        val endpoint = result.optString("ingestionAddress").takeIf { it.isNotBlank() }
                                            ?: error("YouTube did not return an ingestion address.")
                                        val streamName = result.optString("streamName").takeIf { it.isNotBlank() }
                                            ?: error("YouTube did not return a stream key.")
                                        liveSessionId = sessionId
                                        liveEndpoint = endpoint
                                        val intent = Intent(context, LiveMediaForegroundService::class.java).apply {
                                            putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                                            putExtra(LiveMediaForegroundService.EXTRA_LIVE_SESSION_ID, sessionId)
                                            putExtra(LiveMediaForegroundService.EXTRA_LIVE_ENDPOINT, endpoint + "/" + streamName)
                                            putExtra(LiveMediaForegroundService.EXTRA_YOUTUBE_ACCOUNT_ID, result.optString("accountId"))
                                            putExtra(LiveMediaForegroundService.EXTRA_YOUTUBE_BROADCAST_ID, result.optString("broadcastId"))
                                        }
                                        ContextCompat.startForegroundService(context, intent)
                                        outputStatus = "YouTube live is connecting"
                                    } catch (t: Throwable) {
                                        liveSessionId = null
                                        liveEndpoint = null
                                        error = t.message ?: "YouTube live could not start."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Icon(Icons.Default.PlayArrow, null); Text(" Start YouTube Live") }

                        Button(
                            enabled = liveSessionId != null,
                            onClick = {
                                context.startService(
                                    Intent(context, LiveMediaForegroundService::class.java)
                                        .setAction(LiveMediaForegroundService.ACTION_STOP)
                                )
                                liveSessionId = null
                                liveEndpoint = null
                                outputStatus = "Live stopped"
                            }
                        ) { Text("Stop Live") }
                    }
                    Text("AI Live Stream — privacy & control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("What BQ Sqwad AI will do: use only the live inputs enabled for the selected mode, assist the host, and—only in audience modes you explicitly enable—read eligible YouTube live-chat messages and post replies through the connected channel.")
                    Text("What it will not do: reveal passwords, provider secrets, private prompts, hidden system instructions, or private organization/member data; pretend to be a human; or make commitments or sensitive decisions on behalf of the organization.")
                    Text("You control whether the AI stays host-only, can interact with moderated audience messages, or can use broader live-program context. You can stop the live session and disable audience replies at any time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("YouTube access is limited to the permissions you approve during Google OAuth. BQ Sqwad AI stores the provider credentials encrypted on its server and does not place them in the APK.", color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Text("AI Live Stream privacy is required before public streaming.")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        androidx.compose.material3.Checkbox(
                            checked = aiLivePrivacyAccepted,
                            onCheckedChange = { aiLivePrivacyAccepted = it }
                        )
                        Text("I understand what BQ Sqwad AI can see and say during this live session, and I choose the audience mode below.", modifier = Modifier.padding(top = 12.dp))
                    }
                    Text("Mode: " + when (aiLiveMode) {
                        "host_only" -> "Host Only — AI assists the host and does not reply to viewers."
                        "moderated_audience" -> "Moderated Audience — AI may read and reply to viewer comments through the host's approved live channel."
                        else -> "Full Audience — AI can use the live program/screen context and reply to viewers; stronger privacy and moderation controls apply."
                    })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "host_only" to "Host Only",
                            "moderated_audience" to "Moderated",
                            "full_audience" to "Full Audience"
                        ).forEach { (id, label) ->
                            FilterChip(selected = aiLiveMode == id, onClick = {
                                aiLiveMode = id
                                if (id == "host_only") {
                                    aiLiveViewerReplies = false
                                    aiLiveScreenAwareness = false
                                } else if (id == "moderated_audience") {
                                    aiLiveViewerReplies = true
                                    aiLiveScreenAwareness = false
                                } else {
                                    aiLiveViewerReplies = true
                                    aiLiveScreenAwareness = true
                                }
                            }, label = { Text(label) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("AI may reply to viewers")
                        Switch(aiLiveViewerReplies, onCheckedChange = { if (aiLiveMode != "host_only") aiLiveViewerReplies = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("AI may use live screen/program context")
                        Switch(aiLiveScreenAwareness, onCheckedChange = { if (aiLiveMode == "full_audience") aiLiveScreenAwareness = it })
                    }
                    Text("Mode credit rate: ${"%.2f".format(aiLiveCreditsPerMinute)} credits/minute. Each mode is tracked separately so audience-facing AI usage does not silently consume host-only usage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled = aiLivePrivacyAccepted && !busy,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    api.updateOrganizationAiLiveConfig(
                                        org.json.JSONObject()
                                            .put("mode", aiLiveMode)
                                            .put("privacyAccepted", aiLivePrivacyAccepted)
                                            .put("privacyVersion", "2026-09")
                                            .put("viewerReplies", aiLiveViewerReplies)
                                            .put("screenAwareness", aiLiveScreenAwareness)
                                    )
                                }.onSuccess {
                                    aiLiveCreditsPerMinute = it.optDouble("creditRatePerMinute", aiLiveCreditsPerMinute)
                                }.onFailure { e -> error = e.message ?: "AI Live privacy settings could not be saved." }
                            }
                        }
                    ) { Text("Save AI Live privacy & mode") }

                    Text("Session: " + (liveSessionId ?: "not running"))
                    if (youtubeChatLog.isNotEmpty()) {
                        SurfaceCard("YouTube live audience", youtubeChatLog.takeLast(20).joinToString("\n"))
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AI production director", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        directorPrompt, { directorPrompt = it }, Modifier.fillMaxWidth(),
                        minLines = 3, label = { Text("Direct the production") },
                        placeholder = { Text("Prepare a two-camera interview scene and draft live captions.") }
                    )
                    Button(
                        enabled = directorPrompt.isNotBlank() && !busy,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    // The production director uses the same server-authoritative organization boundary.
                                    val reply = api.organizationAssistant(
                                        "Production director request:\n$directorPrompt"
                                    )
                                    directorResult = reply.optString("answer").ifBlank { "The production director returned no answer." }
                                } catch (t: Throwable) {
                                    error = t.message ?: "AI director request failed."
                                } finally { busy = false }
                            }
                        }
                    ) { Text(if (busy) "AI is working…" else "Run AI director") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (directorResult.isNotBlank()) {
                        Text("AI Director", style = MaterialTheme.typography.titleMedium)
                        Text(directorResult)
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Professional AI Production Control", style = MaterialTheme.typography.titleLarge)
                    Text("Organization-scoped production policy. Real camera/platform connectivity is reported only after a provider connection actually succeeds.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("AI Production Director")
                        Switch(aiDirectorEnabled, onCheckedChange = { next ->
                            aiDirectorEnabled = next
                            scope.launch {
                                runCatching {
                                    api.updateOrganizationProductionConfig(
                                        org.json.JSONObject().put("aiDirectorEnabled", next)
                                    )
                                }.onSuccess { production = it }
                                 .onFailure { e -> error = e.message }
                            }
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Automatic camera switching")
                        Switch(autoSwitchEnabled, onCheckedChange = { next ->
                            autoSwitchEnabled = next
                            saveProductionConfig()
                        })
                    }
                    Text("Production state: $productionStatus")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                runCatching { api.setOrganizationProductionState(org.json.JSONObject().put("status", "ready")) }
                                    .onSuccess { production = it; productionStatus = "ready" }
                                    .onFailure { e -> error = e.message }
                            }
                        }) { Text("Prepare") }
                        Button(onClick = {
                            scope.launch {
                                runCatching { api.setOrganizationProductionState(org.json.JSONObject().put("status", "idle")) }
                                    .onSuccess { production = it; productionStatus = "idle" }
                                    .onFailure { e -> error = e.message }
                            }
                        }) { Text("Safe stop") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Camera & source registry", style = MaterialTheme.typography.titleLarge)
                    Text("Register real camera/source metadata. Registration itself never claims that a source is connected.")
                    OutlinedTextField(cameraName, { cameraName = it }, Modifier.fillMaxWidth(), label = { Text("Camera/source name") })
                    OutlinedTextField(cameraEndpoint, { cameraEndpoint = it }, Modifier.fillMaxWidth(), label = { Text("Endpoint / pairing reference") })
                    OutlinedTextField(cameraRole, { cameraRole = it }, Modifier.fillMaxWidth(), label = { Text("Production role") })
                    Button(enabled = cameraName.isNotBlank(), onClick = {
                        scope.launch {
                            runCatching {
                                api.upsertOrganizationProductionSource(org.json.JSONObject()
                                    .put("name", cameraName)
                                    .put("endpoint", cameraEndpoint)
                                    .put("role", cameraRole)
                                    .put("type", "camera")
                                    .put("enabled", true))
                            }.onSuccess { production = it; cameraName = ""; cameraEndpoint = "" }
                             .onFailure { e -> error = e.message }
                        }
                    }) { Text("Register camera/source") }
                    val sourceArray = production?.optJSONArray("sources")
                    if (sourceArray == null || sourceArray.length() == 0) Text("No registered camera sources.")
                    else for (i in 0 until sourceArray.length()) {
                        val source = sourceArray.optJSONObject(i) ?: continue
                        Text(source.optString("name") + " • " + source.optString("role") + " • health: " + source.optString("health", "unknown"))
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Streaming destinations", style = MaterialTheme.typography.titleLarge)
                    Text("Choose a connected live destination. YouTube is supported for live broadcast; X is available for post-event publishing when live ingest is not supported. Credentials are never stored in the APK.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(destinationName, { destinationName = it }, Modifier.weight(1f), label = { Text("Destination name") })
                        Column(Modifier.width(150.dp)) {
                            Text("Platform", style = MaterialTheme.typography.labelMedium)
                            listOf("youtube" to "YouTube Live", "x" to "X (post-event)").forEach { (id, label) ->
                                val connected = connectedPlatforms[id] == true
                                FilterChip(
                                    selected = destinationType == id,
                                    enabled = connected,
                                    onClick = { destinationType = id },
                                    label = { Text(if (connected) label else "$label • Not connected", maxLines = 1) }
                                )
                            }
                        }
                    }
                    Text("Only connected platforms can be selected. YouTube supports live ingest; X is post-event publishing only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = destinationName.isNotBlank() && connectedPlatforms[destinationType] == true, onClick = {
                        scope.launch {
                            runCatching {
                                api.upsertOrganizationProductionDestination(org.json.JSONObject()
                                    .put("name", destinationName)
                                    .put("type", destinationType.lowercase())
                                    .put("enabled", false)
                                    .put("authentication", "not_connected"))
                            }.onSuccess { production = it; destinationName = "" }
                             .onFailure { e -> error = e.message }
                        }
                    }) { Text("Add destination") }
                    val destinations = production?.optJSONArray("destinations")
                    if (destinations == null || destinations.length() == 0) Text("No streaming destinations configured.")
                    else for (i in 0 until destinations.length()) {
                        val d = destinations.optJSONObject(i) ?: continue
                        Text(d.optString("name") + " • " + d.optString("platform") + " • " + d.optString("authentication", "not_connected") + " • output: " + d.optString("outputState", "idle"))
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Integration status", style = MaterialTheme.typography.titleLarge)
                    Text("Organization data: server-persisted and scoped")
                    Text("Personal memory: separate from organization knowledge")
                    Text("Channels: server-persisted with enable/disable state")
                    Text("Broadcast adapters: admin/provider controlled; credentials are not stored in the APK")
                    Text("Network cameras, QR pairing and platform broadcast require configured provider endpoints")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Scheduled AI", style = MaterialTheme.typography.titleLarge)
                    Text("Create organization-scoped AI tasks that run automatically on the backend. Schedules are server-persisted and use the shared credit system.")
                    OutlinedTextField(scheduledName, { scheduledName = it }, Modifier.fillMaxWidth(), label = { Text("Task name") })
                    OutlinedTextField(scheduledPrompt, { scheduledPrompt = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("AI task prompt") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("hourly", "daily", "weekly").forEach { option ->
                            FilterChip(selected = scheduledSchedule == option, onClick = { scheduledSchedule = option }, label = { Text(option.replaceFirstChar { it.uppercase() }) })
                        }
                    }
                    Button(
                        enabled = scheduledName.isNotBlank() && scheduledPrompt.isNotBlank(),
                        onClick = {
                            scope.launch {
                                runCatching {
                                    api.createOrganizationScheduledTask(scheduledName, scheduledPrompt, scheduledSchedule)
                                }.onSuccess {
                                    scheduledName = ""
                                    scheduledPrompt = ""
                                    runCatching { api.organizationScheduledTasks() }
                                        .onSuccess { scheduledTasks = it.optJSONArray("tasks") ?: org.json.JSONArray() }
                                }.onFailure { e -> error = e.message ?: "Scheduled task could not be created." }
                            }
                        }
                    ) { Text("Create scheduled task") }
                    if (scheduledTasks.length() == 0) {
                        Text("No scheduled AI tasks yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        for (i in 0 until scheduledTasks.length()) {
                            val task = scheduledTasks.optJSONObject(i) ?: continue
                            val id = task.optString("id")
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(task.optString("name"), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                        Text(task.optString("schedule").replaceFirstChar { it.uppercase() })
                                    }
                                    Text(task.optString("lastStatus", "pending"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    task.optString("nextRunAt").takeIf { it.isNotBlank() }?.let { Text("Next run: " + it.take(19).replace("T", " "), style = MaterialTheme.typography.bodySmall) }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {
                                            scope.launch {
                                                runCatching { api.runOrganizationScheduledTask(id) }
                                                    .onSuccess { runCatching { api.organizationScheduledTasks() }.onSuccess { scheduledTasks = it.optJSONArray("tasks") ?: org.json.JSONArray() } }
                                                    .onFailure { e -> error = e.message ?: "Scheduled task failed." }
                                            }
                                        }) { Text("Run now") }
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                runCatching { api.updateOrganizationScheduledTask(id, org.json.JSONObject().put("enabled", task.optBoolean("enabled", true).not())) }
                                                    .onSuccess { runCatching { api.organizationScheduledTasks() }.onSuccess { scheduledTasks = it.optJSONArray("tasks") ?: org.json.JSONArray() } }
                                                    .onFailure { e -> error = e.message ?: "Task state could not be changed." }
                                            }
                                        }) { Text(if (task.optBoolean("enabled", true)) "Pause" else "Resume") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}
