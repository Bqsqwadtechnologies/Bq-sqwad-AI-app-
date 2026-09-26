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

    LaunchedEffect(jobId) {
        val id = jobId ?: return@LaunchedEffect
        while (true) {
            runCatching { GenerationApiClient().videoJob(id) }
                .onSuccess {
                    status = it.status
                    progress = it.progress
                    contentUrl = it.contentUrl
                    error = it.error
                }
                .onFailure { error = it.message }
            if (status == "completed" || status == "failed") break
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
                    scope.launch {
                        runCatching { GenerationApiClient().createVideo(prompt, seconds, size) }
                            .onSuccess {
                                jobId = it.jobId
                                status = it.status
                                progress = it.progress
                            }
                            .onFailure {
                                error = it.message ?: "Video generation failed."
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
                    if (status == "queued" || status == "in_progress") LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    if (jobId != null) Text("Job: $jobId", style = MaterialTheme.typography.labelSmall)
                    if (contentUrl != null) {
                        Text("Video ready.", color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(contentUrl)))
                                }.onFailure { error = it.message ?: "The generated video could not be opened." }
                            }) { Text("Open video") }
                            OutlinedButton(onClick = {
                                val url = contentUrl
                                if (!url.isNullOrBlank()) {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(Intent.EXTRA_TEXT, url)
                                        })
                                    }.onFailure { error = it.message ?: "The video could not be shared." }
                                }
                            }) { Text("Share") }
                        }
                    }
                    if (status == "failed" && !busy) {
                        OutlinedButton(onClick = {
                            busy = true
                            error = null
                            contentUrl = null
                            scope.launch {
                                runCatching { GenerationApiClient().createVideo(prompt, seconds, size) }
                                    .onSuccess {
                                        jobId = it.jobId
                                        status = it.status
                                        progress = it.progress
                                    }
                                    .onFailure {
                                        error = it.message ?: "Video retry failed."
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
