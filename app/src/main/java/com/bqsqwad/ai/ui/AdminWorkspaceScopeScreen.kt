@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

private val scopeGuardRows = listOf(
    Triple("learning", "Learning Studio", "Lessons, practice, quizzes, homework help and exam preparation only."),
    Triple("truthguard", "TruthGuard AI", "Claim checking and evidence/source review only."),
    Triple("life_mathis", "Life Mathis", "Life guidance, reflection and personal decisions only."),
    Triple("companion", "AI Companion", "Companionship and check-ins only."),
    Triple("personal_intelligence", "Personal Intelligence", "Planning, organization, goals and priorities only."),
    Triple("data_analyst", "Data Analyst", "Datasets, spreadsheets, statistics and reports only."),
    Triple("frontier_lab", "Frontier Lab", "Experiments, hypotheses, prototypes and simulations only."),
    Triple("video", "Video Generation", "Video clips and animations only.")
)

@Composable
fun AdminWorkspaceScopeScreen(onBack: () -> Unit) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(scopeGuardRows.associate { it.first to true }) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val saved = api.adminWorkspaceScopeGuards().optJSONObject("enabledByWorkspace") ?: JSONObject()
            enabled = scopeGuardRows.associate { row -> row.first to saved.optBoolean(row.first, true) }
        } catch (t: Throwable) {
            error = t.message ?: "Could not load workspace scope settings."
        } finally {
            loading = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Workspace scope guards", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Enabled guards redirect out-of-scope requests before any credits are reserved. Keep these on by default.", color = MaterialTheme.colorScheme.onSurface)
            if (loading) Text("Loading settings…", color = MaterialTheme.colorScheme.primary)
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            scopeGuardRows.forEach { row ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.second, fontWeight = FontWeight.SemiBold)
                            Text(row.third, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = enabled[row.first] != false, onCheckedChange = { enabled = enabled.toMutableMap().apply { put(row.first, it) } })
                    }
                }
            }
            Button(
                onClick = {
                    saving = true
                    status = null
                    error = null
                    val payload = JSONObject().apply { scopeGuardRows.forEach { put(it.first, enabled[it.first] != false) } }
                    scope.launch {
                        try {
                            val saved = api.saveAdminWorkspaceScopeGuards(payload)
                            val flags = saved.optJSONObject("enabledByWorkspace") ?: payload
                            enabled = scopeGuardRows.associate { it.first to flags.optBoolean(it.first, true) }
                            status = "Workspace scope settings saved."
                        } catch (t: Throwable) {
                            error = t.message ?: "Could not save workspace scope settings."
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !loading && !saving,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saving) "Saving…" else "Save scope settings") }
        }
    }
}
