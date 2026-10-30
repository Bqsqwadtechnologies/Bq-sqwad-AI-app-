package com.bqsqwad.ai.ui.teams

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
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
)

data class TeamProjectRow(
    val id: String,
    val name: String,
)

/**
 * Team workspace — same rules as website:
 * - Member messages: free, no model
 * - Ask AI: team credit pool only; AI sees member roster
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamWorkspaceScreen(
    username: String,
    baseUrl: String,
    authTokenProvider: suspend () -> String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var teamName by remember { mutableStateOf(username) }
    var teamId by remember { mutableStateOf<String?>(null) }
    var balance by remember { mutableStateOf(0.0) }
    var isActive by remember { mutableStateOf(false) }
    var memberStatus by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<TeamMemberRow>>(emptyList()) }
    var projects by remember { mutableStateOf<List<TeamProjectRow>>(emptyList()) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<TeamMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

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
                isActive = memberStatus == "active"

                if (isActive) {
                    val tid = teamId!!
                    val mems = apiPost(baseUrl, token, "/native/teams/members", JSONObject().put("teamId", tid))
                    members = parseMembers(mems.optJSONArray("members"))
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
        if (!isActive || teamId == null || projectId == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(8_000)
            refreshMessages(teamId!!, projectId!!)
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

        Row(Modifier.fillMaxSize().padding(padding)) {
            // Side panel (members + projects) — compact on phone
            Column(
                Modifier
                    .width(140.dp)
                    .fillMaxHeight()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isActive) {
                    Text(
                        if (memberStatus == "pending") "Join request pending."
                        else "Join to chat and ask the AI.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
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
                    Text("Members", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(members.filter { it.status == "active" }) { m ->
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
                                    Text(m.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
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

            // Chat
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!isActive) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (memberStatus == "pending") "Waiting for approval."
                            else "Join this team to message members and ask the AI.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                Text(
                                    "Chat with your team. Use Ask AI or type @ai when you want a reply. Member messages never use credits.",
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
                                    Text(
                                        m.authorName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
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
                        if (error != null) {
                            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                                                    .put("text", text.trim()),
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
                            "Send is free. Ask AI uses team credits only.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
