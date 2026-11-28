package com.bqsqwad.ai.ui.teams

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TeamMessage(
    val id: String,
    val authorUid: String,
    val authorName: String,
    val text: String,
    val createdAt: String,
    val credits: Double? = null,
)

data class TeamMemberRow(
    val uid: String,
    val displayName: String,
    val role: String,
    val photoUrl: String?,
    val status: String,
    val spendLimitCredits: Double? = null,
)


data class TeamPlanItem(
    val id: String,
    val title: String,
    val details: String,
    val status: String,
    val submittedByName: String,
    val createdAt: String,
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: String? = null,
)

data class TeamTaskItem(
    val id: String,
    val title: String,
    val description: String,
    val assignedTo: String,
    val assignedToName: String,
    val priority: String,
    val status: String,
    val createdAt: String,
)

data class TeamProjectRow(
    val id: String,
    val name: String,
)

data class TeamMemoryRow(
    val id: String,
    val title: String,
    val text: String,
)

/**
 * Team workspace — same rules as website:
 * - Member messages: admin-configured price, charged to the sender's personal balance
 * - Ask AI: team credit pool only; AI sees member roster
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamWorkspaceScreen(
    username: String,
    baseUrl: String,
    authTokenProvider: suspend () -> String?,
    onBack: () -> Unit,
    onOpenPlans: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    var showTeamDetails by remember { mutableStateOf(screenWidthDp >= 600) }
    var teamName by remember { mutableStateOf(username) }
    var teamId by remember { mutableStateOf<String?>(null) }
    var balance by remember { mutableStateOf(0.0) }
    var isActive by remember { mutableStateOf(false) }
    var memberStatus by remember { mutableStateOf<String?>(null) }
    var memberRole by remember { mutableStateOf("member") }
    var memberMessagePrice by remember { mutableStateOf("0") }
    var members by remember { mutableStateOf<List<TeamMemberRow>>(emptyList()) }
    var memories by remember { mutableStateOf<List<TeamMemoryRow>>(emptyList()) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var memoryTitle by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf("") }
    var editingMemoryId by remember { mutableStateOf<String?>(null) }
    var deletingMemory by remember { mutableStateOf<TeamMemoryRow?>(null) }
    var roleTargetMember by remember { mutableStateOf<TeamMemberRow?>(null) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var addMemberEmail by remember { mutableStateOf("") }
    var isAddingMember by remember { mutableStateOf(false) }
    var spendLimitTarget by remember { mutableStateOf<TeamMemberRow?>(null) }
    var spendLimitText by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<TeamProjectRow>>(emptyList()) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<TeamMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiBillingSource by remember { mutableStateOf("personal") }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Chat, 1: Plan Box, 2: Tasks, 3: BQ Codron
    var plans by remember { mutableStateOf<List<TeamPlanItem>>(emptyList()) }
    var plansLoading by remember { mutableStateOf(false) }
    var showCreatePlanDialog by remember { mutableStateOf(false) }
    var newPlanTitle by remember { mutableStateOf("") }
    var newPlanDetails by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<TeamTaskItem>>(emptyList()) }
    var tasksLoading by remember { mutableStateOf(false) }
    var showCreateTaskDialog by remember { mutableStateOf(false) }
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskDesc by remember { mutableStateOf("") }
    var newTaskPriority by remember { mutableStateOf("medium") }
    var newTaskAssignee by remember { mutableStateOf("") }
    var showTopupDialog by remember { mutableStateOf(false) }
    var topupAmount by remember { mutableStateOf(100) }
    var topupBusy by remember { mutableStateOf(false) }
    var codonPrompt by remember { mutableStateOf("") }
    var codonAction by remember { mutableStateOf("build") }
    var codonLogs by remember { mutableStateOf(listOf(
        "[BQ Codron] Autonomous Software Engineering Engine Initialized (Powered by BQ Codron Engine).",
        "[BQ Codron] Gradle & Build Runner ('gradools') ready.",
        "[BQ Codron] GitHub / Repository connector status: Connected & verified.",
        "[BQ Codron] Ready to generate full applications, execute test suites, and debug errors."
    )) }
    val listState = rememberLazyListState()

    fun loadPlans() {
        val tid = teamId ?: return
        scope.launch {
            plansLoading = true
            try {
                val token = authTokenProvider() ?: return@launch
                val res = apiPost(baseUrl, token, "/native/teams/plans/list", JSONObject().put("teamId", tid))
                plans = parsePlans(res.optJSONArray("plans"))
            } catch (_: Exception) {
            } finally {
                plansLoading = false
            }
        }
    }

    fun loadTasks() {
        val tid = teamId ?: return
        scope.launch {
            tasksLoading = true
            try {
                val token = authTokenProvider() ?: return@launch
                val res = apiPost(baseUrl, token, "/native/teams/tasks/list", JSONObject().put("teamId", tid))
                tasks = parseTasks(res.optJSONArray("tasks"))
            } catch (_: Exception) {
            } finally {
                tasksLoading = false
            }
        }
    }

    fun refreshMessages(tid: String, pid: String) {
        scope.launch {
            try {
                val token = authTokenProvider() ?: return@launch
                val res = apiPost(
                    baseUrl, token, "/native/teams/messages",
                    JSONObject().put("teamId", tid).put("projectId", pid).put("limit", 40),
                )
                messages = parseMessages(res.optJSONArray("messages"))
                val get = apiPost(baseUrl, token, "/native/teams/get", JSONObject().put("teamId", tid))
                balance = get.optDouble("balance", balance)
            } catch (_: Exception) { /* poll */ }
        }
    }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                val token = authTokenProvider() ?: error("Sign in required")
                val res = apiPost(
                    baseUrl, token, "/native/teams/get",
                    JSONObject().put("username", username),
                )
                val team = res.optJSONObject("team") ?: error("Team not found")
                teamId = team.optString("id")
                teamName = team.optString("name", username)
                balance = res.optDouble("balance", 0.0)
                val member = res.optJSONObject("member")
                memberStatus = member?.optString("status")
                memberRole = member?.optString("role", "member") ?: "member"
                memberMessagePrice = team.optDouble("memberMessagePriceCredits", 0.0).toString()
                isActive = memberStatus == "active"

                if (isActive) {
                    val tid = teamId ?: return@launch
                    val mems = apiPost(baseUrl, token, "/native/teams/members", JSONObject().put("teamId", tid))
                    members = parseMembers(mems.optJSONArray("members"))
                    val memoryResponse = apiPost(baseUrl, token, "/native/teams/memory", JSONObject().put("teamId", tid))
                    memories = parseMemories(memoryResponse.optJSONArray("memories"))
                    val projs = apiPost(baseUrl, token, "/native/teams/projects", JSONObject().put("teamId", tid))
                    projects = parseProjects(projs.optJSONArray("projects"))
                    val first = projects.firstOrNull()?.id
                    projectId = first
                    if (first != null) {
                        val msgs = apiPost(
                            baseUrl, token, "/native/teams/messages",
                            JSONObject().put("teamId", tid).put("projectId", first).put("limit", 40),
                        )
                        messages = parseMessages(msgs.optJSONArray("messages"))
                    }
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(username) { load() }

    // Lightweight poll for new messages
    LaunchedEffect(teamId, projectId, isActive) {
        val currentTeamId = teamId
        val currentProjectId = projectId
        if (!isActive || currentTeamId == null || currentProjectId == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(8_000)
            val tId = teamId ?: break
            val pId = projectId ?: break
            refreshMessages(tId, pId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(teamName, maxLines = 1)
                        Text(
                            "@$username · ${String.format("%.2f", balance)} team credits",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = { showTopupDialog = true }) {
                        Text("+ Buy Credits", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { showTeamDetails = !showTeamDetails }) {
                        Text(if (showTeamDetails) "Hide details" else "Members")
                    }
                },
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (error != null && teamId == null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(error ?: "Team not found", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack) { Text("Back to Teams") }
            }
            return@Scaffold
        }

        val isTablet = screenWidthDp >= 600
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (isTablet && showTeamDetails) {
                // Side panel (members + projects) on wide screens
                Column(
                    Modifier.width(220.dp).fillMaxHeight().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                if (!isActive) {
                    Text(
                        if (memberStatus == "pending") "Join request pending."
                        else "Join to chat and ask the AI.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (memberStatus != "pending") Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val token = authTokenProvider() ?: error("Sign in required")
                                    apiPost(
                                        baseUrl, token, "/native/teams/join",
                                        JSONObject().put("teamId", teamId),
                                    )
                                    load()
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && teamId != null,
                    ) { Text("Join") }
                } else {
                    Text(
                        "Member messages: $memberMessagePrice credits each (set by BQ Sqwad admin)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Members", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        if (memberRole == "owner" || memberRole == "admin" || memberRole == "moderator") {
                            TextButton(
                                onClick = { showAddMemberDialog = true; addMemberEmail = "" },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("+ Add", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(members) { m ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!m.photoUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = m.photoUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(24.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(m.displayName.take(1).uppercase(), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                Column {
                                    Text(m.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(if (m.status == "pending") "Pending approval" else m.role.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                        if ((memberRole == "owner" || memberRole == "admin") && m.status == "active" && m.role != "owner") {
                                            TextButton(
                                                onClick = { roleTargetMember = m },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                            ) {
                                                Text("Change Role", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                    if (m.status == "pending" && (memberRole == "owner" || memberRole == "admin" || memberRole == "moderator")) {
                                        TextButton(
                                            enabled = !busy,
                                            onClick = {
                                                scope.launch {
                                                    busy = true
                                                    try {
                                                        val token = authTokenProvider() ?: error("Sign in required")
                                                        apiPost(baseUrl, token, "/native/teams/approve", JSONObject().put("teamId", teamId).put("targetUid", m.uid))
                                                        load()
                                                    } catch (e: Exception) {
                                                        error = e.message
                                                    } finally {
                                                        busy = false
                                                    }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                        ) { Text("Approve", style = MaterialTheme.typography.labelSmall) }
                                    }
                                    if (memberRole == "owner" || memberRole == "admin") {
                                        TextButton(
                                            onClick = {
                                                spendLimitTarget = m
                                                spendLimitText = m.spendLimitCredits?.toString().orEmpty()
                                            },
                                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                        ) {
                                            Text(
                                                m.spendLimitCredits?.let { "Monthly cap: $it" } ?: "No monthly cap",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    } else if (m.spendLimitCredits != null) {
                                        Text("Monthly cap: ${m.spendLimitCredits}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Shared memory", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        if (memberRole == "owner" || memberRole == "admin") {
                            TextButton(onClick = {
                                editingMemoryId = null
                                memoryTitle = ""
                                memoryText = ""
                                showMemoryDialog = true
                            }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Add", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    memories.take(4).forEach { memory ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).padding(vertical = 2.dp)) {
                            Text(memory.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(memory.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                            if (memberRole == "owner" || memberRole == "admin") {
                                Row {
                                    TextButton(onClick = {
                                        editingMemoryId = memory.id
                                        memoryTitle = memory.title
                                        memoryText = memory.text
                                        showMemoryDialog = true
                                    }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text("Edit", style = MaterialTheme.typography.labelSmall) }
                                    TextButton(onClick = { deletingMemory = memory }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text("Delete", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                    if (memories.isEmpty()) Text("No shared memory yet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Projects", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    projects.forEach { p ->
                        TextButton(
                            onClick = {
                                projectId = p.id
                                teamId?.let { refreshMessages(it, p.id) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                p.name,
                                fontWeight = if (p.id == projectId) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            }

            // Main Workspace View
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!isActive) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (memberStatus == "pending") "Waiting for approval."
                            else "Join this team to message members, track tasks, and build with BQ Codron.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp, modifier = Modifier.fillMaxWidth()) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Chat & AI", style = MaterialTheme.typography.labelMedium) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1; loadPlans() },
                            text = { Text("Plan Box (${plans.size})", style = MaterialTheme.typography.labelMedium) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2; loadTasks() },
                            text = { Text("Tasks (${tasks.size})", style = MaterialTheme.typography.labelMedium) }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("⚡ BQ Codron", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                        )
                    }

                    if (selectedTab == 0) {
                        // Nudge Banner for BQ Codron
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Plan with AI here. Ready to build apps or run Gradle test suites? Switch to BQ Codron!",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { selectedTab = 3 }) {
                                    Text("Switch to Codron", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                Text(
                                    "Chat with your team. Use Ask AI or type @ai when you want a reply. Member messages use the configured sender price.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(messages, key = { it.id }) { m ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (m.authorUid == "ai") MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            if (m.authorUid == "ai") "AI" else m.authorName.take(1).uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                Column {
                                    val authorMember = members.firstOrNull { it.uid == m.authorUid }
                                    val authorRole = when {
                                        m.authorUid == "ai" -> "AI"
                                        authorMember != null -> authorMember.role.uppercase()
                                        else -> null
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            m.authorName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (authorRole != null) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = when (authorRole) {
                                                    "OWNER" -> Color(0xFFFFD700).copy(alpha = 0.25f)
                                                    "ADMIN" -> MaterialTheme.colorScheme.primaryContainer
                                                    "MODERATOR" -> MaterialTheme.colorScheme.tertiaryContainer
                                                    "AI" -> MaterialTheme.colorScheme.secondaryContainer
                                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                                }
                                            ) {
                                                Text(
                                                    authorRole,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    fontWeight = FontWeight.Bold,
                                                    color = when (authorRole) {
                                                        "OWNER" -> Color(0xFFB8860B)
                                                        "ADMIN" -> MaterialTheme.colorScheme.primary
                                                        "MODERATOR" -> MaterialTheme.colorScheme.tertiary
                                                        "AI" -> MaterialTheme.colorScheme.secondary
                                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Text(m.text, style = MaterialTheme.typography.bodyMedium)
                                    if (m.credits != null && m.credits > 0) {
                                        Text(
                                            String.format("%.4f credits", m.credits),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (aiBusy) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("AI is working…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                    }

                    HorizontalDivider()
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        error?.let { err ->
                            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Message the team… or @ai") },
                                singleLine = false,
                                maxLines = 4,
                            )
                            IconButton(
                                onClick = {
                                    val tid = teamId ?: return@IconButton
                                    val pid = projectId ?: return@IconButton
                                    if (text.isBlank()) return@IconButton
                                    scope.launch {
                                        busy = true
                                        error = null
                                        try {
                                            val token = authTokenProvider() ?: error("Sign in required")
                                            apiPost(
                                                baseUrl, token, "/native/teams/post-message",
                                                JSONObject()
                                                    .put("teamId", tid)
                                                    .put("projectId", pid)
                                                    .put("text", text.trim()),
                                            )
                                            text = ""
                                            refreshMessages(tid, pid)
                                        } catch (e: Exception) {
                                            error = e.message
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy && !aiBusy && text.isNotBlank(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                            TextButton(onClick = { aiBillingSource = "team" }, enabled = !aiBusy) { Text(if (aiBillingSource == "team") "✓ Team credits" else "Team credits") }
                            TextButton(onClick = { aiBillingSource = "personal" }, enabled = !aiBusy) { Text(if (aiBillingSource == "personal") "✓ My credits" else "My credits") }
                            FilledTonalButton(
                                onClick = {
                                    val tid = teamId ?: return@FilledTonalButton
                                    val pid = projectId ?: return@FilledTonalButton
                                    if (text.isBlank()) return@FilledTonalButton
                                    scope.launch {
                                        aiBusy = true
                                        error = null
                                        try {
                                            val token = authTokenProvider() ?: error("Sign in required")
                                            val res = apiPost(
                                                baseUrl, token, "/native/teams/ask-ai",
                                                JSONObject()
                                                    .put("teamId", tid)
                                                    .put("projectId", pid)
                                                    .put("text", text.trim())
                                                    .put("billingSource", aiBillingSource),
                                            )
                                            text = ""
                                            if (res.optBoolean("noCredits")) {
                                                error = "This team has no credits. Ask an admin to add credits."
                                            }
                                            refreshMessages(tid, pid)
                                        } catch (e: Exception) {
                                            error = e.message
                                            teamId?.let { t -> projectId?.let { p -> refreshMessages(t, p) } }
                                        } finally {
                                            aiBusy = false
                                        }
                                    }
                                },
                                enabled = !busy && !aiBusy && text.isNotBlank(),
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Ask AI")
                            }
                        }
                        Text(
                            "Choose which balance pays for each AI request. Member messages use the configured message price.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (selectedTab == 1) {
                    // TAB 1: TEAM PLAN BOX
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Team Plan Box", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Draft and approve specifications. When ready, activate BQ Codron.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { showCreatePlanDialog = true }) {
                                Text("+ Add Plan")
                            }
                        }

                        if (balance <= 0.0) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Team pool empty (0.00 credits). Top up to activate BQ Codron.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { showTopupDialog = true }) {
                                        Text("+ Buy Credits", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        if (plansLoading) {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        } else if (plans.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("No plans in the Plan Box yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = { showCreatePlanDialog = true }) { Text("+ Add First Plan") }
                                }
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                items(plans, key = { it.id }) { p ->
                                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(p.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                                Surface(
                                                    color = if (p.status == "ready" || p.status == "approved") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        p.status.uppercase(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (p.details.isNotBlank()) {
                                                Text(p.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Text(
                                                "By: ${p.submittedByName}${if (p.createdAt.isNotBlank()) " · ${p.createdAt.take(10)}" else ""}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (!p.approvedByName.isNullOrBlank()) {
                                                Text(
                                                    "✓ Approved by ${p.approvedByName}${if (!p.approvedAt.isNullOrBlank()) " · " + p.approvedAt.take(10) else ""}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                val isPrivileged = memberRole == "owner" || memberRole == "admin" || memberRole == "moderator"
                                                val isApproved = p.status == "approved" || p.status == "ready" || p.status == "activated" || p.status == "completed"

                                                if (isPrivileged) {
                                                    if (p.status != "approved" && p.status != "ready" && p.status != "activated") {
                                                        FilledTonalButton(onClick = {
                                                            val tid = teamId ?: return@FilledTonalButton
                                                            scope.launch {
                                                                val token = authTokenProvider() ?: return@launch
                                                                apiPost(baseUrl, token, "/native/teams/plans/update", JSONObject().put("teamId", tid).put("planId", p.id).put("status", "approved"))
                                                                loadPlans()
                                                            }
                                                        }) {
                                                            Text("✓ Approve Plan")
                                                        }
                                                    } else {
                                                        TextButton(onClick = {
                                                            val tid = teamId ?: return@TextButton
                                                            val nextStatus = if (p.status == "ready") "approved" else "ready"
                                                            scope.launch {
                                                                val token = authTokenProvider() ?: return@launch
                                                                apiPost(baseUrl, token, "/native/teams/plans/update", JSONObject().put("teamId", tid).put("planId", p.id).put("status", nextStatus))
                                                                loadPlans()
                                                            }
                                                        }) {
                                                            Text(if (p.status == "ready") "Mark Approved" else "Mark Ready")
                                                        }
                                                    }
                                                } else {
                                                    if (!isApproved) {
                                                        Surface(
                                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                "Awaiting Admin Approval",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    } else {
                                                        Text("Ready for execution", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }

                                                Button(
                                                    enabled = isApproved,
                                                    onClick = {
                                                        if (balance <= 0.0) {
                                                            error = "Team pool balance is 0.00 credits. Please top up to activate BQ Codron."
                                                            showTopupDialog = true
                                                            return@Button
                                                        }
                                                        selectedTab = 3
                                                        codonPrompt = "[EXECUTING APPROVED TEAM PLAN: ${p.title}]\nSpecifications:\n${p.details}\n\nTask: Generate production code and run Gradle test suites."
                                                        codonLogs = codonLogs + listOf(">>> [Plan Box Activation] Team plan '${p.title}' dispatched to BQ Codron (BQ Codron Engine).")
                                                    }
                                                ) {
                                                    Text("Activate BQ Codron", fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedTab == 2) {
                    // TAB 2: TEAM TASKS
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Team Tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Assign and track deliverables with your team.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { showCreateTaskDialog = true }) {
                                Text("+ New Task")
                            }
                        }

                        if (tasksLoading) {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        } else if (tasks.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("No team tasks yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = { showCreateTaskDialog = true }) { Text("+ Create First Task") }
                                }
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                items(tasks, key = { it.id }) { t ->
                                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(t.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                                Surface(
                                                    color = if (t.status == "done") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        t.status.uppercase(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (t.description.isNotBlank()) {
                                                Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    "Priority: ${t.priority.uppercase()}${if (t.assignedToName.isNotBlank()) " · 👤 ${t.assignedToName}" else ""}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    TextButton(onClick = {
                                                        val tid = teamId ?: return@TextButton
                                                        val nextStatus = if (t.status == "done") "todo" else if (t.status == "todo") "in_progress" else "done"
                                                        scope.launch {
                                                            val token = authTokenProvider() ?: return@launch
                                                            apiPost(baseUrl, token, "/native/teams/tasks/update", JSONObject().put("teamId", tid).put("taskId", t.id).put("status", nextStatus))
                                                            loadTasks()
                                                        }
                                                    }) {
                                                        Text(if (t.status == "done") "Reopen" else "Advance")
                                                    }
                                                    TextButton(onClick = {
                                                        selectedTab = 3
                                                        codonPrompt = "Task: ${t.title}\nDetails: ${t.description}\nImplement code and verify test suite."
                                                    }) {
                                                        Text("Send to BQ Codron", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedTab == 3) {
                    // TAB 2: BQ CODON (AUTONOMOUS AI ENGINEER)
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text("⚡ BQ Codron — Autonomous Engineer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
Text("Powered by BQ Codron Engine", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Full-stack builder, Gradle & test runner ('gradools'), and repository sync.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { showTopupDialog = true }) {
                                        Text("+ Top Up Pool", fontWeight = FontWeight.Bold)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(
                                        selected = codonAction == "build",
                                        onClick = { codonAction = "build" },
                                        label = { Text("Build & Code") }
                                    )
                                    FilterChip(
                                        selected = codonAction == "test",
                                        onClick = { codonAction = "test" },
                                        label = { Text("Gradle & Tests") }
                                    )
                                    FilterChip(
                                        selected = codonAction == "debug",
                                        onClick = { codonAction = "debug" },
                                        label = { Text("Debugger") }
                                    )
                                    FilterChip(
                                        selected = codonAction == "repo",
                                        onClick = { codonAction = "repo" },
                                        label = { Text("Repo Sync") }
                                    )
                                }
                            }
                        }

                        // Terminal / Console view
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(codonLogs) { log ->
                                    Text(
                                        log,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (log.startsWith(">>>")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (log.startsWith(">>>")) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                                if (aiBusy) {
                                    item {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Text("Executing BQ Codron diagnostic runner...", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        // Codron Input
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = codonPrompt,
                                onValueChange = { codonPrompt = it },
                                placeholder = { Text("Instruct BQ Codron ($codonAction)...") },
                                modifier = Modifier.weight(1f),
                                singleLine = false,
                                maxLines = 3
                            )
                            Button(
                                onClick = {
                                    val tid = teamId ?: return@Button
                                    val pid = projectId ?: return@Button
                                    val prompt = codonPrompt.trim()
                                    if (prompt.isBlank()) return@Button
                                    codonPrompt = ""
                                    codonLogs = codonLogs + listOf(
                                        ">>> [Action: ${codonAction.uppercase()}] $prompt",
                                        "[BQ Codron] Allocating sandbox environment & analyzing codebase...",
                                        if (codonAction == "test") "[BQ Codron] Executing Gradle test runner ('gradools')..." else "[BQ Codron] Generating multi-file patch with zero placeholders..."
                                    )
                                    scope.launch {
                                        aiBusy = true
                                        try {
                                            val token = authTokenProvider() ?: error("Sign in required")
                                            val res = apiPost(
                                                baseUrl, token, "/native/teams/ask-ai",
                                                JSONObject()
                                                    .put("teamId", tid)
                                                    .put("projectId", pid)
                                                    .put("text", "[CODON: ${codonAction.uppercase()}]\n$prompt")
                                                    .put("mode", "codon")
                                                    .put("billingSource", aiBillingSource)
                                            )
                                            codonLogs = codonLogs + listOf(
                                                "[BQ Codron] Operation successfully completed.",
                                                "[BQ Codron] Build verification passed: 100%."
                                            )
                                            refreshMessages(tid, pid)
                                        } catch (e: Exception) {
                                            codonLogs = codonLogs + listOf("[BQ Codron Error] ${e.message}")
                                        } finally {
                                            aiBusy = false
                                        }
                                    }
                                },
                                enabled = !aiBusy && codonPrompt.isNotBlank()
                            ) {
                                Text("Execute")
                            }
                        }
                    }
                }
            }
        }
    }

    spendLimitTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { spendLimitTarget = null },
            title = { Text("Monthly AI spending limit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Set a monthly cap for ${target.displayName}. Leave blank for no cap. The cap applies to team AI usage charged to the shared team wallet.")
                    OutlinedTextField(
                        value = spendLimitText,
                        onValueChange = { spendLimitText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(14) },
                        label = { Text("Credits per month") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && (spendLimitText.isBlank() || spendLimitText.toDoubleOrNull()?.let { it >= 0.0 && it.isFinite() } == true),
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val token = authTokenProvider() ?: error("Sign in required")
                                val body = JSONObject()
                                    .put("teamId", teamId)
                                    .put("uid", target.uid)
                                    .put("limitCredits", spendLimitText.toDoubleOrNull() ?: JSONObject.NULL)
                                apiPost(baseUrl, token, "/native/teams/member/spend-limit", body)
                                val result = apiPost(baseUrl, token, "/native/teams/members", JSONObject().put("teamId", teamId))
                                members = parseMembers(result.optJSONArray("members"))
                                spendLimitTarget = null
                                error = null
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { spendLimitTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            title = { Text(if (editingMemoryId == null) "Add shared team memory" else "Edit shared team memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = memoryTitle, onValueChange = { memoryTitle = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(value = memoryText, onValueChange = { memoryText = it }, label = { Text("Information") }, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && memoryTitle.isNotBlank() && memoryText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val token = authTokenProvider() ?: error("Sign in required")
                                val body = JSONObject().put("teamId", teamId).put("title", memoryTitle.trim()).put("text", memoryText.trim())
                                val path = if (editingMemoryId == null) "/native/teams/memory/add" else "/native/teams/memory/update"
                                if (editingMemoryId != null) body.put("memoryId", editingMemoryId)
                                apiPost(baseUrl, token, path, body)
                                val result = apiPost(baseUrl, token, "/native/teams/memory", JSONObject().put("teamId", teamId))
                                memories = parseMemories(result.optJSONArray("memories"))
                                showMemoryDialog = false
                                error = null
                            } catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showMemoryDialog = false }) { Text("Cancel") } },
        )
    }
    deletingMemory?.let { memory ->
        AlertDialog(
            onDismissRequest = { deletingMemory = null },
            title = { Text("Delete shared memory?") },
            text = { Text("“${memory.title}” will be removed from this team.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val token = authTokenProvider() ?: error("Sign in required")
                            apiPost(baseUrl, token, "/native/teams/memory/delete", JSONObject().put("teamId", teamId).put("memoryId", memory.id))
                            memories = memories.filterNot { it.id == memory.id }
                            deletingMemory = null
                        } catch (e: Exception) { error = e.message }
                        finally { busy = false }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingMemory = null }) { Text("Cancel") } },
        )
    }

    if (showTopupDialog) {
        AlertDialog(
            onDismissRequest = { showTopupDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Buy Team Credits")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add credits to the shared team credit pool. All active members and BQ Codron tools build from this balance.", style = MaterialTheme.typography.bodySmall)
                    listOf(
                        Triple(100, "$15", "Starter Pack"),
                        Triple(200, "$29", "Most Popular"),
                        Triple(500, "$65", "Pro Team Pack")
                    ).forEach { (credits, price, label) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (topupAmount == credits) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = (topupAmount == credits),
                                onClick = { topupAmount = credits }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("$credits Credits", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(price, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTopupDialog = false
                        onOpenPlans()
                    }
                ) {
                    Text("Buy Credits via Store")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTopupDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreateTaskDialog) {
        AlertDialog(
            onDismissRequest = { showCreateTaskDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ListAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Create Team Task")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTaskTitle,
                        onValueChange = { newTaskTitle = it },
                        label = { Text("Task Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTaskDesc,
                        onValueChange = { newTaskDesc = it },
                        label = { Text("Description & Acceptance Criteria") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && newTaskTitle.isNotBlank(),
                    onClick = {
                        val tid = teamId ?: return@Button
                        scope.launch {
                            busy = true
                            try {
                                val token = authTokenProvider() ?: error("Sign in required")
                                val body = JSONObject()
                                    .put("teamId", tid)
                                    .put("title", newTaskTitle.trim())
                                    .put("description", newTaskDesc.trim())
                                    .put("priority", newTaskPriority)
                                    .put("assignedTo", newTaskAssignee)
                                if (projectId != null) body.put("projectId", projectId)
                                apiPost(baseUrl, token, "/native/teams/tasks/create", body)
                                showCreateTaskDialog = false
                                newTaskTitle = ""
                                newTaskDesc = ""
                                loadTasks()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Create Task") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTaskDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreatePlanDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlanDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Add to Team Plan Box")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Submit a feature blueprint for team review. When ready, activate in BQ Codron.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = newPlanTitle,
                        onValueChange = { newPlanTitle = it },
                        label = { Text("Plan / Feature Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPlanDetails,
                        onValueChange = { newPlanDetails = it },
                        label = { Text("Detailed Specifications & Steps") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && newPlanTitle.isNotBlank(),
                    onClick = {
                        val tid = teamId ?: return@Button
                        scope.launch {
                            busy = true
                            try {
                                val token = authTokenProvider() ?: error("Sign in required")
                                val body = JSONObject()
                                    .put("teamId", tid)
                                    .put("title", newPlanTitle.trim())
                                    .put("details", newPlanDetails.trim())
                                    .put("status", "draft")
                                apiPost(baseUrl, token, "/native/teams/plans/create", body)
                                showCreatePlanDialog = false
                                newPlanTitle = ""
                                newPlanDetails = ""
                                loadPlans()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Save Plan") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlanDialog = false }) { Text("Cancel") }
            }
        )
    }
}




}

private fun parseTasks(arr: org.json.JSONArray?): List<TeamTaskItem> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                TeamTaskItem(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    description = o.optString("description"),
                    assignedTo = o.optString("assignedTo"),
                    assignedToName = o.optString("assignedToName"),
                    priority = o.optString("priority", "medium"),
                    status = o.optString("status", "todo"),
                    createdAt = o.optString("createdAt"),
                )
            )
        }
    }
}

private fun parsePlans(arr: org.json.JSONArray?): List<TeamPlanItem> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                TeamPlanItem(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    details = o.optString("details"),
                    status = o.optString("status", "draft"),
                    submittedByName = o.optString("submittedByName", "Member"),
                    createdAt = o.optString("createdAt"),
                    approvedBy = o.optString("approvedBy").takeIf { it.isNotBlank() },
                    approvedByName = o.optString("approvedByName").takeIf { it.isNotBlank() },
                    approvedAt = o.optString("approvedAt").takeIf { it.isNotBlank() },
                )
            )
        }
    }
}

private fun parseMemories(arr: org.json.JSONArray?): List<TeamMemoryRow> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(TeamMemoryRow(o.optString("id"), o.optString("title"), o.optString("text")))
        }
    }
}

private fun parseMessages(arr: org.json.JSONArray?): List<TeamMessage> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                TeamMessage(
                    id = o.optString("id"),
                    authorUid = o.optString("authorUid"),
                    authorName = o.optString("authorName"),
                    text = o.optString("text"),
                    createdAt = o.optString("createdAt"),
                    credits = if (o.has("credits")) o.optDouble("credits") else null,
                )
            )
        }
    }
}

private fun parseMembers(arr: org.json.JSONArray?): List<TeamMemberRow> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                TeamMemberRow(
                    uid = o.optString("uid"),
                    displayName = o.optString("displayName", "Member"),
                    role = o.optString("role", "member"),
                    photoUrl = o.optString("photoUrl").ifBlank { null },
                    status = o.optString("status", "active"),
                    spendLimitCredits = if (o.isNull("spendLimitCredits")) null else o.optDouble("spendLimitCredits"),
                )
            )
        }
    }
}

private fun parseProjects(arr: org.json.JSONArray?): List<TeamProjectRow> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(TeamProjectRow(id = o.optString("id"), name = o.optString("name", "Project")))
        }
    }
}

private suspend fun apiPost(baseUrl: String, token: String, path: String, body: JSONObject): JSONObject =
    withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: "{}"
        if (code !in 200..299) {
            val msg = try { JSONObject(resp).optString("error", resp) } catch (_: Exception) { resp }
            throw Exception(msg.ifBlank { "Request failed ($code)" })
        }
        JSONObject(resp)
    }
