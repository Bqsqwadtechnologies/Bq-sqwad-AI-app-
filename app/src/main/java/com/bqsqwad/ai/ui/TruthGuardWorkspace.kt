package com.bqsqwad.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class TruthGuardPlan(
    val id: String,
    val name: String,
    val price: Map<String, String>,
    val features: List<String>,
    val limits: Map<String, String>,
    val trialDays: Int,
    val trialEligible: Boolean
)

@Composable
fun CompleteTruthGuardWorkspace(onOpenPlans: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsPrefs = remember { context.getSharedPreferences("bq_sqwad_ui", android.content.Context.MODE_PRIVATE) }
    val webSearchEnabled = remember { settingsPrefs.getBoolean("web_search_enabled", true) }
    var claim by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deepResearch by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(true) }
    var showReliability by remember { mutableStateOf(true) }
    var conservativeVerdicts by remember { mutableStateOf(true) }
    var saveHistory by remember { mutableStateOf(true) }
    var defaultDeepResearch by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var plans by remember { mutableStateOf<List<TruthGuardPlan>>(emptyList()) }
    var activePlanId by remember { mutableStateOf("truthguardfree") }
    var adminTestMode by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    val isAdmin = adminTestMode || activePlanId == "admin-unlimited"
    val proOrHigher = isAdmin || activePlanId == "tgpro" || activePlanId == "tgteam"

    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            val api = WorkspaceApiClient()
            val catalog = api.truthGuardCatalog()
            activePlanId = catalog.optString("activePlanId", "truthguardfree")
            adminTestMode = catalog.optBoolean("adminTestMode", false)
            plans = parseTruthGuardPlans(catalog)
            val settings = catalog.optJSONObject("settings") ?: JSONObject()
            showSources = settings.optBoolean("showSources", true)
            showReliability = settings.optBoolean("showReliability", true)
            conservativeVerdicts = settings.optBoolean("conservativeVerdicts", true)
            saveHistory = settings.optBoolean("saveHistory", true)
            defaultDeepResearch = settings.optBoolean("defaultDeepResearch", false)
            deepResearch = defaultDeepResearch && proOrHigher
            history = api.truthGuardHistory()
        }.onFailure { notice = it.message ?: "TruthGuard could not load." }
        loading = false
    }

    fun saveSettings() {
        scope.launch {
            runCatching {
                WorkspaceApiClient().saveTruthGuardSettings(
                    JSONObject()
                        .put("showSources", showSources)
                        .put("showReliability", showReliability)
                        .put("conservativeVerdicts", conservativeVerdicts)
                        .put("saveHistory", saveHistory)
                        .put("defaultDeepResearch", defaultDeepResearch && proOrHigher)
                )
            }.onSuccess { notice = "TruthGuard settings saved." }
                .onFailure { notice = it.message ?: "TruthGuard settings could not be saved." }
        }
    }

    fun verify() {
        val text = claim.trim()
        if (text.isBlank()) {
            notice = "Enter a claim to verify."
            return
        }
        if (!webSearchEnabled) {
            notice = "Turn on Web Search in Settings to verify claims against current sources."
            return
        }
        if (deepResearch && !proOrHigher) {
            notice = "Deep Evidence Sweep requires TruthGuard Pro or Team."
            return
        }
        busy = true
        notice = null
        scope.launch {
            runCatching { WorkspaceApiClient().truthGuardCheck(text, deepResearch) }
                .onSuccess {
                    result = it
                    if (saveHistory) runCatching { history = WorkspaceApiClient().truthGuardHistory() }
                }
                .onFailure { notice = it.message ?: "TruthGuard verification failed." }
            busy = false
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { TruthGuardHero(activePlanId, isAdmin, plans.firstOrNull { it.id == activePlanId }, onOpenPlans) }
        item { TruthGuardPlanStrip(plans, activePlanId, isAdmin, onOpenPlans) }
        if (!webSearchEnabled) item {
            SurfaceCard("Web Search is off", "Turn it on in Settings to verify claims against current web evidence.")
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open Settings") }
        }

        item {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 2.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when {
                            isAdmin -> "Deep Evidence Lab"
                            activePlanId == "truthguardfree" -> "Quick Check Workspace"
                            activePlanId == "tgplus" -> "Evidence Desk"
                            activePlanId == "tgpro" -> "Deep Evidence Lab"
                            activePlanId == "tgteam" -> "Team Verification Hub"
                            else -> "TruthGuard Workspace"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            activePlanId == "truthguardfree" -> "Concise claim verification with current evidence."
                            activePlanId == "tgplus" -> "Live evidence, source notes and manipulation signals."
                            activePlanId == "tgpro" -> "Deep evidence sweeps and long-claim breakdowns."
                            activePlanId == "tgteam" -> "Full verification workflow with fair-use access."
                            else -> "Evidence-first claim verification."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = claim,
                        onValueChange = { claim = it; notice = null },
                        Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        label = { Text("Claim to verify") },
                        placeholder = { Text("Paste a statement, headline, post, or claim…") }
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Deep Evidence Sweep", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (proOrHigher) "Available on Pro and Team." else "Locked — Pro or Team required.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = deepResearch,
                            onCheckedChange = {
                                if (it && !proOrHigher) notice = "Deep Evidence Sweep requires TruthGuard Pro or Team."
                                else deepResearch = it
                            }
                        )
                    }
                    Button(
                        onClick = ::verify,
                        enabled = !busy && claim.isNotBlank() && webSearchEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Verifying…" else "Verify claim") }
                    Text(
                        "Successful checks use the server-controlled TruthGuard credit price. Plan limits are enforced on the backend.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        notice?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }

        result?.let {
            item { TruthGuardResultCard(it, showSources, showReliability) }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TruthGuard settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Evidence display, history and verification controls.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { settingsOpen = !settingsOpen }) { Text(if (settingsOpen) "Hide" else "Open") }
            }
        }

        if (settingsOpen) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TruthGuardToggle("Evidence sources", "Show retrieved source cards.", showSources) { showSources = it }
                        TruthGuardToggle("Reliability details", "Show source reliability context.", showReliability) { showReliability = it }
                        TruthGuardToggle("Conservative verdicts", "Use cautious wording for incomplete or conflicting evidence.", conservativeVerdicts) { conservativeVerdicts = it }
                        TruthGuardToggle("Save verification history", "Keep checks in your account history.", saveHistory) { saveHistory = it }
                        TruthGuardToggle("Default to deep sweep", "Start with deep research when your plan supports it.", defaultDeepResearch) { defaultDeepResearch = it }
                        Button(onClick = ::saveSettings, Modifier.fillMaxWidth()) { Text("Save settings") }
                    }
                }
            }
        }

        item { Text("Verification history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (history.isEmpty()) {
            item { SurfaceCard("No checks yet", "Your saved TruthGuard checks will appear here.") }
        } else {
            items(history.take(20), key = { it.optString("id") }) { item ->
                TruthGuardHistoryCard(item) { result = item }
            }
        }
    }
}

@Composable
private fun TruthGuardHero(planId: String, isAdmin: Boolean, plan: TruthGuardPlan?, onOpenPlans: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TruthGuard AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Evidence-aware fact checking, source analysis and claim review.",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (isAdmin) "Full access" else (plan?.name ?: "TruthGuard Free"), fontWeight = FontWeight.Bold)
                    val price = plan?.price?.entries?.joinToString(" • ") { pair ->
                        pair.key.replaceFirstChar { it.uppercase() } + ": $" + pair.value
                    }.orEmpty()
                    if (price.isNotBlank()) Text(price, style = MaterialTheme.typography.bodySmall)
                    if ((plan?.trialDays ?: 0) > 0 && plan?.trialEligible == true) {
                        Text(plan.trialDays.toString() + "-day free trial", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (!isAdmin) OutlinedButton(onClick = onOpenPlans) { Text("View plans") }
            }
        }
    }
}

@Composable
private fun TruthGuardPlanStrip(plans: List<TruthGuardPlan>, activePlanId: String, isAdmin: Boolean, onOpenPlans: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TruthGuard plans", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        plans.forEach { plan ->
            val active = !isAdmin && plan.id == activePlanId
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plan.name, fontWeight = FontWeight.SemiBold)
                            val price = plan.price.entries.joinToString(" • ") { pair ->
                                pair.key.replaceFirstChar { it.uppercase() } + ": $" + pair.value
                            }
                            if (price.isNotBlank()) Text(price, style = MaterialTheme.typography.bodySmall)
                        }
                        if (active) AssistChip(onClick = {}, label = { Text("ACTIVE") })
                    }
                    if (plan.features.isNotEmpty()) Text(plan.features.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val limitText = plan.limits.entries.joinToString(" • ") { pair ->
                        pair.key.replaceFirstChar { it.uppercase() } + ": " + pair.value
                    }
                    if (limitText.isNotBlank()) Text(limitText, style = MaterialTheme.typography.labelSmall)
                    if (plan.trialEligible && plan.trialDays > 0) Text(plan.trialDays.toString() + "-day free trial", style = MaterialTheme.typography.labelMedium)
                    if (!active && !isAdmin) TextButton(onClick = onOpenPlans) { Text("Open plan options") }
                }
            }
        }
    }
}

@Composable
private fun TruthGuardResultCard(result: JSONObject, showSources: Boolean, showReliability: Boolean) {
    val analysis = result.optString("analysis").ifBlank { "No analysis returned." }
    val sources = result.optJSONArray("sources") ?: JSONArray()
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Verification result", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Claim", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(result.optString("claim"))
            HorizontalDivider()
            Text(analysis, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Plan: " + result.optString("planId", "truthguardfree") + " • Charged: " + result.optString("creditsCharged", "0"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (showSources && sources.length() > 0) {
                Text("Sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                for (i in 0 until sources.length()) {
                    val source = sources.optJSONObject(i) ?: continue
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text((i + 1).toString() + ". " + source.optString("title"), fontWeight = FontWeight.SemiBold)
                            if (showReliability) Text("Source-backed evidence retrieved for this check.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val url = source.optString("url")
                            if (url.isNotBlank()) TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("Open source") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TruthGuardHistoryCard(item: JSONObject, onOpen: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.optString("claim"), fontWeight = FontWeight.SemiBold, maxLines = 3)
            Text(
                item.optString("depth", "standard").replaceFirstChar { it.uppercase() } + " • " + item.optString("planId", "truthguardfree"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(item.optString("createdAt", ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.optString("analysis"), maxLines = 4, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TruthGuardToggle(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun parseTruthGuardPlans(body: JSONObject): List<TruthGuardPlan> {
    val array = body.optJSONArray("plans") ?: JSONArray()
    return List(array.length()) { index ->
        val item = array.optJSONObject(index) ?: JSONObject()
        val priceObject = item.optJSONObject("price") ?: JSONObject()
        val price = mutableMapOf<String, String>()
        val priceKeys = priceObject.keys()
        while (priceKeys.hasNext()) {
            val key = priceKeys.next()
            price[key] = priceObject.opt(key).toString()
        }
        val featureArray = item.optJSONArray("features") ?: JSONArray()
        val limitObject = item.optJSONObject("limits") ?: JSONObject()
        val limits = mutableMapOf<String, String>()
        val limitKeys = limitObject.keys()
        while (limitKeys.hasNext()) {
            val key = limitKeys.next()
            limits[key] = limitObject.opt(key).toString()
        }
        TruthGuardPlan(
            id = item.optString("id"),
            name = item.optString("name"),
            price = price,
            features = List(featureArray.length()) { featureArray.optString(it) },
            limits = limits,
            trialDays = item.optInt("trialDays", 0),
            trialEligible = item.optBoolean("trialEligible", false)
        )
    }.filter { it.id.isNotBlank() }
}
