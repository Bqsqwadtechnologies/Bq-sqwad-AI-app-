package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
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
    val voiceUnlocked = hasPlan("companionplus", "companionpremium", "companionultimate")
    val cameraUnlocked = hasPlan("companionpremium", "companionultimate")

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
                "Voice" -> "Companion Voice requires Companion Plus or higher."
                "Camera" -> "Companion Camera requires Companion Premium or Ultimate."
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
                }.onFailure { notice = it.message ?: "Saved Companion history could not be loaded." }
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
            notice = it.message ?: "Could not verify your Companion plan."
            currentPlan = null
        }
        loadingAccess = false
    }

    fun saveSettings() {
        scope.launch {
            runCatching {
                WorkspaceApiClient().saveCompanionSettings(JSONObject()
                    .put("notifications", notifications)
                    .put("saveHistory", saveHistory)
                    .put("safeMode", safeMode)
                    .put("voiceName", "Aurora")
                    .put("avatarStyle", "Natural"))
            }.onFailure { notice = it.message ?: "Companion settings could not be saved." }
        }
    }

    if (loadingAccess) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI Companion", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Your plan controls Companion memory, voice and camera capabilities. Access is verified against the BQ Sqwad AI catalog and server entitlement.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                if (catalogPlans.isNotEmpty()) {
                    Text("Companion plans", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    catalogPlans.forEach { plan ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(plan.name, fontWeight = FontWeight.SemiBold)
                                val priceText = plan.price.entries.joinToString(" • ") { (period, value) -> period.replaceFirstChar { it.uppercase() } + ": $" + value }
                                if (priceText.isNotBlank()) Text(priceText, style = MaterialTheme.typography.bodySmall)
                                if (plan.trialEligible && plan.trialDays > 0) Text(plan.trialDays.toString() + "-day free trial", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                if (plan.features.isNotEmpty()) Text(plan.features.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (plan.id == currentPlan?.id) AssistChip(onClick = {}, label = { Text("ACTIVE") })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(currentPlan?.name ?: "Plan unavailable", fontWeight = FontWeight.Bold)
                        currentPlan?.price?.entries?.joinToString(" • ") { (period, value) ->
                            period.replaceFirstChar { it.uppercase() } + ": $" + value
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
                if (!currentPlan?.features.isNullOrEmpty()) {
                    Text("Included in your plan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        currentPlan?.features?.joinToString(" • ").orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "Chat", onClick = { selectMode("Chat") }, label = { Text("Chat") })
                    FilterChip(selected = mode == "Voice", onClick = { selectMode("Voice") }, label = { Text(if (voiceUnlocked) "Voice" else "Voice • Plus+") })
                    FilterChip(selected = mode == "Camera", onClick = { selectMode("Camera") }, label = { Text(if (cameraUnlocked) "Camera" else "Camera • Premium+") })
                    FilterChip(selected = mode == "Memory", onClick = { selectMode("Memory") }, label = { Text(if (memoryUnlocked) "Memory" else "Memory • Basic+") })
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
                                    .onFailure { notice = it.message ?: "Memory setting could not be saved." }
                            }
                        }
                    )
                }
                DeviceIntelligencePanel("AI Companion")
                return@Column
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Companion settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { settingsOpen = !settingsOpen }) { Text(if (settingsOpen) "Hide" else "Open") }
        }
        if (settingsOpen) {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Notifications"); Switch(checked = notifications, onCheckedChange = { notifications = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Save conversation history"); Switch(checked = saveHistory, onCheckedChange = { saveHistory = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Safety mode"); Switch(checked = safeMode, onCheckedChange = { safeMode = it })
                    }
                    Button(onClick = ::saveSettings, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
                }
            }
        }

        Text("Personality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Caring", "Encouraging", "Calm").forEach { value ->
                FilterChip(selected = personality == value, onClick = { personality = value }, label = { Text(value) })
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

        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

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
                        }.onFailure { notice = it.message ?: "Companion response failed." }
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
                        .onFailure { notice = it.message ?: "Check-in failed." }
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
        val priceObject = p.optJSONObject("price") ?: JSONObject()
        val price = mutableMapOf<String, String>()
        val keys = priceObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            price[key] = priceObject.opt(key).toString()
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
