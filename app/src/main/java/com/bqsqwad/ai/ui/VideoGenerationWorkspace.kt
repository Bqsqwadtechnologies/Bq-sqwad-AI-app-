package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import com.bqsqwad.ai.core.GenerationApiClient
import com.bqsqwad.ai.core.WorkspaceApiClient
import com.bqsqwad.ai.core.WorkspaceRedirectException
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VideoGenerationWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("8") }
    var size by remember { mutableStateOf("1280x720") }
    var jobId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("idle") }
    var progress by remember { mutableIntStateOf(0) }
    var contentUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pollingFailed by remember { mutableStateOf(false) }
    var pollRefresh by remember { mutableIntStateOf(0) }
    var publishedVideoModels by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedVideoModelId by remember { mutableStateOf("") }
    var videoModelExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }
            .onSuccess { array ->
                publishedVideoModels = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .filter { it.optString("feature") == "video-generation" && it.optBoolean("active") && it.optBoolean("published") }
                if (selectedVideoModelId.isNotBlank() && publishedVideoModels.none { it.optString("id") == selectedVideoModelId }) selectedVideoModelId = ""
            }
    }

    LaunchedEffect(jobId, pollRefresh) {
        val id = jobId ?: return@LaunchedEffect
        var consecutiveFailures = 0
        pollingFailed = false
        while (true) {
            runCatching { GenerationApiClient().videoJob(id) }
                .onSuccess {
                    consecutiveFailures = 0
                    status = it.status
                    progress = it.progress.coerceIn(0, 100)
                    contentUrl = it.contentUrl
                    error = it.error
                }
                .onFailure {
                    consecutiveFailures += 1
                    error = safeUserFacingIssue(it, "Could not refresh render status.")
                }
            if (status in setOf("completed", "failed", "cancelled")) break
            if (consecutiveFailures >= 3) {
                pollingFailed = true
                break
            }
            delay(15000)
        }
        busy = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text("AI Video Studio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Prompt → render job → progress → output. Designed as a production workspace rather than a generic chat screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Dedicated render workspace. Video jobs consume the centrally configured video-generation credit cost.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            val selectedName = publishedVideoModels.firstOrNull { it.optString("id") == selectedVideoModelId }?.optString("name")
                ?: publishedVideoModels.firstOrNull { it.optBoolean("isDefault") }?.optString("name")
                ?: "Server default"
            Box {
                OutlinedButton(onClick = {
                    videoModelExpanded = true
                    scope.launch {
                        runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }
                            .onSuccess { array ->
                                publishedVideoModels = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                                    .filter { it.optString("feature") == "video-generation" && it.optBoolean("active") && it.optBoolean("published") }
                            }
                    }
                }) { Text("AI model: " + selectedName.take(20)) }
                DropdownMenu(expanded = videoModelExpanded, onDismissRequest = { videoModelExpanded = false }) {
                    DropdownMenuItem(text = { Text("Use server default") }, onClick = { selectedVideoModelId = ""; videoModelExpanded = false })
                    publishedVideoModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.optString("name") + " · " + model.optInt("creditCost") + " credits/job" + if (model.optBoolean("isDefault")) " · Default" else "") },
                            onClick = { selectedVideoModelId = model.optString("id"); videoModelExpanded = false }
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text("Describe the video") }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(seconds, { seconds = it }, label = { Text("Seconds") }, modifier = Modifier.weight(1f))
                OutlinedTextField(size, { size = it }, label = { Text("Size") }, modifier = Modifier.weight(1f))
            }
        }
        item {
            Button(
                enabled = prompt.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    error = null
                    contentUrl = null
                    status = "queued"
                    progress = 0
                    pollingFailed = false
                    jobId = null
                    scope.launch {
                        runCatching { GenerationApiClient().createVideo(prompt, seconds, size, selectedVideoModelId.takeIf { it.isNotBlank() }) }
                            .onSuccess {
                                jobId = it.jobId
                                status = it.status
                                progress = it.progress
                            }
                            .onFailure {
                                if (it is WorkspaceRedirectException) {
                                    error = null
                                    status = "Redirected to the recommended workspace"
                                    progress = 0
                                    jobId = null
                                } else {
                                    error = safeUserFacingIssue(it, "Video generation failed.")
                                }
                                busy = false
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Rendering…" else "Generate video") }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Render status", fontWeight = FontWeight.Bold)
                    Text(status)
                    if (status == "queued" || status == "in_progress") LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    if (jobId != null) Text("Job: $jobId", style = MaterialTheme.typography.labelSmall)
                    if (contentUrl != null) {
                        Text("Video ready.", color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(contentUrl)))
                                }.onFailure { error = safeUserFacingIssue(it, "The generated video could not be opened.") }
                            }) { Text("Open video") }
                            OutlinedButton(onClick = {
                                val url = contentUrl
                                if (!url.isNullOrBlank()) {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, url)
                                        })
                                    }.onFailure { error = safeUserFacingIssue(it, "The video could not be shared.") }
                                }
                            }) { Text("Share") }
                        }
                    }
                    if (pollingFailed && !busy && jobId != null) {
                        OutlinedButton(onClick = {
                            busy = true
                            pollingFailed = false
                            error = null
                            pollRefresh += 1
                        }) { Text("Retry status check") }
                    }
                    if (status == "failed" && !busy) {
                        OutlinedButton(onClick = {
                            busy = true
                            error = null
                            contentUrl = null
                            status = "queued"
                            progress = 0
                            pollingFailed = false
                            jobId = null
                            scope.launch {
                                runCatching { GenerationApiClient().createVideo(prompt, seconds, size, selectedVideoModelId.takeIf { it.isNotBlank() }) }
                                    .onSuccess {
                                        jobId = it.jobId
                                        status = it.status
                                        progress = it.progress
                                    }
                                    .onFailure {
                                        error = safeUserFacingIssue(it, "Video retry failed.")
                                        busy = false
                                    }
                            }
                        }) { Text("Retry render") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
