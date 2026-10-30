package com.bqsqwad.ai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.ui.teams.TeamWorkspaceScreen
import com.bqsqwad.ai.ui.teams.TeamsHubScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Blue = Color(0xFF1457FF)

/**
 * Entry from the top-left corner menu (ModalNavigationDrawer → "Teams").
 * Opens the shared Teams product (website parity):
 * hub → discover / create / join, then team workspace with free member chat + Ask AI on team credits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTeamScreen(
    baseUrl: String,
    authTokenProvider: suspend () -> String?,
    onBack: () -> Unit,
) {
    var openUsername by remember { mutableStateOf<String?>(null) }

    when (val username = openUsername) {
        null -> TeamsHubScreen(
            baseUrl = baseUrl,
            authTokenProvider = authTokenProvider,
            onOpenTeam = { openUsername = it },
            onBack = onBack,
        )
        else -> TeamWorkspaceScreen(
            username = username,
            baseUrl = baseUrl,
            authTokenProvider = authTokenProvider,
            onBack = { openUsername = null },
        )
    }
}

@Composable
fun SettingsCreditsCard(
    balanceLabel: String,
    unlimited: Boolean,
    onOpenPlans: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Blue.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth(),
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
                color = Blue,
            )
            Text(
                "Credits are shown here in Settings. Main chat stays focused on conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    body: JSONObject? = null,
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
