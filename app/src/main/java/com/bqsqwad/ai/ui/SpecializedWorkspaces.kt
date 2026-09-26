package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.SearchSource
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class WorkspaceTurn(val user: Boolean, val text: String)

data class TruthGuardHistoryItem(
    val id: String,
    val claim: String,
    val analysis: String,
    val createdAt: String
)

private data class TruthGuardPlanUi(
    val id: String,
    val name: String,
    val checks: String,
    val features: List<String>,
    val deep: Boolean,
    val export: Boolean,
    val team: Boolean
)

private val truthGuardPlansUi = listOf(
    TruthGuardPlanUi(
        "truthguardfree", "TruthGuard Free", "5 checks/month",
        listOf("Verdict", "Confidence", "Plain-English explanation", "Up to 3 cited sources/check"),
        deep = false, export = false, team = false
    ),
    TruthGuardPlanUi(
        "tgplus", "TruthGuard Plus", "150 checks/month",
        listOf("Live web evidence", "Source reliability notes", "Manipulation/bias signals", "History"),
        deep = true, export = false, team = false
    ),
    TruthGuardPlanUi(
        "tgpro", "TruthGuard Pro", "1,000 checks/month",
        listOf("Deep evidence sweep", "More sources", "Long-article claim breakdown", "Export-ready summaries", "Priority processing"),
        deep = true, export = true, team = false
    ),
    TruthGuardPlanUi(
        "tgteam", "TruthGuard Team", "Fair-use unlimited",
        listOf("Everything in Pro", "Shared organization history", "Priority support"),
        deep = true, export = true, team = true
    )
)

@Composable
fun TruthGuardWorkspace(onOpenPlans: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var claim by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf("Standard") }
    var busy by remember { mutableStateOf(false) }
    var loadingPlan by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<String?>(null) }
    var sources by remember { mutableStateOf<List<SearchSource>>(emptyList()) }
    var history by remember { mutableStateOf<List<TruthGuardHistoryItem>>(emptyList()) }
    var selectedHistory by remember { mutableStateOf<TruthGuardHistoryItem?>(null) }
    var activePlanId by remember { mutableStateOf("truthguardfree") }

    val activePlan = truthGuardPlansUi.firstOrNull { it.id == activePlanId } ?: truthGuardPlansUi.first()

    fun refreshWorkspace() {
        scope.launch {
            loadingPlan = true
            runCatching {
                val api = WorkspaceApiClient()
                val catalog = api.nativeCatalog()
                val entitlements = api.entitlements()
                val items = entitlements.optJSONArray("items") ?: org.json.JSONArray()
                val activeIds = buildSet {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val state = item.optString("state")
                        if (state in setOf("active", "grace", "on_hold", "paused")) {
                            item.optString("planId").takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                activePlanId = listOf("tgteam", "tgpro", "tgplus", "truthguardfree")
                    .firstOrNull { activeIds.contains(it) }
                    ?: "truthguardfree"
                val catalogPlans = catalog.optJSONArray("plans") ?: org.json.JSONArray()
                if (catalogPlans.length() == 0) error("TruthGuard catalog is unavailable.")
                history = api.truthGuardHistory().mapNotNull { row ->
                    TruthGuardHistoryItem(
                        id = row.optString("id"),
                        claim = row.optString("claim"),
                        analysis = row.optString("analysis"),
                        createdAt = row.optString("createdAt")
                    ).takeIf { it.claim.isNotBlank() && it.analysis.isNotBlank() }
                }
            }.onFailure {
                error = it.message ?: "Could not refresh TruthGuard access."
            }
            loadingPlan = false
        }
    }

    fun verify() {
        val input = claim.trim()
        if (input.isBlank() || busy) return
        if (depth == "Deep" && !activePlan.deep) {
            error = "Deep evidence is included with TruthGuard Plus and above."
            return
        }
        busy = true
        error = null
        analysis = null
        sources = emptyList()
        scope.launch {
            runCatching {
                val api = WorkspaceApiClient()
                val research = api.workspaceResearch(input, deepResearch = depth == "Deep")
                sources = research.sources
                val evidence = research.sources.joinToString("\n\n") {
                    "Source: ${it.title}\nURL: ${it.url}\n${it.snippet.orEmpty()}"
                }
                val system = """You are TruthGuard AI.
Evaluate the supplied claim using the evidence.
Separate supported evidence, conflicting evidence, and information that cannot be established.
Do not invent sources. State uncertainty clearly.
TruthGuard plan: ${activePlan.name}
Plan capabilities: ${activePlan.features.joinToString(", ")}
Evidence:
$evidence"""
                val response = api.workspaceAi("truth_guard", input, system)
                val text = response.optString("text").ifBlank { response.optString("answer") }
                api.saveTruthGuardCheck(input, text, research.sources)
                text
            }.onSuccess {
                analysis = it
                refreshWorkspace()
            }.onFailure {
                error = it.message ?: "TruthGuard could not complete the check."
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refreshWorkspace() }

    WorkspaceFrame(
        title = "TruthGuard AI",
        style = WorkspaceVisualStyle.TRUTH_GUARD,
        subtitle = "Evidence-first claim checking with a plan-aware verification workspace.",
        icon = Icons.Default.Verified
    ) {
        if (loadingPlan) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 2.dp
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(activePlan.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(activePlan.checks, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    if (activePlan.id != "truthguardfree") {
                        AssistChip(onClick = {}, label = { Text("ACTIVE") })
                    }
                }
                Text(
                    activePlan.features.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (activePlan.id == "truthguardfree") {
                    OutlinedButton(onClick = onOpenPlans, modifier = Modifier.fillMaxWidth()) {
                        Text("View TruthGuard plans")
                    }
                }
            }
        }

        Text("Verification mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = depth == "Standard",
                onClick = { depth = "Standard" },
                label = { Text("Standard") }
            )
            FilterChip(
                selected = depth == "Deep",
                onClick = { if (activePlan.deep) depth = "Deep" else error = "Deep evidence is locked on your current plan." },
                label = { Text(if (activePlan.deep) "Deep" else "Deep • Upgrade") }
            )
        }

        Text("Claim to check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = claim,
            onValueChange = { claim = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            placeholder = { Text("Enter a claim, statement, headline or question…") }
        )
        Button(
            onClick = ::verify,
            enabled = claim.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Checking evidence…" else "Check with TruthGuard") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        analysis?.let { SurfaceCard("Latest TruthGuard analysis", it) }

        if (sources.isNotEmpty()) {
            Text("Evidence sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            sources.forEachIndexed { index, source ->
                SurfaceCard(
                    "[${index + 1}] ${source.title}",
                    listOfNotNull(source.snippet, source.url).joinToString("\n")
                )
            }
        }

        Text("Saved verification history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (history.isEmpty()) {
            Text("Your completed checks will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            history.take(20).forEach { item ->
                OutlinedCard(
                    onClick = { selectedHistory = item },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.claim, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(item.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        selectedHistory?.let { item ->
            AlertDialog(
                onDismissRequest = { selectedHistory = null },
                confirmButton = { TextButton(onClick = { selectedHistory = null }) { Text("Close") } },
                title = { Text("TruthGuard result") },
                text = { Text(item.analysis) }
            )
        }
    }
}


@Composable
fun FrontierLabWorkspace() {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("Experiment") }
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runExperiment() {
        val input = prompt.trim()
        if (input.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                WorkspaceApiClient().workspaceAi(
                    "frontier_lab",
                    input,
                    """You are BQ Sqwad AI Frontier Lab.
Mode: $mode
Explore advanced AI ideas, prototypes, technical reasoning and structured experiments.
Separate established facts from hypotheses. Do not claim an experiment was executed unless the backend actually executed it."""
                )
            }.onSuccess { result = it.optString("text").ifBlank { it.optString("answer") } }
                .onFailure { error = it.message ?: "Frontier Lab could not complete the experiment." }
            busy = false
        }
    }

    WorkspaceFrame(
        title = "Frontier Lab",
        subtitle = "Advanced experimentation, technical exploration and structured AI research.",
        style = WorkspaceVisualStyle.FRONTIER,
        icon = Icons.Default.Science
    ) {
        Text("Experiment mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Experiment", "Prototype", "Research", "Architecture").forEach {
                FilterChip(selected = mode == it, onClick = { mode = it }, label = { Text(it) })
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            placeholder = { Text("Describe the AI experiment, prototype or research problem…") }
        )
        Button(onClick = ::runExperiment, enabled = prompt.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Running…" else "Run in Frontier Lab")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { SurfaceCard("Lab result", it) }
    }
}

@Composable
fun LearningStudioWorkspace() {
    val scope = rememberCoroutineScope()
    var subject by remember { mutableStateOf("General") }
    var goal by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Tutor") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runLearning() {
        val request = goal.trim()
        if (request.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                WorkspaceApiClient().workspaceAi(
                    "learning_studio",
                    request,
                    """You are Learning Studio, an age-appropriate AI tutor.
Subject: $subject
Mode: $mode
Teach step by step, check understanding, and do not simply give answers when a learner needs to learn the method."""
                )
            }.onSuccess { result = it.optString("text") }
                .onFailure { error = it.message ?: "Learning Studio could not respond." }
            busy = false
        }
    }

    WorkspaceFrame(
        title = "Learning Studio",
        style = WorkspaceVisualStyle.LEARNING,
        subtitle = "Tutor, lessons, homework support, revision and progress-oriented learning.",
        icon = Icons.Default.School
    ) {
        Text("Learning mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Tutor", "Lesson", "Revision", "Quiz").forEach {
                FilterChip(selected = mode == it, onClick = { mode = it }, label = { Text(it) })
            }
        }
        Text("Subject")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("General", "Science", "English", "Social Studies").forEach {
                FilterChip(selected = subject == it, onClick = { subject = it }, label = { Text(it) })
            }
        }
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            placeholder = { Text("What do you want to learn or practise?") }
        )
        Button(onClick = ::runLearning, enabled = goal.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Preparing…" else "Start learning")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { SurfaceCard("Learning response", it) }
    }
}

@Composable
fun LifeMattersWorkspace() {
    val scope = rememberCoroutineScope()
    var focus by remember { mutableStateOf("Planning") }
    var request by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runLife() {
        val input = request.trim()
        if (input.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                WorkspaceApiClient().workspaceAi(
                    "life_matters",
                    input,
                    """You are Life Matters, a practical personal planning assistant.
Focus area: $focus
Give realistic, supportive, actionable guidance. Do not claim to be a professional or make high-stakes decisions for the user."""
                )
            }.onSuccess { result = it.optString("text") }
                .onFailure { error = it.message ?: "Life Matters could not respond." }
            busy = false
        }
    }

    WorkspaceFrame(
        title = "Life Matters",
        style = WorkspaceVisualStyle.LIFE,
        subtitle = "Goals, routines, planning, practical guidance and personal organization.",
        icon = Icons.Default.FavoriteBorder
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Planning", "Goals", "Routines", "Tasks").forEach {
                FilterChip(selected = focus == it, onClick = { focus = it }, label = { Text(it) })
            }
        }
        OutlinedTextField(
            value = request,
            onValueChange = { request = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            placeholder = { Text("What would you like help organizing?") }
        )
        Button(onClick = ::runLife, enabled = request.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Planning…" else "Get practical help")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { SurfaceCard("Your plan", it) }
    }
}

@Composable
fun PersonalIntelligenceWorkspace() {
    val scope = rememberCoroutineScope()
    var focus by remember { mutableStateOf("Projects") }
    var request by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runPersonal() {
        val input = request.trim()
        if (input.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                val api = WorkspaceApiClient()
                val memory = runCatching { api.memory() }.getOrDefault(JSONObject())
                api.workspaceAi(
                    "personal_intelligence",
                    input,
                    """You are Personal Intelligence, a private organization assistant.
Focus: $focus
Use account memory only when supplied and relevant. Never invent personal facts.
Available memory context:
${memory.optString("context", "")}"""
                )
            }.onSuccess { result = it.optString("text") }
                .onFailure { error = it.message ?: "Personal Intelligence could not respond." }
            busy = false
        }
    }

    WorkspaceFrame(
        title = "Personal Intelligence",
        style = WorkspaceVisualStyle.PERSONAL,
        subtitle = "Organize projects, goals, notes and personal context with account-controlled memory.",
        icon = Icons.Default.Psychology
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Projects", "Goals", "Notes", "Memory").forEach {
                FilterChip(selected = focus == it, onClick = { focus = it }, label = { Text(it) })
            }
        }
        OutlinedTextField(
            value = request,
            onValueChange = { request = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            placeholder = { Text("Ask Personal Intelligence to organize something…") }
        )
        Button(onClick = ::runPersonal, enabled = request.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Organizing…" else "Organize")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { SurfaceCard("Workspace result", it) }
    }
}

private enum class WorkspaceVisualStyle {
    TRUTH_GUARD, LEARNING, LIFE, PERSONAL, FRONTIER
}

@Composable
private fun WorkspaceFrame(
    title: String,
    subtitle: String,
    style: WorkspaceVisualStyle,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (style) {
        WorkspaceVisualStyle.TRUTH_GUARD -> scheme.tertiaryContainer
        WorkspaceVisualStyle.LEARNING -> scheme.primaryContainer
        WorkspaceVisualStyle.LIFE -> scheme.secondaryContainer
        WorkspaceVisualStyle.PERSONAL -> scheme.surfaceVariant
        WorkspaceVisualStyle.FRONTIER -> scheme.primaryContainer
    }
    val onContainer = when (style) {
        WorkspaceVisualStyle.TRUTH_GUARD -> scheme.onTertiaryContainer
        WorkspaceVisualStyle.LEARNING -> scheme.onPrimaryContainer
        WorkspaceVisualStyle.LIFE -> scheme.onSecondaryContainer
        WorkspaceVisualStyle.PERSONAL -> scheme.onSurfaceVariant
        WorkspaceVisualStyle.FRONTIER -> scheme.onPrimaryContainer
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(icon, contentDescription = null)
                        Column {
                            Text(title, fontWeight = FontWeight.Bold)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = container,
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(icon, contentDescription = null, tint = onContainer)
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = onContainer)
                        }
                        Text(
                            when (style) {
                                WorkspaceVisualStyle.TRUTH_GUARD -> "Evidence workspace • claims → research → sources → analysis"
                                WorkspaceVisualStyle.LEARNING -> "Study workspace • learn → practise → revise → measure"
                                WorkspaceVisualStyle.LIFE -> "Planning workspace • goals → routines → tasks → action"
                                WorkspaceVisualStyle.PERSONAL -> "Private workspace • projects → notes → memory → organization"
                                WorkspaceVisualStyle.FRONTIER -> "Lab workspace • experiments → prototypes → research → architecture"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = onContainer
                        )
                    }
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}
