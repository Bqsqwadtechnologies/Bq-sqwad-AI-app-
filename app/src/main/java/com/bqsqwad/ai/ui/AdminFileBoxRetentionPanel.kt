package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun AdminFileBoxRetentionPanel(onBack: () -> Unit) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf(30) }
    var stats by remember { mutableStateOf(JSONObject()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        busy = true
        scope.launch {
            runCatching { api.adminFileBoxRetention() }.onSuccess { body ->
                stats = body
                days = body.optInt("defaultRetentionDays", 30).let { if (it in listOf(7,14,30,90)) it else 30 }
                notice = null
            }.onFailure { notice = "Admin retention configuration could not be loaded. Verify super-admin access." }
            busy = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("File Box Retention", style = MaterialTheme.typography.headlineSmall)
        Text("Set the default retention for new File Box accounts and inspect a bounded usage snapshot. Existing users with an explicit retention choice keep their own setting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Default retention", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7,14,30,90).forEach { value ->
                        FilterChip(selected = days == value, onClick = { days = value }, label = { Text("$value days") })
                    }
                }
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { api.saveAdminFileBoxRetention(days) }
                            .onSuccess { notice = "Default retention saved."; refresh() }
                            .onFailure { notice = "Could not save retention settings. Check admin permissions." }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save default") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Usage snapshot", style = MaterialTheme.typography.titleMedium)
                Text("Accounts scanned: " + stats.optInt("scannedAccounts"))
                Text("Active saved items: " + stats.optInt("activeItems"))
                Text("Expired items awaiting cleanup: " + stats.optInt("expiredItems"))
                Text("Last configuration update: " + stats.optString("updatedAt", "not recorded"), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (busy) CircularProgressIndicator()
        notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to Admin") }
    }
}
