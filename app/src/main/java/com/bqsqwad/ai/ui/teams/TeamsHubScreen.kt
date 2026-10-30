package com.bqsqwad.ai.ui.teams

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared Teams hub (website parity).
 * Public teams appear in search; private teams by exact username.
 * Recommended team username: "white".
 */
data class TeamSummary(
    val id: String,
    val name: String,
    val username: String,
    val visibility: String,
    val memberCount: Int,
    val description: String,
    val myRole: String? = null,
    val myStatus: String? = null,
)

private const val RECOMMENDED = "white"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamsHubScreen(
    baseUrl: String,
    authTokenProvider: suspend () -> String?,
    onOpenTeam: (username: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var mine by remember { mutableStateOf<List<TeamSummary>>(emptyList()) }
    var results by remember { mutableStateOf<List<TeamSummary>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var createUsername by remember { mutableStateOf("") }
    var createPublic by remember { mutableStateOf(true) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                val token = authTokenProvider() ?: error("Sign in required")
                val myList = apiGet(baseUrl, token, "/native/teams/mine")
                val discover = apiPost(baseUrl, token, "/native/teams/search", JSONObject().put("query", ""))
                mine = parseTeamList(myList.optJSONArray("teams"))
                results = parseTeamList(discover.optJSONArray("teams"))
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val recommended = results.firstOrNull { it.username == RECOMMENDED }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Teams") },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text("Back") }
                    }
                },
                actions = {
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create team")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Shared AI workspaces. Plan together — AI recognizes every member. Usage is billed to the team credit pool.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // Recommended White
                if (recommended != null && mine.none { it.username == RECOMMENDED }) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("RECOMMENDED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(recommended.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("@${recommended.username} · ${recommended.memberCount} members", style = MaterialTheme.typography.bodySmall)
                            Text(recommended.description.ifBlank { "The default community team." }, style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val token = authTokenProvider() ?: error("Sign in required")
                                            val res = apiPost(
                                                baseUrl, token, "/native/teams/join",
                                                JSONObject().put("teamId", recommended.id),
                                            )
                                            if (res.optBoolean("pending")) {
                                                error = "Join request sent. An admin will review it."
                                            } else {
                                                onOpenTeam(recommended.username)
                                            }
                                            load()
                                        } catch (e: Exception) {
                                            error = e.message
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy,
                            ) { Text("Join White team") }
                        }
                    }
                }

                Text("Your teams", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (mine.isEmpty()) {
                    Text("You have not joined a team yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 200.dp)) {
                        items(mine) { t ->
                            ListItem(
                                headlineContent = { Text(t.name) },
                                supportingContent = {
                                    Text("@${t.username} · ${t.myRole ?: ""}${if (t.myStatus == "pending") " · pending" else ""}")
                                },
                                leadingContent = { Icon(Icons.Default.Groups, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                trailingContent = {
                                    TextButton(onClick = { onOpenTeam(t.username) }) { Text("Open") }
                                },
                            )
                        }
                    }
                }

                Text("Discover", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Search public teams or exact username") },
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val token = authTokenProvider() ?: error("Sign in required")
                                    val res = apiPost(
                                        baseUrl, token, "/native/teams/search",
                                        JSONObject().put("query", query),
                                    )
                                    results = parseTeamList(res.optJSONArray("teams"))
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                    ) { Icon(Icons.Default.Search, contentDescription = "Search") }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    items(results) { t ->
                        ListItem(
                            headlineContent = { Text(t.name) },
                            supportingContent = {
                                Text("@${t.username} · ${t.visibility} · ${t.memberCount} members")
                            },
                            trailingContent = {
                                Row {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                busy = true
                                                try {
                                                    val token = authTokenProvider() ?: error("Sign in required")
                                                    val res = apiPost(
                                                        baseUrl, token, "/native/teams/join",
                                                        JSONObject().put("teamId", t.id),
                                                    )
                                                    if (res.optBoolean("pending")) {
                                                        error = "Join request sent."
                                                    } else {
                                                        onOpenTeam(t.username)
                                                    }
                                                    load()
                                                } catch (e: Exception) {
                                                    error = e.message
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text(if (t.visibility == "private") "Request" else "Join") }
                                    TextButton(onClick = { onOpenTeam(t.username) }) { Text("Open") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create a team") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = createName, onValueChange = { createName = it }, label = { Text("Team name") }, singleLine = true)
                    OutlinedTextField(
                        value = createUsername,
                        onValueChange = { createUsername = it.lowercase().replace(Regex("[^a-z0-9_]"), "") },
                        label = { Text("Unique username") },
                        supportingText = { Text("Link: /t/username") },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = createPublic, onCheckedChange = { createPublic = it })
                        Text(if (createPublic) "Public — anyone can join" else "Private — approval required")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val token = authTokenProvider() ?: error("Sign in required")
                                val res = apiPost(
                                    baseUrl, token, "/native/teams/create",
                                    JSONObject()
                                        .put("name", createName)
                                        .put("username", createUsername)
                                        .put("visibility", if (createPublic) "public" else "private"),
                                )
                                val team = res.optJSONObject("team")
                                val username = team?.optString("username") ?: createUsername
                                showCreate = false
                                onOpenTeam(username)
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && createName.length >= 2 && createUsername.length >= 3,
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel") }
            },
        )
    }
}

private fun parseTeamList(arr: org.json.JSONArray?): List<TeamSummary> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                TeamSummary(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    username = o.optString("username"),
                    visibility = o.optString("visibility", "public"),
                    memberCount = o.optInt("memberCount"),
                    description = o.optString("description"),
                    myRole = o.optString("myRole").ifBlank { null },
                    myStatus = o.optString("myStatus").ifBlank { null },
                )
            )
        }
    }
}

private suspend fun apiGet(baseUrl: String, token: String, path: String): JSONObject =
    withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: "{}"
        if (code !in 200..299) throw Exception(JSONObject(body).optString("error", body).ifBlank { "Request failed ($code)" })
        JSONObject(body)
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
        if (code !in 200..299) throw Exception(JSONObject(resp).optString("error", resp).ifBlank { "Request failed ($code)" })
        JSONObject(resp)
    }
