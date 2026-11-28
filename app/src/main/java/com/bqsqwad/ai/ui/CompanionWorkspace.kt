@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bqsqwad.ai.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CompanionMessage(
    val fromUser: Boolean,
    val text: String,
    val isCached: Boolean = false,
    val assistantName: String = "Goodshina",
    val timestamp: String = ""
)

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

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Chat, 1: Tool Guide, 2: Plans & Storage
    var personality by remember { mutableStateOf("Caring") }
    var mode by remember { mutableStateOf("Chat") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var memoryEnabled by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loadingAccess by remember { mutableStateOf(true) }
    var adminTestMode by remember { mutableStateOf(false) }
    var accessLoadedSuccessfully by remember { mutableStateOf(false) }
    var accessRefresh by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var saveHistory by remember { mutableStateOf(true) }
    var notifications by remember { mutableStateOf(true) }
    var safeMode by remember { mutableStateOf(true) }
    var isExpiring by remember { mutableStateOf(false) }
    var currentPlan by remember { mutableStateOf<CompanionPlan?>(null) }
    var catalogPlans by remember { mutableStateOf<List<CompanionPlan>>(emptyList()) }
    var isRedirectedNoPlan by remember { mutableStateOf(false) }
    var cloudSyncStatus by remember { mutableStateOf("Synced with BQ SQWAD AI Cloud") }

    val messages = remember { mutableStateListOf<CompanionMessage>() }

    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val infiniteTransition = rememberInfiniteTransition(label = "companion-pulse")
    val blinkingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blinking-alpha"
    )

    val dictationResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty().trim()
            if (spoken.isNotBlank()) {
                input = if (input.isBlank()) spoken else input.trimEnd() + " " + spoken
                notice = "Voice dictation added. Ready to send."
            }
        }
    }
    val dictationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to AI Companion")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            runCatching { dictationResultLauncher.launch(intent) }
                .onFailure { notice = "Voice dictation is not available on this device right now." }
        } else {
            notice = "Microphone permission is needed for speech dictation."
        }
    }

    fun hasPlan(vararg ids: String): Boolean = currentPlan?.id?.let { planId ->
        ids.any { planId.equals(it, ignoreCase = true) }
    } == true

    val isFreePlan = currentPlan?.id == "companionfree" || currentPlan?.id == "companion_free"
    val memoryUnlocked = accessLoadedSuccessfully && (adminTestMode || hasPlan("companionbasic", "companionplus", "companionpremium", "companionultimate", "companion_basic", "companion_plus", "companion_premium", "companion_ultimate"))

    LaunchedEffect(accessRefresh) {
        loadingAccess = true
        accessLoadedSuccessfully = false
        val api = WorkspaceApiClient()

        val coreData = withTimeoutOrNull(8_000L) {
            coroutineScope {
                val catalogRequest = async { runCatching { api.companionCatalog() }.getOrDefault(JSONObject()) }
                val entitlementRequest = async { runCatching { api.entitlements() }.getOrDefault(JSONObject()) }
                catalogRequest.await() to entitlementRequest.await()
            }
        }
        if (coreData == null) {
            accessLoadedSuccessfully = false
            notice = "Plan status could not be refreshed quickly. Tap Retry to reconnect."
            loadingAccess = false
            return@LaunchedEffect
        }

        val (catalog, entitlementResponse) = coreData
        val plans = parseCompanionPlans(catalog)
        catalogPlans = plans
        adminTestMode = catalog.optBoolean("adminTestMode", false)

        val items = entitlementResponse.optJSONArray("items") ?: JSONArray()
        val activeEntitlement = (0 until items.length())
            .mapNotNull { items.optJSONObject(it) }
            .filter { item ->
                val planId = item.optString("planId").lowercase()
                val module = item.optString("module").lowercase()
                val state = item.optString("state").lowercase()
                (module == "companion" || planId.startsWith("companion")) &&
                        state in setOf("active", "grace", "trialing")
            }
            .maxByOrNull { item ->
                item.optString("updatedAt").ifBlank { item.optString("startDate") }
            }

        val activePlanId = when {
            adminTestMode -> "companionultimate"
            activeEntitlement != null -> activeEntitlement.optString("planId").lowercase()
            else -> "companionfree"
        }

        isRedirectedNoPlan = (activeEntitlement == null && !adminTestMode && catalogPlans.isNotEmpty() && catalogPlans.none { it.id == "companionfree" })
        currentPlan = plans.firstOrNull { it.id.equals(activePlanId, ignoreCase = true) } ?: plans.firstOrNull { it.id == "companionfree" }

        // Expiring condition: trialing with <= 2 days or grace state
        val state = activeEntitlement?.optString("state")?.lowercase()
        val trialDays = currentPlan?.trialDays ?: 0
        isExpiring = state == "grace" || (state == "trialing" && trialDays <= 2)

        val memoryPlanEligible = adminTestMode ||
                (currentPlan?.id?.lowercase()?.let {
                    it in setOf("companionbasic", "companionplus", "companionpremium", "companionultimate")
                } == true)

        accessLoadedSuccessfully = currentPlan != null
        loadingAccess = false

        scope.launch {
            supervisorScope {
                val settingsTask = async { runCatching { withTimeoutOrNull(5_000L) { api.companionSettings() } }.getOrNull() }
                val profileTask = async { runCatching { withTimeoutOrNull(5_000L) { api.companionProfile() } }.getOrNull() }
                val historyTask = if (currentPlan?.id != "companionfree" && currentPlan != null) async {
                    runCatching { withTimeoutOrNull(6_000L) { api.companionHistory() } }.getOrNull()
                } else null
                val memoryTask = if (memoryPlanEligible) async {
                    runCatching { withTimeoutOrNull(5_000L) { api.companionMemory() } }.getOrNull()
                } else null

                runCatching { settingsTask.await() }?.getOrNull()?.let { response ->
                    val settings = response?.optJSONObject("settings") ?: JSONObject()
                    notifications = settings.optBoolean("notifications", true)
                    saveHistory = settings.optBoolean("saveHistory", true)
                    safeMode = settings.optBoolean("safeMode", true)
                }
                runCatching { profileTask.await() }?.getOrNull()?.let { response ->
                    val profile = response?.optJSONObject("profile") ?: JSONObject()
                    personality = profile.optString("personality")
                        .takeIf { it in setOf("Caring", "Encouraging", "Calm", "Motivational", "Professional") } ?: "Caring"
                }
                if (saveHistory) {
                    runCatching { historyTask?.await() }?.getOrNull()?.let { response ->
                        val saved = response?.optJSONArray("items") ?: JSONArray()
                        messages.clear()
                        for (index in saved.length() - 1 downTo 0) {
                            val item = saved.optJSONObject(index) ?: continue
                            messages.add(
                                CompanionMessage(
                                    fromUser = item.optString("role") == "user",
                                    text = item.optString("text"),
                                    isCached = item.optBoolean("cached", false),
                                    assistantName = item.optString("assistantName").ifBlank { "Goodshina" }
                                )
                            )
                        }
                    }
                }
                runCatching { memoryTask?.await() }?.getOrNull()?.let { response ->
                    memoryEnabled = response?.optBoolean("enabled", false) == true
                } ?: run {
                    if (!memoryPlanEligible) memoryEnabled = false
                }
                cloudSyncStatus = "Cloud Sync Active • Synchronized with Web"
            }
        }
    }

    fun saveSettings() {
        scope.launch {
            runCatching {
                val api = WorkspaceApiClient()
                api.saveCompanionSettings(JSONObject()
                    .put("notifications", notifications)
                    .put("saveHistory", saveHistory)
                    .put("safeMode", safeMode))
                api.saveCompanionProfile(JSONObject().put("personality", personality))
            }.onSuccess { notice = "Settings and personality updated successfully." }
                .onFailure { notice = safeUserFacingIssue(it, "Settings could not be saved.") }
        }
    }

    fun updatePersonality(newPersonality: String) {
        personality = newPersonality
        scope.launch {
            runCatching {
                WorkspaceApiClient().saveCompanionProfile(JSONObject().put("personality", newPersonality))
            }.onSuccess {
                notice = "AI Companion tone tuned to '$newPersonality'."
            }.onFailure {
                notice = safeUserFacingIssue(it, "Could not update personality tone.")
            }
        }
    }

    fun sendMessageText(textToSend: String) {
        val trimmed = textToSend.trim()
        if (trimmed.isBlank() || busy) return
        input = ""
        val timeNow = timeFormatter.format(Date())
        messages.add(CompanionMessage(fromUser = true, text = trimmed, timestamp = timeNow))
        busy = true
        notice = null

        scope.launch {
            runCatching {
                WorkspaceApiClient().companionChat(trimmed, personality)
            }.onSuccess { reply ->
                val answer = reply.optString("text").ifBlank { reply.optString("answer") }
                val isCached = reply.optBoolean("cached", false)
                val assistantName = reply.optString("assistantName").ifBlank { "Goodshina" }
                if (answer.isNotBlank()) {
                    messages.add(
                        CompanionMessage(
                            fromUser = false,
                            text = answer,
                            isCached = isCached,
                            assistantName = assistantName,
                            timestamp = timeFormatter.format(Date())
                        )
                    )
                }
            }.onFailure {
                notice = safeUserFacingIssue(it, "AI Companion response failed. Please retry.")
            }
            busy = false
        }
    }

    if (loadingAccess) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BqSqwadLoadingIndicator(Modifier.size(44.dp))
                Text("Initializing AI Companion…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    // No Active Plan Redirection
    if (isRedirectedNoPlan) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    BqLogo(56.dp)
                    Text(
                        "Welcome to BQ SQWAD AI Companion",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "An active Companion Plan or Free Mode is required to connect with AI Companion.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onOpenPlans,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("View Plan Catalog & Subscribe")
                    }
                    OutlinedButton(
                        onClick = {
                            isRedirectedNoPlan = false
                            currentPlan = catalogPlans.firstOrNull { it.id == "companionfree" }
                                ?: CompanionPlan("companionfree", "Companion Free", mapOf("Free" to "₦0"), listOf("Caring text conversation", "AI Companion"), 0, false)
                            notice = "Switched to Free Mode. AI Companion is ready."
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start with Free Mode")
                    }
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top Executive Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BqLogo(38.dp)
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "AI Companion",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        "AI Companion",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Text(
                                "Personal guidance, caring check-ins & memory",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Companion settings")
                        }
                    }
                }

                // Plan Status Banner with Glowing/Blinking Upgrade Button when Expiring or Free
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    currentPlan?.name ?: "Companion Plan",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                if (adminTestMode) {
                                    BqStatusBadge("ADMIN TEST")
                                } else if (isFreePlan) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            "Free Mode Active",
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                            Text(
                                if (isFreePlan) "Standard Luna chat · Upgrade to unlock full memory"
                                else "Memory & cloud history synchronized across app & web",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Blinking/pulsing upgrade button when expiring or on free tier
                        if (isExpiring || isFreePlan) {
                            Button(
                                onClick = onOpenPlans,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isExpiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .alpha(if (isExpiring) blinkingAlpha else 1f)
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isExpiring) "Upgrade" else "Upgrade", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
                            }
                        } else if (currentPlan?.id != "companionultimate") {
                            OutlinedButton(
                                onClick = onOpenPlans,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text("Manage", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // Interactive Navigation Tabs (Horizontal arrangement to prevent vertical wrapping)
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Chat", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tool Guide", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Plans & Sync", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // Notification banner
        notice?.let { msg ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        msg,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    IconButton(onClick = { notice = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> { // TAB 0: CONVERSATION
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Personality Quick Bar
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            Text("Tone:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        items(listOf("Caring", "Encouraging", "Calm", "Motivational", "Professional")) { p ->
                            FilterChip(
                                selected = personality == p,
                                onClick = { updatePersonality(p) },
                                label = { Text(p, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    // Message list
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Default.Spa, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Text(
                                                "Welcome to your personal space",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            "AI Companion is ready to listen, reflect and support your day. Responses are cached to protect tokens, and full memory is available on eligible plans.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text("Quick questions to start:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            items(listOf(
                                                "How can I decompress tonight?",
                                                "Help me plan a productive morning",
                                                "Give me a positive perspective on today",
                                                "Help me reflect on a difficult goal"
                                            )) { prompt ->
                                                SuggestionChip(
                                                    onClick = { sendMessageText(prompt) },
                                                    label = { Text(prompt, maxLines = 1) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        items(messages) { msg ->
                            val isUser = msg.fromUser
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                            ) {
                                if (!isUser) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                                    ) {
                                        Text(
                                            msg.assistantName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (msg.isCached) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    "⚡ Instant Cache · 0 Credits",
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                                )
                                            }
                                        }
                                        if (msg.timestamp.isNotBlank()) {
                                            Text(msg.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontSize = 10.sp)
                                        }
                                    }
                                }

                                Surface(
                                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isUser) 16.dp else 4.dp,
                                        bottomEnd = if (isUser) 4.dp else 16.dp
                                    ),
                                    modifier = Modifier.widthIn(max = 320.dp)
                                ) {
                                    Text(
                                        msg.text,
                                        modifier = Modifier.padding(12.dp),
                                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    // Input & Action controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it; notice = null },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Talk to AI Companion…") },
                            maxLines = 4,
                            shape = RoundedCornerShape(20.dp)
                        )

                        IconButton(
                            onClick = {
                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to AI Companion")
                                    }
                                    runCatching { dictationResultLauncher.launch(intent) }
                                        .onFailure { notice = "Voice dictation is unavailable on this device." }
                                } else {
                                    dictationPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            enabled = !busy
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Voice dictation", tint = MaterialTheme.colorScheme.primary)
                        }

                        Button(
                            onClick = { sendMessageText(input) },
                            enabled = !busy && input.isNotBlank(),
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            1 -> { // TAB 1: TOOL GUIDE DASHBOARD
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            "AI Companion Tool Guide",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Explore and control the core tools of your companion system. Every button is fully functional and synchronized with your account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // TOOL 1: Companion AI Engine
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Tool 1: Companion AI Engine", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    "Dedicated conversational model configured with emotional intelligence, boundary safety, and token-saving smart caching.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            selectedTab = 0
                                            sendMessageText("Hello Luna, can you introduce your role as my AI companion?")
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Ask Intro", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                runCatching { WorkspaceApiClient().clearCompanionHistory() }
                                                    .onSuccess {
                                                        messages.clear()
                                                        notice = "Conversation history cleared from cloud storage."
                                                    }
                                                    .onFailure {
                                                        notice = safeUserFacingIssue(it, "Could not clear history.")
                                                    }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Clear History", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    // TOOL 2: Daily Caring Check-in
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                    Text("Tool 2: Daily Reflection & Check-in", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    "Generate tailored daily mindfulness check-ins. Prompts help process emotions, calibrate routines, and reduce anxiety.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        selectedTab = 0
                                        busy = true
                                        scope.launch {
                                            runCatching {
                                                WorkspaceApiClient().companionCheckin("Please provide a caring daily reflection prompt for today.", personality)
                                            }.onSuccess {
                                                val checkinText = it.optString("text")
                                                messages.add(CompanionMessage(fromUser = false, text = checkinText, assistantName = "AI Companion (Daily Check-in)"))
                                                notice = "Daily check-in received."
                                            }.onFailure {
                                                notice = safeUserFacingIssue(it, "Check-in failed.")
                                            }
                                            busy = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Run Daily Check-in", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // TOOL 3: Memory Vault & Context
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                    Text("Tool 3: Memory Vault & Continuity", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    if (memoryUnlocked) "Companion Memory is active. Luna retains context you share to provide continuity across sessions."
                                    else "Memory is locked on the Free tier. Upgrade to Companion Basic or higher to enable conversation memory.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (memoryUnlocked) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(if (memoryEnabled) "Memory Active" else "Memory Disabled", fontWeight = FontWeight.SemiBold)
                                        Switch(
                                            checked = memoryEnabled,
                                            onCheckedChange = { en ->
                                                memoryEnabled = en
                                                scope.launch {
                                                    runCatching { WorkspaceApiClient().setCompanionMemoryEnabled(en) }
                                                        .onSuccess { notice = "Memory setting updated." }
                                                        .onFailure { notice = safeUserFacingIssue(it, "Failed to update memory setting.") }
                                                }
                                            }
                                        )
                                    }
                                } else {
                                    Button(onClick = onOpenPlans, shape = RoundedCornerShape(10.dp)) {
                                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Unlock Memory", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    // TOOL 4: Personality & Emotional Tone
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Tool 4: Personality & Tone Tuning", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    "Switch Luna's response style. Settings immediately sync to your cloud profile.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(listOf("Caring", "Encouraging", "Calm", "Motivational", "Professional")) { p ->
                                        FilterChip(
                                            selected = personality == p,
                                            onClick = { updatePersonality(p) },
                                            label = { Text(p) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // TOOL 5: Voice & Speech Engine
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                    Text("Tool 5: Voice & Speech Engine", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    "Speak directly with voice dictation or live voice streaming sessions.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        selectedTab = 0
                                        dictationPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Dictate Message", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // TOOL 6: Token-Saving Smart Cache
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Tool 6: Token-Saving Smart Cache", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    "Repeated questions and common inquiries are automatically retrieved from the high-speed local and cloud cache. This avoids burning unnecessary tokens while delivering instant answers.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Text("Cache Engine: Active · Zero credit billing on repeat queries", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }

            2 -> { // TAB 2: PLANS & SYNCHRONIZATION
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            "Companion Plans & Cloud Sync",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Each plan is synchronized between the mobile app and website repository. Storage buckets store conversation history and media seamlessly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Synchronization Status", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                Text(cloudSyncStatus, style = MaterialTheme.typography.bodySmall)
                                Text("Storage Bucket: bqsqwadai.appspot.com (Secure Cloud Storage)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                Button(
                                    onClick = { accessRefresh++ },
                                    modifier = Modifier.padding(top = 4.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Force Cloud Refresh")
                                }
                            }
                        }
                    }

                    // Render plans
                    items(catalogPlans, key = { it.id }) { plan ->
                        val isCurrent = currentPlan?.id == plan.id
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = if (isCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(plan.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    if (isCurrent) {
                                        BqStatusBadge("ACTIVE")
                                    }
                                }

                                val priceDisplay = plan.price.entries.joinToString(" • ") { (k, v) -> "$k: $v" }
                                    .ifBlank { if (plan.id == "companionfree") "Free Mode" else "View in Billing" }
                                Text(priceDisplay, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

                                plan.features.forEach { feat ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(feat, style = MaterialTheme.typography.bodySmall)
                                    }
                                }

                                if (!isCurrent) {
                                    Button(
                                        onClick = onOpenPlans,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Subscribe to ${plan.name}")
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }

        // Settings Bottom Sheet
        if (settingsOpen) {
            ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Companion Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Notifications & Check-in Reminders", modifier = Modifier.weight(1f))
                        Switch(checked = notifications, onCheckedChange = { notifications = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sync Conversation History", modifier = Modifier.weight(1f))
                        Switch(checked = saveHistory, onCheckedChange = { saveHistory = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Safety & Boundary Enforcement", modifier = Modifier.weight(1f))
                        Switch(checked = safeMode, onCheckedChange = { safeMode = it })
                    }

                    Button(
                        onClick = {
                            saveSettings()
                            settingsOpen = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save & Close")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
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
                String.format(Locale.US, "₦%,.2f", it)
            } ?: raw
        }
        if (price.isEmpty()) {
            if (p.optBoolean("free", false) || p.optString("id") == "companionfree") price["Plan"] = "Free"
            else price["Price"] = "View in Plans & Credits"
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
