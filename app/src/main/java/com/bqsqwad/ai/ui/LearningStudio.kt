package com.bqsqwad.ai.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bqsqwad.ai.core.WorkspaceApiClient
import com.bqsqwad.ai.core.copyToClipboard
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class LearningPlanUi(
    val id: String,
    val name: String,
    val price: String,
    val features: List<String>,
    val limits: String
)

private data class SubjectInfo(
    val id: String,
    val name: String,
    val icon: String,
    val description: String
)

private val CURATED_SUBJECTS = listOf(
    SubjectInfo("Mathematics", "Mathematics", "🔢", "Algebra, geometry, calculus & problem solving"),
    SubjectInfo("Science", "Science & Physics", "🔬", "Physics, chemistry, biology & scientific method"),
    SubjectInfo("English", "English & Literature", "📖", "Grammar, comprehension, writing & literary analysis"),
    SubjectInfo("Coding", "Coding & Tech", "💻", "Algorithms, Python, web development & logic"),
    SubjectInfo("History", "History & Social Studies", "🌍", "World history, geography, governance & civilisations"),
    SubjectInfo("Faith", "Faith & Christian Studies", "✝️", "Biblical history, theology, Christian thought & ethics"),
    SubjectInfo("Languages", "Languages", "🗣️", "Grammar, vocabulary, translation & conversation"),
    SubjectInfo("General", "General & Study Skills", "🧠", "Critical thinking, memory revision & logic")
)

private data class ModeInfo(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val blurb: String,
    val minPlanRequired: String? = null
)

private val LEARNING_MODES = listOf(
    ModeInfo("Tutor", "Step Tutor", Icons.Default.Psychology, "Adaptive step-by-step guidance"),
    ModeInfo("Lesson", "Full Lesson", Icons.Default.MenuBook, "Structured conceptual lesson"),
    ModeInfo("Revision", "Revision & Notes", Icons.Default.Bolt, "High-yield study summaries & formulas"),
    ModeInfo("Quiz", "Self-Test Quiz", Icons.Default.Quiz, "Interactive test with automated grading"),
    ModeInfo("Exam", "Exam Prep", Icons.Default.Timer, "Past-paper questions & marking rubric", "learnscholar")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompleteLearningStudioWorkspace(
    onOpenPlans: () -> Unit = {},
    onOpenMainChat: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var plans by remember { mutableStateOf<List<LearningPlanUi>>(emptyList()) }
    var activePlanId by remember { mutableStateOf("explorer") }
    var subject by remember { mutableStateOf("Mathematics") }
    var mode by remember { mutableStateOf("Tutor") }
    var goal by remember { mutableStateOf("") }
    var activeTool by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    var isAnswerCached by remember { mutableStateOf(false) }
    var workspaceRedirect by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lessonsCompleted by remember { mutableStateOf(0) }
    var quizzesCompleted by remember { mutableStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Normalize active plan ID to cover both website and mobile variations
    val normalizedPlan = remember(activePlanId) {
        when (activePlanId.lowercase().trim()) {
            "scholar", "learnscholar" -> "learnscholar"
            "academy", "family", "learnfamily" -> "learnfamily"
            "school", "tutor", "learnschool" -> "learnschool"
            "admin-unlimited" -> "admin-unlimited"
            else -> "explorer"
        }
    }

    val isAdminBypass = normalizedPlan == "admin-unlimited"
    val isScholarPlus = normalizedPlan in setOf("learnscholar", "learnfamily", "learnschool") || isAdminBypass
    val isFamilyPlus = normalizedPlan in setOf("learnfamily", "learnschool") || isAdminBypass
    val isSchoolPlus = normalizedPlan == "learnschool" || isAdminBypass

    val activePlan = plans.firstOrNull { it.id == normalizedPlan || it.id == activePlanId }

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
                            val rawVal = configured.opt(key).toString()
                            val shown = if (rawVal.contains("₦")) rawVal else rawVal.toDoubleOrNull()?.let {
                                String.format(java.util.Locale.US, "₦%,.2f", it)
                            } ?: rawVal
                            parts += key.replaceFirstChar { it.uppercase() } + ": " + shown
                        }
                        parts.joinToString(" • ")
                    }.orEmpty().ifBlank {
                        if (p.optBoolean("free", false) || p.optString("id") == "explorer") "Free forever"
                        else "View details in Plans"
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
            }.onFailure {
                error = safeUserFacingIssue(it, "Learning status could not be refreshed.")
            }
        }
    }

    LaunchedEffect(refreshKey) { refresh() }

    fun runSession(customGoal: String? = null, customMode: String? = null, toolFeature: String? = null) {
        val input = (customGoal ?: goal).trim()
        if (input.isBlank() || busy) return
        val effectiveMode = customMode ?: mode
        val effectiveFeature = toolFeature ?: activeTool

        if (effectiveMode.lowercase() in setOf("exam", "timed exam", "past paper") && !isScholarPlus) {
            error = "Exam mode is included with Scholar, Family Academy and School plans."
            return
        }

        busy = true
        error = null
        answer = null
        isAnswerCached = false
        workspaceRedirect = null

        scope.launch {
            runCatching {
                WorkspaceApiClient().learningSession(effectiveMode, subject, input, effectiveFeature)
            }.onSuccess { response ->
                val redirect = response.optJSONObject("redirect")
                if (redirect != null) {
                    workspaceRedirect = redirect
                    answer = null
                } else {
                    workspaceRedirect = null
                    val learningAnswer = response.optString("text").ifBlank { response.optString("answer") }
                    if (learningAnswer.isBlank()) error("Learning Studio returned an empty response. Please retry.")
                    answer = learningAnswer
                    isAnswerCached = response.optBoolean("cached", false)

                    if (input.isNotBlank() && learningAnswer.isNotBlank() && !isAnswerCached) {
                        scope.launch {
                            runCatching {
                                WorkspaceApiClient().submitTrainingContribution(input, learningAnswer, "learning_studio", false)
                            }
                        }
                    }
                }
                refreshKey++
            }.onFailure {
                error = when {
                    it.message?.contains("plan_not_entitled") == true -> "Your Learning plan could not be verified. Please refresh."
                    it.message?.contains("daily_capability_limit_reached") == true -> "You have used your 3 free Explorer lessons today. Upgrade to Scholar for unlimited lessons!"
                    it.message?.contains("learning_feature_locked") == true -> "This premium learning feature is locked on your current plan."
                    else -> safeUserFacingIssue(it, "Learning Studio could not complete the session.")
                }
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Dynamic Plan Hero Banner
        when (normalizedPlan) {
            "learnscholar" -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF1E1B4B),
                    contentColor = Color.White,
                    tonalElevation = 6.dp,
                    border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = CircleShape, color = Color(0xFF4F46E5)) {
                                    Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = Color.White, modifier = Modifier.padding(6.dp).size(20.dp))
                                }
                                Text("Scholar Learning Pro", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            BqLogo(36.dp)
                        }
                        Text("Unlimited AI lessons, exam past-paper drills, homework explanations, and 120 live voice teacher minutes/month.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC7D2FE))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF312E81)) {
                                Text("$lessonsCompleted lessons", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFFA5B4FC), fontWeight = FontWeight.SemiBold)
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF312E81)) {
                                Text("$quizzesCompleted quizzes", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFFA5B4FC), fontWeight = FontWeight.SemiBold)
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF047857)) {
                                Text("Active Plan", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFFD1FAE5), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            "learnfamily" -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF064E3B),
                    contentColor = Color.White,
                    tonalElevation = 6.dp,
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = CircleShape, color = Color(0xFF059669)) {
                                    Icon(Icons.Default.FamilyRestroom, contentDescription = null, tint = Color.White, modifier = Modifier.padding(6.dp).size(20.dp))
                                }
                                Text("Family Academy Hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            BqLogo(36.dp)
                        }
                        Text("Multi-learner tracking, parent progress reports, achievement certificates, and full curriculum access for the household.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA7F3D0))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF065F46)) {
                                Text("$lessonsCompleted lessons", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFF6EE7B7), fontWeight = FontWeight.SemiBold)
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF065F46)) {
                                Text("$quizzesCompleted quizzes", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFF6EE7B7), fontWeight = FontWeight.SemiBold)
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF047857)) {
                                Text("Family Plan Active", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFFD1FAE5), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            "learnschool" -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF1E293B),
                    contentColor = Color.White,
                    tonalElevation = 6.dp,
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = CircleShape, color = Color(0xFFD97706)) {
                                    Icon(Icons.Default.School, contentDescription = null, tint = Color.White, modifier = Modifier.padding(6.dp).size(20.dp))
                                }
                                Text("School & Tutor Command", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            BqLogo(36.dp)
                        }
                        Text("Classroom curriculum mapping, assignment generator, safeguarding reports, and verified institutional certificates.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFDE68A))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF334155)) {
                                Text("$lessonsCompleted lessons", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFFFCD34D), fontWeight = FontWeight.SemiBold)
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF334155)) {
                                Text("$quizzesCompleted quizzes", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFFFCD34D), fontWeight = FontWeight.SemiBold)
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFB45309)) {
                                Text("School Active", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFFFEF3C7), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            "admin-unlimited" -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 6.dp
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Learning Studio • Admin Lab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            BqLogo(36.dp)
                        }
                        Text("All Learning Studio plan gates, modes and features bypassed for validation.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BqStatusBadge("Admin Bypass")
                            BqStatusBadge("$lessonsCompleted lessons")
                            BqStatusBadge("$quizzesCompleted quizzes")
                        }
                    }
                }
            }
            else -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    tonalElevation = 4.dp
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Learning Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Your personal AI study partner for lessons, revision & practice.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            BqLogo(36.dp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            BqStatusBadge("Explorer (Free)")
                            BqStatusBadge("$lessonsCompleted completed")
                            BqStatusBadge("$quizzesCompleted quizzes")
                        }
                        OutlinedButton(
                            onClick = onOpenPlans,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Upgrade to Scholar for Unlimited Lessons")
                        }
                    }
                }
            }
        }

        // Subject Selector
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Subject / Curriculum Area", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CURATED_SUBJECTS) { item ->
                    val selected = subject == item.id
                    FilterChip(
                        selected = selected,
                        onClick = { subject = item.id },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.icon)
                                Text(item.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }

        // Learning Mode
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Learning Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LEARNING_MODES) { item ->
                    val selected = mode == item.id
                    val isGated = item.minPlanRequired == "learnscholar" && !isScholarPlus
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (isGated) {
                                error = "Exam prep is included with Scholar, Family and School plans."
                            } else {
                                mode = item.id
                                activeTool = null
                                error = null
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(item.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (isGated) "${item.name} • Upgrade" else item.name)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondary,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                        )
                    )
                }
            }
        }

        // Dedicated Tools (Fully Interactive)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Plan Learning Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Homework Help
                item {
                    val active = activeTool == "homework_help"
                    FilterChip(
                        selected = active,
                        onClick = {
                            activeTool = if (active) null else "homework_help"
                            if (!active) {
                                if (goal.isBlank()) goal = "Explain how to solve this step-by-step: "
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Homework Solver")
                            }
                        }
                    )
                }

                // Worksheets
                item {
                    val active = activeTool == "worksheets"
                    val locked = !isScholarPlus
                    FilterChip(
                        selected = active,
                        onClick = {
                            if (locked) {
                                error = "Worksheet generation is included in Scholar, Family and School plans."
                            } else {
                                activeTool = if (active) null else "worksheets"
                                if (!active) {
                                    if (goal.isBlank()) goal = "Generate a printable practice worksheet with 5 graded questions and answer key for: "
                                }
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (locked) "Worksheet Gen • Locked" else "Worksheet Gen")
                            }
                        }
                    )
                }

                // Parent Dashboard / Report
                item {
                    val active = activeTool == "parent_dashboard"
                    val locked = !isFamilyPlus
                    FilterChip(
                        selected = active,
                        onClick = {
                            if (locked) {
                                error = "Parent progress reports require Family Academy or School plan."
                            } else {
                                activeTool = if (active) null else "parent_dashboard"
                                if (!active) {
                                    goal = "Generate a comprehensive weekly parent progress summary and study recommendations for our recent lessons in " + subject + "."
                                    runSession(goal, "Lesson", "parent_dashboard")
                                }
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Assessment, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (locked) "Parent Report • Locked" else "Parent Report")
                            }
                        }
                    )
                }

                // Verifiable Certificates
                item {
                    val active = activeTool == "certificates"
                    val locked = !isFamilyPlus
                    FilterChip(
                        selected = active,
                        onClick = {
                            if (locked) {
                                error = "Achievement certificates require Family Academy or School plan."
                            } else {
                                activeTool = if (active) null else "certificates"
                                if (!active) {
                                    val certCode = "BQ-CERT-" + UUID.randomUUID().toString().take(8).uppercase()
                                    goal = "Create an official achievement certificate for mastering core topics in " + subject + ". Include verification code " + certCode + ", skills verified, and commendation statement."
                                    runSession(goal, "Lesson", "certificates")
                                }
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.MilitaryTech, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (locked) "Certificate • Locked" else "Issue Certificate")
                            }
                        }
                    )
                }

                // Teacher Lesson Planner
                item {
                    val active = activeTool == "classes"
                    val locked = !isSchoolPlus
                    FilterChip(
                        selected = active,
                        onClick = {
                            if (locked) {
                                error = "Classroom lesson planning requires School & Tutor plan."
                            } else {
                                activeTool = if (active) null else "classes"
                                if (!active) {
                                    if (goal.isBlank()) goal = "Create a 45-minute structured classroom lesson plan with objectives, board examples, group activity and exit ticket for: "
                                }
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Class, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (locked) "Lesson Planner • Locked" else "Classroom Planner")
                            }
                        }
                    )
                }
            }
        }

        // Prompt Input Area
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = {
                        goal = it
                        error = null
                        if (workspaceRedirect != null) workspaceRedirect = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    shape = RoundedCornerShape(16.dp),
                    label = { Text("What would you like to learn today?") },
                    placeholder = {
                        Text(
                            when (mode) {
                                "Quiz" -> "Enter a topic to generate a self-test quiz (e.g. Photosynthesis, Quadratic Equations, Early Roman Church)..."
                                "Revision" -> "Enter topic for quick high-yield revision formulas and flashcards..."
                                "Exam" -> "Enter topic or past-paper exam question to simulate timed grading..."
                                else -> "Ask a concept question, request a step-by-step breakdown, paste homework, or learn a historical/biblical event..."
                            }
                        )
                    }
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (goal.isNotBlank()) {
                        TextButton(onClick = { goal = ""; answer = null; error = null }) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear")
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Button(
                        onClick = { runSession() },
                        enabled = goal.isNotBlank() && !busy,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Teaching…")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Start Lesson")
                        }
                    }
                }
            }
        }

        // Error Feedback
        error?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null)
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Out of Scope Polite Guidance (Instead of Aggressive Blocking)
        workspaceRedirect?.let { redirect ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Text("General Request Notice", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        redirect.optString("message").ifBlank { "This question appears outside regular academic subjects. Would you like to open Main Chat, or teach the underlying academic concept here?" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenMainChat,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Main Chat")
                        }
                        OutlinedButton(
                            onClick = {
                                val learningFramed = "Explain the educational and conceptual principles behind: " + goal
                                workspaceRedirect = null
                                runSession(learningFramed, "Lesson")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Learn Concept Here")
                        }
                    }
                }
            }
        }

        // Answer & Lesson Display Card
        answer?.let { lessonContent ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 3.dp
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(6.dp).size(18.dp))
                            }
                            Column {
                                Text("BQ Learning Tutor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("$subject • $mode Mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        if (isAnswerCached) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Text("Instant (Saved Study Notes)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Markdown rendering for math, headings, bullets, code
                    MarkdownText(
                        text = lessonContent,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Action buttons
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, "BQ Sqwad AI Lesson - " + subject, lessonContent)
                                Toast.makeText(context, "Lesson notes copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Notes")
                        }

                        Button(
                            onClick = {
                                mode = "Quiz"
                                goal = "Test my understanding of the previous lesson: " + goal
                                runSession(goal, "Quiz")
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Quiz, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Quiz Me")
                        }
                    }
                }
            }
        }

        // Compliance & Disclaimer Footer
        Text(
            "BQ Sqwad AI Learning Studio is powered by advanced educational AI. Curriculum explanations are tailored for structured comprehension and should be verified against school syllabus requirements.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
        )
    }
}
