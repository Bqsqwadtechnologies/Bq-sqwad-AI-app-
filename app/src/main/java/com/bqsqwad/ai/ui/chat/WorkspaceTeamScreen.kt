package com.bqsqwad.ai.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Blue = Color(0xFF1457FF)

enum class TeamRole { ADMIN, MODERATOR, MEMBER }

data class TeamMember(
    val uid: String,
    val email: String,
    val displayName: String,
    val role: TeamRole,
    val status: String,
    val canBeCharged: Boolean
)

data class ChargeApprovalRequest(
    val id: String,
    val fromEmail: String,
    val toEmail: String,
    val reason: String,
    val status: String
)

@Composable
fun WorkspaceTeamScreen(
    baseUrl: String,
    authTokenProvider: suspend () -> String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<TeamMember>>(emptyList()) }
    var chargeAccountUid by remember { mutableStateOf<String?>(null) }
    var pendingApprovals by remember { mutableStateOf<List<ChargeApprovalRequest>>(emptyList()) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf(TeamRole.MEMBER) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var myRole by remember { mutableStateOf(TeamRole.MEMBER) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                val token = authTokenProvider() ?: error("Sign in required")
                val body = teamApi(baseUrl, token, "GET", "/native/team/members")
                val list = body.optJSONArray("members") ?: JSONArray()
                members = List(list.length()) { i ->
                    val o = list.getJSONObject(i)
                    TeamMember(
                        uid = o.optString("uid"),
                        email = o.optString("email"),
                        displayName = o.optString("displayName").ifBlank { o.optString("email") },
                        role = when (o.optString("role").lowercase()) {
                            "admin" -> TeamRole.ADMIN
                            "moderator" -> TeamRole.MODERATOR
                            else -> TeamRole.MEMBER
                        },
                        status = o.optString("status", "active"),
                        canBeCharged = o.optBoolean("canBeCharged", false)
                    )
                }
                chargeAccountUid = body.optString("chargeAccountUid").ifBlank { null }
                myRole = when (body.optString("myRole").lowercase()) {
                    "admin" -> TeamRole.ADMIN
                    "moderator" -> TeamRole.MODERATOR
                    else -> TeamRole.MEMBER
                }
                val approvals = body.optJSONArray("pendingChargeApprovals") ?: JSONArray()
                pendingApprovals = List(approvals.length()) { i ->
                    val o = approvals.getJSONObject(i)
                    ChargeApprovalRequest(
                        id = o.optString("id"),
                        fromEmail = o.optString("fromEmail"),
                        toEmail = o.optString("toEmail"),
                        reason = o.optString("reason"),
                        status = o.optString("status")
                    )
                }
            }.onFailure { error = it.message ?: "Could not load team" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Team & workspace", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Invite people who already have a BQ Sqwad AI account. They get an in-app notice and an email. Only admins can change roles, remove members, or set which account pays for usage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            error?.let { item { Text(it, color = Color(0xFFB45309)) } }
            notice?.let { item { Text(it, color = Blue) } }

            if (myRole == TeamRole.ADMIN) {
                item {
                    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Invite by email", fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = inviteEmail,
                                onValueChange = { inviteEmail = it },
                                label = { Text("Email") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = inviteRole == TeamRole.MEMBER, onClick = { inviteRole = TeamRole.MEMBER }, label = { Text("Member") })
                                FilterChip(selected = inviteRole == TeamRole.MODERATOR, onClick = { inviteRole = TeamRole.MODERATOR }, label = { Text("Moderator") })
                            }
                            Button(
                                enabled = !busy && inviteEmail.contains("@"),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        runCatching {
                                            val token = authTokenProvider() ?: error("Sign in required")
                                            teamApi(baseUrl, token, "POST", "/native/team/invite",
                                                JSONObject().put("email", inviteEmail.trim().lowercase()).put("role", inviteRole.name.lowercase()))
                                        }.onSuccess {
                                            notice = "Invite sent. They will be notified in the app and by email."
                                            inviteEmail = ""
                                            refresh()
                                        }.onFailure { error = it.message ?: "Invite failed" }
                                        busy = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Send invite") }
                        }
                    }
                }
            }

            item { Text("Members", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (loading) item { CircularProgressIndicator(modifier = Modifier.padding(24.dp)) }
            else items(members, key = { it.uid }) { member ->
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, null, tint = Blue)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(member.displayName, fontWeight = FontWeight.SemiBold)
                                Text(member.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AssistChip(onClick = {}, label = { Text(member.role.name.lowercase().replaceFirstChar { it.uppercase() }) })
                        }
                        Text("Status: ${member.status}", style = MaterialTheme.typography.labelSmall)
                        if (chargeAccountUid == member.uid) {
                            Text("Credits charged from this account", color = Blue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (myRole == TeamRole.ADMIN) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (chargeAccountUid != member.uid && member.status == "active") {
                                    TextButton(onClick = {
                                        busy = true
                                        scope.launch {
                                            runCatching {
                                                val token = authTokenProvider() ?: error("Sign in required")
                                                teamApi(baseUrl, token, "POST", "/native/team/request-charge-account", JSONObject().put("uid", member.uid))
                                            }.onSuccess {
                                                notice = "Charge-account request sent. ${member.email} must approve before credits are used from their balance."
                                                refresh()
                                            }.onFailure { error = it.message }
                                            busy = false
                                        }
                                    }) { Text("Use their credits") }
                                }
                                if (member.role != TeamRole.ADMIN) {
                                    TextButton(onClick = {
                                        busy = true
                                        scope.launch {
                                            runCatching {
                                                val token = authTokenProvider() ?: error("Sign in required")
                                                teamApi(baseUrl, token, "POST", "/native/team/remove", JSONObject().put("uid", member.uid))
                                            }.onSuccess { refresh() }.onFailure { error = it.message }
                                            busy = false
                                        }
                                    }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }

            if (pendingApprovals.isNotEmpty()) {
                item { Text("Your approvals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(pendingApprovals, key = { it.id }) { req ->
                    Surface(shape = RoundedCornerShape(14.dp), color = Blue.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${req.fromEmail} wants to charge team usage to your credits.", fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        runCatching {
                                            val token = authTokenProvider() ?: error("Sign in required")
                                            teamApi(baseUrl, token, "POST", "/native/team/approve-charge", JSONObject().put("id", req.id).put("approve", true))
                                        }.onSuccess { notice = "Approved."; refresh() }.onFailure { error = it.message }
                                    }
                                }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Approve") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            val token = authTokenProvider() ?: error("Sign in required")
                                            teamApi(baseUrl, token, "POST", "/native/team/approve-charge", JSONObject().put("id", req.id).put("approve", false))
                                        }.onSuccess { refresh() }.onFailure { error = it.message }
                                    }
                                }) { Text("Decline") }
                            }
                        }
                    }
                }
            }

            item {
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://bqsqwadai.com/terms")))
                }) { Text("Terms of use (website)") }
            }
            item {
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://bqsqwadai.com/privacy-policy")))
                }) { Text("Privacy policy (website)") }
            }
        }
    }
}

@Composable
fun SettingsCreditsCard(
    balanceLabel: String,
    unlimited: Boolean,
    onOpenPlans: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Blue.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBalanceWallet, null, tint = Blue)
                Spacer(Modifier.width(10.dp))
                Text("Credit balance", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (unlimited) "Unlimited (admin)" else balanceLabel,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Blue
            )
            Text(
                "Credits are shown here in Settings. Main chat stays focused on conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenPlans) { Text("Plans & top-up") }
        }
    }
}

private suspend fun teamApi(
    baseUrl: String,
    token: String,
    method: String,
    path: String,
    body: JSONObject? = null
): JSONObject = withContext(Dispatchers.IO) {
    val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            doOutput = true
            outputStream.use { it.write(body.toString().toByteArray()) }
        }
    }
    try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (code !in 200..299) {
            val msg = json.optString("error").ifBlank { json.optString("message") }.ifBlank { "Request failed ($code)" }
            error(msg)
        }
        json
    } finally {
        connection.disconnect()
    }
}
