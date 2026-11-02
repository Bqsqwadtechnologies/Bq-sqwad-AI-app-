package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class MyTaskRow(
    val id: String,
    val title: String,
    val description: String,
    val status: String,
    val dueAt: String,
)

@Composable
fun MyTasksWorkspace() {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    val tasks = remember { mutableStateListOf<MyTaskRow>() }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun parse(body: JSONObject) {
        tasks.clear()
        val rows = body.optJSONArray("tasks") ?: org.json.JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id")
            val title = row.optString("title")
            if (id.isBlank() || title.isBlank()) continue
            tasks.add(
                MyTaskRow(
                    id = id,
                    title = title,
                    description = row.optString("description"),
                    status = row.optString("status", "open"),
                    dueAt = if (row.isNull("dueAt")) "" else row.optString("dueAt"),
                )
            )
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { api.myTasks() }
                .onSuccess(::parse)
                .onFailure { error = it.message ?: "Tasks could not be loaded." }
            loading = false
        }
    }

    fun changeStatus(task: MyTaskRow, status: String) {
        if (busyId.isNotEmpty()) return
        scope.launch {
            busyId = task.id
            error = null
            runCatching { api.updateMyTaskStatus(task.id, status) }
                .onSuccess {
                    val index = tasks.indexOfFirst { row -> row.id == task.id }
                    if (index >= 0) tasks[index] = task.copy(status = status)
                }
                .onFailure { error = it.message ?: "Task status could not be updated." }
            busyId = ""
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("My Tasks", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Tasks assigned to your signed-in account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = ::refresh, enabled = !loading) { Text("Refresh") }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (loading && tasks.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (tasks.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No assigned tasks", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "When an administrator assigns a task to your account, it will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(task.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    task.status.replace('_', ' '),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (task.description.isNotBlank()) {
                                Text(task.description, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (task.dueAt.isNotBlank()) {
                                Text("Due: " + task.dueAt.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (task.status) {
                                    "open" -> Button(onClick = { changeStatus(task, "in_progress") }, enabled = busyId != task.id) { Text("Start") }
                                    "in_progress" -> {
                                        OutlinedButton(onClick = { changeStatus(task, "blocked") }, enabled = busyId != task.id) { Text("Blocked") }
                                        Button(onClick = { changeStatus(task, "done") }, enabled = busyId != task.id) { Text("Complete") }
                                    }
                                    "blocked" -> Button(onClick = { changeStatus(task, "in_progress") }, enabled = busyId != task.id) { Text("Resume") }
                                    "done" -> OutlinedButton(onClick = { changeStatus(task, "open") }, enabled = busyId != task.id) { Text("Reopen") }
                                }
                                if (busyId == task.id) CircularProgressIndicator(modifier = Modifier.height(28.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
