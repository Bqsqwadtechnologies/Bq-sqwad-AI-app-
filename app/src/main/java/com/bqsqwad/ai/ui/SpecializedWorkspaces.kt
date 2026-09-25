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

@Composable
fun TruthGuardWorkspace() {
    val scope = rememberCoroutineScope()
    var claim by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf("Standard") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<String?>(null) }
    var sources by remember { mutableStateOf<List<SearchSource>>(emptyList()) }

    fun verify() {
        val input = claim.trim()
        if (input.isBlank() || busy) return
        busy = true
        error = null
        analysis = null
        sources = emptyList()
        scope.launch {
            runCatching {
                val api = WorkspaceApiClient()
                val research = api.workspaceResearch(input, deepResearch = depth == "Deep")
                val evidence = research.sources.joinToString("\n\n") {
                    "Source: ${it.title}\nURL: ${it.url}\n${it.snippet.orEmpty()}"
                }
                val system = """You are TruthGuard AI. Evaluate the supplied claim using the evidence.
Separate supported evidence, conflicting evidence, and information that cannot be established.
Do not invent sources. State uncertainty clearly.
Evidence:
$evidence"""
                api.workspaceAi("truth_guard", input, system)
            }.onSuccess {
                analysis = it.optString("text").ifBlank { it.optString("answer") }
                val research = WorkspaceApiClient().workspaceResearch(input, deepResearch = false)
                sources = research.sources
            }.onFailure { error = it.message ?: "TruthGuard could not complete the check." }
            busy = false
        }
    }

    WorkspaceFrame(
        title = "TruthGuard AI",
        subtitle = "Evidence-first claim checking with source-aware analysis.",
        icon = Icons.Default.Verified
    ) {
        Text("Claim to check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = claim,
            onValueChange = { claim = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            placeholder = { Text("Enter a claim, statement, headline or question…") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Standard", "Deep").forEach {
                FilterChip(selected = depth == it, onClick = { depth = it }, label = { Text(it) })
            }
        }
        Button(
            onClick = ::verify,
            enabled = claim.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Checking evidence…" else "Check with TruthGuard") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        analysis?.let {
            SurfaceCard("Analysis", it)
        }
        if (sources.isNotEmpty()) {
            Text("Evidence sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            sources.forEachIndexed { index, source ->
                SurfaceCard(
                    "[${index + 1}] ${source.title}",
                    listOfNotNull(source.snippet, source.url).joinToString("\n")
                )
            }
        }
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

@Composable
private fun WorkspaceFrame(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content
                    )
                }
            }
        )
    }
}
