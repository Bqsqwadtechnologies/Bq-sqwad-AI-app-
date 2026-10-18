@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bqsqwad.ai.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
fun CompanionWorkspace(onOpenPlans: () -> Unit = {}, onOpenCredits: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var personality by remember { mutableStateOf("Caring") }
    var mode by remember { mutableStateOf("Chat") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var memoryEnabled by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loadingAccess by remember { mutableStateOf(true) }
    var accessLoadedSuccessfully by remember { mutableStateOf(false) }
    var accessRefresh by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var saveHistory by remember { mutableStateOf(true) }
    var notifications by remember { mutableStateOf(true) }
    var safeMode by remember { mutableStateOf(true) }
    var currentPlan by remember { mutableStateOf<CompanionPlan?>(null) }
    var catalogPlans by remember { mutableStateOf<List<CompanionPlan>>(emptyList()) }
    val messages = remember { mutableStateListOf<CompanionMessage>() }
    val dictationResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty().trim()
            if (spoken.isNotBlank()) {
                input = if (input.isBlank()) spoken else input.trimEnd() + " " + spoken
                notice = "Voice dictation added to your message. Review it before sending."
            }
        }
    }
    val dictationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to your AI Companion")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            runCatching { dictationResultLauncher.launch(intent) }
                .onFailure { notice = "Voice dictation is not available on this device right now." }
        } else {
            notice = "Microphone permission is needed for voice-to-text. You can still type your message."
        }
    }

    fun hasPlan(vararg ids: String): Boolean = currentPlan?.id?.let { it in ids } == true
    val memoryUnlocked = accessLoadedSuccessfully && hasPlan("companionbasic", "companionplus", "companionpremium", "companionultimate")
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

    LaunchedEffect(accessRefresh) {
        loadingAccess = true
        val api = WorkspaceApiClient()
        val coreData = withTimeoutOrNull(15_000L) {
            coroutineScope {
                val catalogRequest = async { api.companionCatalog() }
                val entitlementRequest = async { api.entitlements() }
                val settingsRequest = async { api.companionSettings() }
                Triple(catalogRequest.await(), entitlementRequest.await(), settingsRequest.await())
            }
        }
        if (coreData == null) {
            accessLoadedSuccessfully = false
            notice = "Companion plan checks took too long. Check your connection and retry."
            currentPlan = null
            loadingAccess = false
            return@LaunchedEffect
        }
        val (catalog, entitlements, settingsResponse) = coreData
        runCatching {
            val companionSettings = settingsResponse.optJSONObject("settings") ?: JSONObject()
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
            val memoryPlanEligible = currentPlan?.id?.let { it in setOf("companionbasic", "companionplus", "companionpremium", "companionultimate") } == true
            if (memoryPlanEligible) {
                runCatching { api.companionMemory() }
                    .onSuccess { memoryEnabled = it.optBoolean("enabled", false) }
            } else {
                memoryEnabled = false
            }
        }.onSuccess {
            accessLoadedSuccessfully = true
        }.onFailure {
            accessLoadedSuccessfully = false
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
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CompanionFloatingWords(Modifier.fillMaxWidth().height(38.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BqLogo(42.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("AI Companion", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            when (personality) {
                                "Motivational" -> "Positive momentum, goals and gentle encouragement"
                                "Professional" -> "Clear, organised and practical personal assistance"
                                "Creative" -> "Ideas, reflection and creative exploration"
                                "Calm" -> "A quieter space for reflection and thoughtful conversation"
                                else -> "A personal space for thoughtful, supportive conversation"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Companion settings")
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(currentPlan?.name ?: if (loadingAccess) "Checking plan…" else "Could not check plan", fontWeight = FontWeight.Bold)
                        currentPlan?.price?.entries?.joinToString(" • ") { (period, value) ->
                            period.replaceFirstChar { it.uppercase() } + ": " + value
                        }?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        if (currentPlan?.trialEligible == true && (currentPlan?.trialDays ?: 0) > 0) {
                            Text(currentPlan?.trialDays.toString() + "-day free trial", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (currentPlan == null && !loadingAccess) {
                        TextButton(onClick = { accessRefresh++ }) { Text("Retry") }
                    } else if (currentPlan?.id != "companionultimate") {
                        OutlinedButton(onClick = onOpenPlans) { Text("Upgrade") }
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

        Text("Companion plans", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(catalogPlans, key = { it.id }) { plan ->
                val isCurrent = currentPlan?.id == plan.id
                Card(
                    modifier = Modifier.width(236.dp),
                    shape = RoundedCornerShape(18.dp),
                    border = if (isCurrent) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(plan.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            if (isCurrent) BqStatusBadge("CURRENT")
                        }
                        Text("Plan ID: " + plan.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            plan.price.entries.joinToString(" • ") { (period, value) -> period.replaceFirstChar { it.uppercase() } + ": " + value }.ifBlank { if (plan.id == "companionfree") "Free" else "See Plans & Billing" },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        plan.features.take(4).forEach { feature -> Text("• " + feature, style = MaterialTheme.typography.bodySmall) }
                        if (plan.features.size > 4) Text("+" + (plan.features.size - 4) + " more features", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (plan.trialEligible && plan.trialDays > 0) Text(plan.trialDays.toString() + "-day trial eligible", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (!isCurrent) {
                            OutlinedButton(onClick = onOpenPlans, modifier = Modifier.fillMaxWidth()) { Text("View plan") }
                        }
                    }
                }
            }
        }

        when (mode) {
            "Voice" -> {
                VoiceWorkspace(onOpenCredits = onOpenCredits)
                return@Column
            }
            "Camera" -> {
                CameraWorkspace(onOpenCredits = onOpenCredits)
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

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; notice = null },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Talk to your companion…") },
                maxLines = 4
            )
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to your AI Companion")
                        }
                        runCatching { dictationResultLauncher.launch(intent) }
                            .onFailure { notice = "Voice dictation is not available on this device right now." }
                    } else {
                        dictationPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Voice to text")
            }
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
                            if (answer.isNotBlank()) {
                                messages.add(CompanionMessage(false, answer))
                                // The Worker independently checks explicit account consent before storing anything.
                                scope.launch {
                                    runCatching { WorkspaceApiClient().submitTrainingContribution(text, answer, "companion", false) }
                                }
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { WorkspaceApiClient().submitTrainingContribution(text, answer, "companion", false) }
                                }
                            }
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


@Composable
private fun CompanionFloatingWords(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduceMotion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) <= 0f
        }.getOrDefault(false)
    }
    val transition = rememberInfiniteTransition(label = "companion-floating-words")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "word-drift"
    )
    val words = remember {
        listOf(
            Triple("Care", .05f, .25f), Triple("Calm", .27f, .72f),
            Triple("Hope", .48f, .40f), Triple("Joy", .68f, .82f),
            Triple("Courage", .84f, .24f), Triple("Friend", .15f, .86f),
            Triple("Trust", .38f, .18f), Triple("Growth", .58f, .66f),
            Triple("Peace", .77f, .54f), Triple("Together", .92f, .90f),
            Triple("Listen", .32f, .92f), Triple("Believe", .63f, .12f)
        )
    }
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
    }
    Canvas(modifier) {
        val canvas = drawContext.canvas.nativeCanvas
        val saveCount = canvas.save()
        canvas.clipRect(0f, 0f, size.width, size.height)
        words.forEachIndexed { index, word ->
            paint.textSize = (10 + index % 4 * 2) * density
            paint.color = if (index % 2 == 0) android.graphics.Color.rgb(70, 155, 245) else android.graphics.Color.rgb(73, 190, 183)
            paint.alpha = if (reduceMotion) 58 else (36 + ((kotlin.math.sin(((phase * 6.28318f) + index).toDouble()).toFloat() + 1f) * 20f).toInt()).coerceIn(25, 78)
            val yBase = word.third * size.height
            val y = if (reduceMotion) yBase else (yBase - phase * size.height + size.height * 2f) % size.height
            canvas.drawText(word.first, word.second * size.width, y, paint)
        }
        canvas.restoreToCount(saveCount)
    }
}
