package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class HelpGuide(val title: String, val category: String, val steps: List<String>)
private val helpGuides = listOf(
    HelpGuide("Sign-in or account access", "account", listOf("Confirm you are using the correct BQ account.", "Update BQ Sqwad AI from Google Play and reopen it.", "If your session expired, sign out and sign in again.", "If it continues, report the safe error/reference code. Never share your password or one-time code.")),
    HelpGuide("Connect a provider", "connector", listOf("Open the Connectors section in BQ Sqwad AI.", "Choose the provider and tap Connect.", "Complete the provider's authorization screen and review its permissions.", "Return to BQ Sqwad AI and refresh connector status.", "If it remains disconnected, report the provider name and error reference. Never share provider passwords or access tokens.")),
    HelpGuide("Upload a file or PDF", "app", listOf("Choose a non-empty file no larger than 25 MB.", "Use a stable connection and wait until upload finishes.", "Retry once if processing fails.", "Report the reference code and file type without including private file contents.")),
    HelpGuide("Credits or purchases", "billing", listOf("Confirm app and website use the same BQ account.", "Refresh your balance and check transaction history.", "For Android purchases, check the Google Play account used to pay.", "Report the product name and reference code. Never send full card details.")),
    HelpGuide("Live voice or camera", "app", listOf("Check camera/microphone permissions and Android privacy toggles.", "Close other apps using the microphone or camera.", "Restart the live session and check your connection.", "Report the safe error code and app/device version.")),
    HelpGuide("Plans or prices not loading", "catalog", listOf("Check the internet connection and reopen Plans.", "Update Google Play Store and use the Play-installed BQ app.", "Refresh the live catalog and wait for store details.", "If prices remain unavailable, submit the error reference. BQ Sqwad AI should not guess store prices."))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSupportScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf(1) }
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var ticketCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val guide = helpGuides[selected]

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Help & Support") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.padding(end = 16.dp)) }
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Step-by-step help", style = MaterialTheme.typography.titleLarge)
            Text("Choose the problem and try each step in order. If it does not work, send a support request with the error reference.")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                helpGuides.forEachIndexed { index, item ->
                    FilterChip(selected = selected == index, onClick = { selected = index }, label = { Text(item.title) })
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(guide.title, style = MaterialTheme.typography.titleMedium)
                    guide.steps.forEachIndexed { index, step -> Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Contact support", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = subject, onValueChange = { subject = it.take(120) }, modifier = Modifier.fillMaxWidth(), label = { Text("Subject") }, singleLine = true)
            OutlinedTextField(value = description, onValueChange = { description = it.take(2000) }, modifier = Modifier.fillMaxWidth(), label = { Text("What happened?") }, minLines = 3)
            OutlinedTextField(value = reference, onValueChange = { reference = it.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }.take(100) }, modifier = Modifier.fillMaxWidth(), label = { Text("Error reference code (optional)") }, singleLine = true)
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && subject.trim().length >= 4 && description.trim().length >= 8,
                onClick = {
                    busy = true; message = ""; ticketCode = ""
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                WorkspaceApiClient().submitSupportIssue(subject, description, guide.category, reference)
                            }
                            ticketCode = result.optString("refCode")
                            message = "Support request saved. Keep the reference code for follow-up."
                            subject = ""; description = ""; reference = ""
                        } catch (_: Exception) {
                            message = "Could not submit the request. Check your connection and sign-in, then retry."
                        } finally { busy = false }
                    }
                }
            ) { Text(if (busy) "Submitting…" else "Send support request") }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ticketCode.isNotBlank()) Text("Support reference: $ticketCode", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("Never share your password, one-time login code, payment card details or provider access tokens with support.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
