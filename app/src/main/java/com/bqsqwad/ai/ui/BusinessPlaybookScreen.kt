package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.BusinessPlaybookClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Android Business AI playbook console — mirrors website /business/playbooks. */
@Composable
fun BusinessPlaybookScreen(
    workspaceId: String = "default",
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            busy = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) { BusinessPlaybookClient().catalog(workspaceId) }
            }.onSuccess { catalog = it }.onFailure { error = it.message }
            busy = false
        }
    }

    LaunchedEffect(workspaceId) { reload() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Business AI", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Playbooks use a secure verification link. OTP, BVN and NIN never appear in chat.",
            style = MaterialTheme.typography.bodyMedium
        )
        BusinessPlanCards(onSelect = { })

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedButton(onClick = {
            scope.launch {
                busy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        BusinessPlaybookClient().post(JSONObject().put("action", "connect_mock_bank").put("workspaceId", workspaceId).put("apiKey", "mock-live-key"))
                    }
                }.onSuccess { status = "Mock bank connected"; reload() }.onFailure { error = it.message }
                busy = false
            }
        }, enabled = !busy) { Text("Connect mock bank") }

        OutlinedButton(onClick = {
            scope.launch {
                busy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        BusinessPlaybookClient().post(
                            JSONObject()
                                .put("action", "set_integration_active")
                                .put("workspaceId", workspaceId)
                                .put("integrationId", "mock_bank")
                                .put("active", false)
                        )
                    }
                }.onSuccess { status = "Integration deactivated"; reload() }.onFailure { error = it.message }
                busy = false
            }
        }, enabled = !busy) { Text("Deactivate integration") }

        val playbooks = catalog?.optJSONArray("playbooks")
        if (playbooks != null) {
            for (i in 0 until playbooks.length()) {
                val p = playbooks.optJSONObject(i) ?: continue
                val id = p.optString("id")
                Card(Modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.optString("title"), style = MaterialTheme.typography.titleMedium)
                        Text(p.optString("summary"), style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            BusinessPlaybookClient().post(
                                                JSONObject()
                                                    .put("action", "start_playbook")
                                                    .put("workspaceId", workspaceId)
                                                    .put("playbookId", id)
                                                    .put("agentId", "default")
                                            )
                                        }
                                    }.onSuccess { body ->
                                        val run = body.optJSONObject("run")
                                        status = run?.optString("chatSafeMessage")
                                            ?: run?.optString("status")
                                            ?: "Started"
                                        val link = run?.optString("verificationUrl").orEmpty()
                                        if (link.isNotBlank()) status = (status ?: "") + "\n" + link
                                    }.onFailure { error = it.message }
                                    busy = false
                                }
                            },
                            enabled = !busy
                        ) { Text("Run playbook") }
                    }
                }
            }
        }
    }
}
