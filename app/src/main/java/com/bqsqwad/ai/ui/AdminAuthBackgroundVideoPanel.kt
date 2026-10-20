package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch

@Composable
internal fun AdminAuthBackgroundVideoPanel(onBack: () -> Unit) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        busy = true
        runCatching { api.adminAppLayout() }.onSuccess { config ->
            url = config.optString("authBackgroundVideoUrl").takeUnless { it == "null" }.orEmpty()
            enabled = config.optBoolean("authBackgroundVideoEnabled", false)
        }.onFailure { notice = "Could not load admin video settings. Confirm super-admin access." }
        busy = false
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Authentication Background Video", style = MaterialTheme.typography.headlineSmall)
        Text("Configure the optional looping video shown behind sign-in and sign-up. Use an HTTPS URL to a video file reachable by the device. The authentication screen loads only the public enabled URL; changes require an authorized super-admin session.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = url, onValueChange = { url = it.take(2000) }, modifier = Modifier.fillMaxWidth(), label = { Text("HTTPS video URL") }, singleLine = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Enable background video", style = MaterialTheme.typography.titleMedium)
                Text("When off, sign-in uses the normal static background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Button(enabled = !busy && (!enabled || url.startsWith("https://", ignoreCase = true)), onClick = {
            busy = true
            scope.launch {
                runCatching { api.setAdminAuthBackgroundVideo(url.trim(), enabled) }
                    .onSuccess { notice = if (enabled) "Background video configuration saved." else "Background video disabled." }
                    .onFailure { notice = "Could not save the video configuration. Check the HTTPS URL and admin access." }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save video settings") }
        if (busy) BqSqwadLoadingIndicator(Modifier.size(42.dp))
        notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to Admin") }
    }
}
