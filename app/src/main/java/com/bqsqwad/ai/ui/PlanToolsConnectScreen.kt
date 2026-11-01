package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class PlanToolUi(val id: String, val name: String, val connected: Boolean)

/**
 * Businesses connect the tools listed on their plan catalog (Composio).
 * No forced BVN/NIN — optional KYC tools appear only if on the plan.
 */
@Composable
fun PlanToolsConnectScreen(
    tools: List<PlanToolUi>,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect tools", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Choose how BQ Sqwad AI works for your business. Connect only the tools on your plan.",
            style = MaterialTheme.typography.bodyMedium
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tools, key = { it.id }) { tool ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(tool.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (tool.connected) "Connected" else "Not connected", style = MaterialTheme.typography.bodySmall)
                    if (!tool.connected) {
                        Button(onClick = { onConnect(tool.id) }) { Text("Connect") }
                    }
                }
            }
        }
    }
}
