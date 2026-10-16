package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class CompanionMessage(val fromUser: Boolean, val text: String)
private data class CompanionPlan(
    val id: String,
    val name: String,
    val price: Map<String, String>,
    val features: List<String>,
    val trialDays: Int,
    val trialEligible: Boolean
)

@Composable
fun CompanionWorkspace(onOpenPlans: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var personality by remember { mutableStateOf("Caring") }
    var mode by remember { mutableStateOf("Chat") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var memoryEnabled by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loadingAccess by remember { mutableStateOf(true) }
    var settingsOpen by remember { mutableStateOf(false) }
    var saveHistory by remember { mutableStateOf(true) }
    var notifications by remember { mutableStateOf(true) }
    var safeMode by remember { mutableStateOf(true) }
    var currentPlan by remember { mutableStateOf<CompanionPlan?>(null) }
    var catalogPlans by remember { mutableStateOf<List<CompanionPlan>>(emptyList()) }
    val messages = remember { mutableStateListOf<CompanionMessage>() }

    fun hasPlan(vararg ids: String): Boolean = currentPlan?.id in ids
    val memoryUnlocked = hasPlan("companionbasic", "companionplus", "companionpremium", "companionultimate")
    // Live Voice and Live Camera are credit-metered, never Companion-plan gated.
    val voiceUnlocked = true
    val cameraUnlocked = true

    fun selectMode(next: String) {
        val unlocked = when (next) {
            "Voice" -> voiceUnlocked
            "Camera" -> cameraUnlocked
            "Memory" -> memoryUnlocked
            else -> true
        }
        if (unlocked) {
            mode = next
            notice = null
        } else {
            notice = when (next) {
                "Memory" -> "Companion Memory requires Companion Basic or higher."
                else -> "This Companion feature requires an eligible plan."
            }
        }
    }

    LaunchedEffect(Unit) {
        loadingAccess = true
        runCatching {
            val api = WorkspaceApiClient()
            val catalog = api.companionCatalog()
            val entitlements = api.entitlements()
            val companionSettings = api.companionSettings().optJSONObject("settings") ?: JSONObject()
            notifications = companionSettings.optBoolean("notifications", true)
            saveHistory = companionSettings.optBoolean("saveHistory", true)
            safeMode = companionSettings.optBoolean("safeMode", true)
            val profile = runCatching { api.companionProfile().optJSONObject("profile") ?: JSONObject() }.getOrDefault(JSONObject())
            personality = profile.optString("personality").takeIf { it in setOf("Caring", "Encouraging", "Calm") } ?: "Caring"
            if (saveHistory) {
                runCatching {
                    val saved = api.companionHistory().optJSONArray("items") ?: JSONArray()
                    messages.clear()
                    for (index in saved.length() - 1 downTo 0) {
                        val item = saved.optJSONObject(index) ?: continue
                        messages.add(CompanionMessage(item.optString("role") == "user", item.optString("text")))
                    }
                }.onFailure { notice = safeUserFacingIssue(it, "Saved Companion history could not be loaded.") }
            }
            val plans = parseCompanionPlans(catalog)
            catalogPlans = plans
            val items = entitlements.optJSONArray("items") ?: JSONArray()
            val active = (0 until items.length())
                .mapNotNull { items.optJSONObject(it) }
                .firstOrNull {
                    val id = it.optString("planId")
                    val state = it.optString("state")
                    id.startsWith("companion") && state in setOf("active", "grace", "on_hold", "paused")
                }
            val activePlanId = active?.optString("planId").orEmpty().ifBlank { "companionfree" }
            currentPlan = plans.firstOrNull { it.id == activePlanId }
                ?: plans.firstOrNull { it.id == "companionfree" }
            if (memoryUnlocked) {
                runCatching { api.companionMemory() }
                    .onSuccess { memoryEnabled = it.optBoolean("enabled", false) }
            }
        }.onFailure {
            notice = safeUserFacingIssue(it, "Could not verify your Companion plan.")
            currentPlan = null
        }
        loadingAccess = false
    }

    fun saveSettings() {
        scope.launch {
            runCatching {
                val api = WorkspaceApiClient()
                api.saveCompanionSettings(JSONObject()
                    .put("notifications", notifications)
                    .put("saveHistory", saveHistory)
                    .put("safeMode", safeMode)
                    .put("voiceName", "Aurora")
                    .put("avatarStyle", "Natural"))
                api.saveCompanionProfile(JSONObject().put("personality", personality))
            }.onSuccess { notice = "Companion settings saved." }
                .onFailure { notice = safeUserFacingIssue(it, "Companion settings could not be saved.") }
        }
    }

    if (loadingAccess) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        Modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BqLogo(42.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("AI Companion", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Caring, calm and always listening", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(androidx.compose.material.icons.Icons.Default.Settings, contentDescription = "Companion settings")
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(currentPlan?.name ?: if (loadingAccess) "Checking plan…" else "Plan details unavailable", fontWeight = FontWeight.Bold)
                        currentPlan?.price?.entries?.joinToString(" • ") { (period, value) ->
                            period.replaceFirstChar { it.uppercase() } + ": " + value
                        }?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        if (currentPlan?.trialEligible == true && (currentPlan?.trialDays ?: 0) > 0) {
                            Text(currentPlan?.trialDays.toString() + "-day free trial", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (currentPlan?.id != "companionultimate") {
                        OutlinedButton(onClick = onOpenPlans) { Text("View plans") }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(selected = mode == "Chat", onClick = { selectMode("Chat") }, label = { Text("Chat", maxLines = 1, softWrap = false) })
                    FilterChip(selected = mode == "Voice", onClick = { selectMode("Voice") }, label = { Text("Voice", maxLines = 1, softWrap = false) })
                    FilterChip(selected = mode == "Camera", onClick = { selectMode("Camera") }, label = { Text("Camera", maxLines = 1, softWrap = false) })
                    FilterChip(selected = mode == "Memory", onClick = { selectMode("Memory") }, label = { Text(if (memoryUnlocked) "Memory" else "🔒 Memory", maxLines = 1, softWrap = false) })
                }
            }
        }

        when (mode) {
            "Voice" -> {
                VoiceWorkspace()
                return@Column
            }
            "Camera" -> {
                CameraWorkspace()
                return@Column
            }
            "Memory" -> {
                SurfaceCard("Memory controls", "Memory is account-scoped and remains under your control.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Companion memory", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (memoryEnabled) "Enabled — you control it" else "Disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = memoryEnabled,
                        onCheckedChange = { enabled ->
                            memoryEnabled = enabled
                            scope.launch {
                                runCatching { WorkspaceApiClient().setCompanionMemoryEnabled(enabled) }
                                    .onFailure { notice = safeUserFacingIssue(it, "Memory setting could not be saved.") }
                            }
                        }
                    )
                }
                DeviceIntelligencePanel("AI Companion")
                return@Column
            }
        }

        if (settingsOpen) {
            ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Companion settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Notifications", modifier = Modifier.weight(1f)); Switch(checked = notifications, onCheckedChange = { notifications = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Save conversation history", modifier = Modifier.weight(1f)); Switch(checked = saveHistory, onCheckedChange = { saveHistory = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Safety mode", modifier = Modifier.weight(1f)); Switch(checked = safeMode, onCheckedChange = { safeMode = it })
                    }
                    Button(onClick = { saveSettings(); settingsOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }

        Text("Personality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Caring", "Encouraging", "Calm").forEach { value ->
                FilterChip(selected = personality == value, onClick = { personality = value }, label = { Text(value, maxLines = 1, softWrap = false) })
            }
        }

        SurfaceCard(
            "Memory",
            if (memoryUnlocked) {
                                    if (memoryEnabled) "Enabled — your companion can use permitted account memory." else "Disabled — you control it."
                                } else {
                                    "Locked until Companion Basic or higher. View plans to unlock memory."
                                }
        )

        notice?.let {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Text(it, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                Surface(
                    color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(message.text, Modifier.padding(12.dp))
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; notice = null },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Talk to your companion…") },
                maxLines = 4
            )
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    val text = input.trim()
                    input = ""
                    messages.add(CompanionMessage(true, text))
                    busy = true
                    notice = null
                    scope.launch {
                        runCatching {
                            WorkspaceApiClient().companionChat(text, personality)
                        }.onSuccess { reply ->
                            val answer = reply.optString("text").ifBlank { reply.optString("answer") }
                            if (answer.isNotBlank()) messages.add(CompanionMessage(false, answer))
                        }.onFailure { notice = safeUserFacingIssue(it, "Companion response failed.") }
                        busy = false
                    }
                }
            ) { Text(if (busy) "…" else "Send") }
        }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                val prompt = "Give me a brief, caring daily check-in prompt for today."
                busy = true
                scope.launch {
                    runCatching {
                        WorkspaceApiClient().companionCheckin(prompt, personality)
                    }.onSuccess { messages.add(CompanionMessage(false, it.optString("text"))) }
                        .onFailure { notice = safeUserFacingIssue(it, "Check-in failed.") }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Daily check-in") }
    }
}


private fun parseCompanionPlans(body: JSONObject): List<CompanionPlan> {
    val array = body.optJSONArray("plans") ?: JSONArray()
    return List(array.length()) { i ->
        val p = array.optJSONObject(i) ?: JSONObject()
        val priceObject = p.optJSONObject("priceNgn") ?: JSONObject()
        val price = mutableMapOf<String, String>()
        val keys = priceObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val raw = priceObject.opt(key).toString()
            price[key] = if (raw.contains("₦")) raw else raw.toDoubleOrNull()?.let {
                String.format(java.util.Locale.US, "₦%,.2f", it)
            } ?: raw
        }
        if (price.isEmpty()) {
            if (p.optBoolean("free", false) || p.optString("id") == "companionfree") price["Plan"] = "Free"
            else price["Price"] = "View localized price in Plans & Credits"
        }
        val featuresArray = p.optJSONArray("features") ?: JSONArray()
        CompanionPlan(
            id = p.optString("id"),
            name = p.optString("name"),
            price = price,
            features = List(featuresArray.length()) { index -> featuresArray.optString(index) },
            trialDays = p.optInt("trialDays", 0),
            trialEligible = p.optBoolean("trialEligible", false)
        )
    }.filter { it.id.startsWith("companion") && it.id.isNotBlank() }
}
