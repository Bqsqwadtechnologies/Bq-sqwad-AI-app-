package com.bqsqwad.ai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class GodAgentRun(val title: String, val result: String, val approved: Boolean)

@Composable
fun GodAiAgentWorkspace(onOpenSettings: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var objective by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Plan") }
    var plan by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var awaitingApproval by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableStateOf<List<GodAgentRun>>(emptyList()) }

    val modes = listOf("Plan", "Research", "Business", "Build", "Organize")
    fun runAgent(approvedPlan: Boolean) {
        val task = objective.trim()
        if (task.isBlank() || busy) return
        busy = true
        notice = null
        scope.launch {
            runCatching {
                val system = if (approvedPlan) {
                    "You are God AI Agent for BQ Sqwad AI. The user has reviewed and approved the proposed plan. Continue with the next safe reasoning step using only capabilities actually available to this authenticated application. Do not claim that external changes, publishing, payments, messages, deployments, or other actions were performed unless a connected tool explicitly confirms success. Before any irreversible or external action, present a concise preview and ask for explicit confirmation."
                } else {
                    "You are God AI Agent for BQ Sqwad AI, a supervised orchestration assistant. Produce a structured plan with objective, assumptions, ordered steps, tools/capabilities that appear relevant, risks, required user permissions, and a verification checklist. Do not execute or claim to execute external actions. Ask for approval before proceeding."
                }
                val message = buildString {
                    append("Workflow mode: ").append(mode).append("\nObjective: ").append(task)
                    if (approvedPlan) append("\nPreviously proposed plan:\n").append(plan.orEmpty())
                    append("\nUse concise numbered steps. Clearly separate what can be done inside BQ Sqwad AI from actions that need a connected website, account, or explicit user approval.")
                }
                val body = WorkspaceApiClient().workspaceAi("agent_tool_use", message, system)
                body.optString("text").ifBlank { body.optString("answer").ifBlank { body.optString("response") } }
                    .takeIf { it.isNotBlank() } ?: throw IllegalStateException("The agent returned an empty plan.")
            }.onSuccess { text ->
                // Contributions are accepted only when the server confirms this account opted in.
                scope.launch {
                    runCatching { WorkspaceApiClient().submitTrainingContribution(task, text, "god_ai_agent", false) }
                }
                if (approvedPlan) {
                    result = text
                    awaitingApproval = false
                    runs = (runs + GodAgentRun(task, text, true)).takeLast(10)
                    notice = "Approved plan continued. Review the result and verify any external action separately."
                } else {
                    plan = text
                    result = null
                    awaitingApproval = true
                    notice = "Plan ready for your review. No external action has been executed."
                }
            }.onFailure {
                notice = safeUserFacingIssue(it, "God AI Agent could not prepare the workflow. Check access and retry.")
            }
            busy = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 3.dp
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Column(Modifier.weight(1f)) {
                            Text("God AI Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Plan, coordinate and review work across enabled BQ Sqwad AI capabilities.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modes.forEach { item -> FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item) }) }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Mission brief", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = objective,
                        onValueChange = { objective = it; notice = null },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        label = { Text("What should the agent help you accomplish?") },
                        placeholder = { Text("Example: research my market, draft a launch plan, and list the steps I should approve.") }
                    )
                    Button(onClick = { runAgent(false) }, enabled = objective.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Text(if (busy) "Working…" else "Prepare supervised plan")
                    }
                    Text("The app uses the shared authenticated BQ Sqwad AI backend. External website actions, publishing, payments and deployments are not performed without a connected tool and explicit confirmation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (busy) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        plan?.let { value ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Proposed plan · review before continuing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(value)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { runAgent(true) }, enabled = !busy && awaitingApproval, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Text("Approve & continue")
                            }
                            OutlinedButton(onClick = { plan = null; result = null; awaitingApproval = false }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Revise plan") }
                        }
                    }
                }
            }
        }
        result?.let { value ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Agent result", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(value)
                        TextButton(onClick = { result = null }) { Text("Dismiss result") }
                    }
                }
            }
        }
        notice?.let { value -> item { Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) } }
        if (runs.isNotEmpty()) {
            item { Text("Recent agent runs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(runs.size) { index ->
                val run = runs[runs.lastIndex - index]
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(run.title, fontWeight = FontWeight.SemiBold)
                        Text(if (run.approved) "Approved continuation" else "Draft plan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(run.result.take(500), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open Settings & Privacy") } }
    }
}
