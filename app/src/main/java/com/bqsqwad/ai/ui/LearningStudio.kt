package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class LearningPlanUi(
    val id: String,
    val name: String,
    val price: String,
    val features: List<String>,
    val limits: String
)

@Composable
fun CompleteLearningStudioWorkspace(onOpenPlans: () -> Unit = {}, onOpenMainChat: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<LearningPlanUi>>(emptyList()) }
    var activePlanId by remember { mutableStateOf("explorer") }
    var subject by remember { mutableStateOf("General") }
    var mode by remember { mutableStateOf("Tutor") }
    var goal by remember { mutableStateOf("") }
    var feature by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    var workspaceRedirect by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lessonsCompleted by remember { mutableStateOf(0) }
    var quizzesCompleted by remember { mutableStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val activePlan = plans.firstOrNull { it.id == activePlanId }
    val isAdminBypass = activePlanId == "admin-unlimited"
    val premium = activePlanId in setOf("learnscholar", "learnfamily", "learnschool") || isAdminBypass
    val family = activePlanId in setOf("learnfamily", "learnschool") || isAdminBypass
    val school = activePlanId == "learnschool" || isAdminBypass

    fun refresh() {
        scope.launch {
            runCatching {
                val api = WorkspaceApiClient()
                val catalog = api.learningCatalog()
                activePlanId = catalog.optString("activePlanId").ifBlank { "explorer" }
                val raw = catalog.optJSONArray("plans") ?: JSONArray()
                plans = List(raw.length()) { i ->
                    val p = raw.optJSONObject(i) ?: JSONObject()
                    val priceNgn = p.optJSONObject("priceNgn")
                    val price = priceNgn?.let { configured ->
                        val parts = mutableListOf<String>()
                        configured.keys().forEach { key ->
                            val raw = configured.opt(key).toString()
                            val shown = if (raw.contains("₦")) raw else raw.toDoubleOrNull()?.let {
                                String.format(java.util.Locale.US, "₦%,.2f", it)
                            } ?: raw
                            parts += key.replaceFirstChar { it.uppercase() } + ": " + shown
                        }
                        parts.joinToString(" • ")
                    }.orEmpty().ifBlank {
                        if (p.optBoolean("free", false) || p.optString("id") == "explorer") "Free"
                        else "View localized price in Plans & Credits"
                    }
                    val features = p.optJSONArray("features") ?: JSONArray()
                    LearningPlanUi(
                        p.optString("id"),
                        p.optString("name"),
                        price,
                        List(features.length()) { features.optString(it) },
                        p.optJSONObject("limits")?.toString()?.removePrefix("{")?.removeSuffix("}") ?: ""
                    )
                }
                val progress = api.learningProgress()
                lessonsCompleted = progress.optInt("lessonsCompleted", 0)
                quizzesCompleted = progress.optInt("quizzesCompleted", 0)
            }.onFailure { error = safeUserFacingIssue(it, "Learning data could not be loaded.") }
        }
    }

    LaunchedEffect(refreshKey) { refresh() }

    fun runSession() {
        val input = goal.trim()
        if (input.isBlank() || busy) return
        if (mode.lowercase() in setOf("exam", "timed exam") && !premium) {
            error = "Exam mode is included with Scholar, Family Academy and School & Tutor."
            return
        }
        if (feature == "parent_dashboard" && !family) {
            error = "Parent Dashboard is included with Family Academy and School & Tutor."
            return
        }
        if (feature == "teacher_dashboard" && !school) {
            error = "Teacher Dashboard is included with School & Tutor."
            return
        }
        busy = true
        error = null
        answer = null
        workspaceRedirect = null
        scope.launch {
            runCatching {
                WorkspaceApiClient().learningSession(mode, subject, input, feature)
            }.onSuccess { response ->
                val redirect = response.optJSONObject("redirect")
                if (redirect != null) {
                    workspaceRedirect = redirect
                    answer = null
                } else {
                    workspaceRedirect = null
                    val learningAnswer = response.optString("text").ifBlank { response.optString("answer") }
                    answer = learningAnswer
                    if (input.trim().isNotBlank() && learningAnswer.isNotBlank()) {
                        val trainingPrompt = input.trim()
                        scope.launch {
                            runCatching {
                                WorkspaceApiClient().submitTrainingContribution(trainingPrompt, learningAnswer, "learning_studio", false)
                            }
                        }
                    }
                }
                refreshKey++
            }.onFailure {
                error = when {
                    it.message?.contains("plan_not_entitled") == true -> "This Learning plan is not active."
                    it.message?.contains("monthly_capability_limit_reached") == true -> "This plan has reached its monthly Learning limit."
                    it.message?.contains("daily_capability_limit_reached") == true -> "You have reached today's Learning limit."
                    it.message?.contains("learning_feature_locked") == true -> "That Learning feature is locked on your current plan."
                    else -> safeUserFacingIssue(it, "Learning Studio could not complete the session.")
                }
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            tonalElevation = 4.dp
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (activePlanId) {
                                "learnscholar" -> "Scholar Learning"
                                "learnfamily" -> "Family Learning Hub"
                                "learnschool" -> "School Learning Command"
                                "admin-unlimited" -> "Learning Studio • Admin Lab"
                                else -> "Learning Studio"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            when (activePlanId) {
                                "learnscholar" -> "Focused tutoring, guided revision and exam-ready practice."
                                "learnfamily" -> "A shared learning space with family progress and learner support."
                                "learnschool" -> "A structured academic hub for classes, teachers and learning progress."
                                "admin-unlimited" -> "Full-access workspace for validating Learning tools and plan gates."
                                else -> "Your study dashboard for tutoring, lessons, revision, quizzes and progress."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BqLogo(46.dp)
                }
                Text(
                    if (isAdminBypass) "Full access" else (activePlan?.name ?: "Explorer"),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isAdminBypass) "All Learning gates are bypassed for testing."
                    else activePlan?.features?.take(5)?.joinToString(" • ") ?: "3 AI lessons/day • Any curriculum/subject • Practice • Marking",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("$lessonsCompleted lessons") })
                    AssistChip(onClick = {}, label = { Text("$quizzesCompleted quizzes") })
                }
                if (!isAdminBypass) {
                    OutlinedButton(onClick = onOpenPlans, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CreditCard, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("View Learning plans")
                    }
                }
            }
        }

        Text("Learning mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Tutor", "Lesson", "Revision", "Quiz", "Exam")) { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { mode = item; feature = null },
                    label = { Text(if (item == "Exam" && !premium) "Exam • Upgrade" else item) }
                )
            }
        }

        Text("Subject", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("General", "Mathematics", "Science", "English", "Social Studies", "Computer Science")) { item ->
                FilterChip(selected = subject == item, onClick = { subject = item }, label = { Text(item) })
            }
        }

        Text("Plan features", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val features = listOf(
                "Homework help" to premium,
                "Live teacher" to premium,
                "Worksheets" to premium,
                "Parent dashboard" to family,
                "Certificates" to family,
                "Teacher dashboard" to school,
                "Classes" to school
            )
            items(features) { (label, enabled) ->
                FilterChip(
                    selected = feature == label.lowercase().replace(" ", "_"),
                    onClick = {
                        val key = label.lowercase().replace(" ", "_")
                        if (enabled) feature = if (feature == key) null else key
                        else error = "Unlock $label with a higher Learning plan."
                    },
                    label = { Text(if (enabled) label else "$label • Locked") }
                )
            }
        }

        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            label = { Text("What do you want to learn?") },
            placeholder = { Text("Ask for a lesson, explain a topic, practise questions, revise for an exam, or work through a problem.") }
        )

        Button(
            onClick = ::runSession,
            enabled = goal.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(if (busy) Icons.Default.HourglassTop else Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Preparing lesson…" else "Start Learning")
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        workspaceRedirect?.let { redirect ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(redirect.optString("message").ifBlank { "This request belongs in Main Chat." }, fontWeight = FontWeight.Medium)
                    Button(
                        onClick = onOpenMainChat,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open Main Chat")
                    }
                }
            }
        }

        answer?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 3.dp
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Learning session", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(it)
                }
            }
        }

        Text(
            "Usage is charged through the BQ Sqwad AI credit system. Plan limits and feature access are enforced by the server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
