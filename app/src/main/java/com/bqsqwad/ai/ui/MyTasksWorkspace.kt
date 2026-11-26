package com.bqsqwad.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class MyTaskRow(
    val id: String,
    val title: String,
    val description: String,
    val type: String,
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
    var userFriendlyError by remember { mutableStateOf<String?>(null) }
    var activeFilter by remember { mutableStateOf("all") } // all, in_progress, goal, done

    // Dialog state for adding a new task or monthly goal
    var showCreateDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf("personal") } // personal or monthly_goal
    var newDueAt by remember { mutableStateOf("") }
    var isSavingTask by remember { mutableStateOf(false) }

    // Dialog state for BQ Code Run gating
    var showCodeRunGate by remember { mutableStateOf(false) }

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
                    type = row.optString("type", "general"),
                    status = row.optString("status", "open"),
                    dueAt = if (row.isNull("dueAt")) "" else row.optString("dueAt"),
                )
            )
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            userFriendlyError = null
            runCatching { api.myTasks() }
                .onSuccess(::parse)
                .onFailure {
                    // Friendly message hiding technical database or network stack details
                    userFriendlyError = "Unable to sync with tasks service right now. Please check your internet connection."
                }
            loading = false
        }
    }

    fun changeStatus(task: MyTaskRow, status: String) {
        if (busyId.isNotEmpty()) return
        scope.launch {
            busyId = task.id
            userFriendlyError = null
            runCatching { api.updateMyTaskStatus(task.id, status) }
                .onSuccess {
                    val index = tasks.indexOfFirst { row -> row.id == task.id }
                    if (index >= 0) tasks[index] = task.copy(status = status)
                }
                .onFailure {
                    userFriendlyError = "Could not update task status. Please try again."
                }
            busyId = ""
        }
    }

    fun handleSaveTask() {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            isSavingTask = true
            userFriendlyError = null
            runCatching {
                api.createMyTask(
                    title = trimmed,
                    description = newDesc.trim(),
                    type = newType,
                    dueAt = newDueAt.trim()
                )
            }.onSuccess {
                newTitle = ""
                newDesc = ""
                newDueAt = ""
                newType = "personal"
                showCreateDialog = false
                refresh()
            }.onFailure {
                // If cloud fails, add local optimistic task so user never loses their input
                val localId = "task_" + System.currentTimeMillis()
                tasks.add(
                    0,
                    MyTaskRow(
                        id = localId,
                        title = trimmed,
                        description = newDesc.trim(),
                        type = newType,
                        status = "open",
                        dueAt = newDueAt.trim()
                    )
                )
                newTitle = ""
                newDesc = ""
                newDueAt = ""
                showCreateDialog = false
            }
            isSavingTask = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val totalCount = tasks.size
    val completedCount = tasks.count { it.status == "done" }
    val inProgressCount = tasks.count { it.status == "in_progress" }
    val goals = tasks.filter { it.type == "monthly_goal" || it.type == "goal" }
    val goalsDone = goals.count { it.status == "done" }
    val progressPercent = if (goals.isNotEmpty()) {
        (goalsDone.toFloat() / goals.size.toFloat()).coerceIn(0f, 1f)
    } else if (totalCount > 0) {
        (completedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val displayedTasks = when (activeFilter) {
        "in_progress" -> tasks.filter { it.status == "in_progress" }
        "goal" -> tasks.filter { it.type == "monthly_goal" || it.type == "goal" }
        "done" -> tasks.filter { it.status == "done" }
        else -> tasks
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Top Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BqLogo(44.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("My Tasks & Goals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Track your assigned work and monthly targets.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = ::refresh,
                        enabled = !loading,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Add Task Action Button (100% active)
                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Personal Task or Goal", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Monthly Achievement Progress Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("Monthly Achievement", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "${(progressPercent * 100).toInt()}% Done",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                LinearProgressIndicator(
                    progress = { progressPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$totalCount Total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$inProgressCount In Progress", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$completedCount Completed", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // BQ Code Run Gating Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("BQ Code Run", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Icon(Icons.Default.Lock, contentDescription = "Paid Feature", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Cloud execution sandbox", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(
                    onClick = { showCodeRunGate = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Launch", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = activeFilter == "all",
                onClick = { activeFilter = "all" },
                label = { Text("All ($totalCount)") }
            )
            FilterChip(
                selected = activeFilter == "in_progress",
                onClick = { activeFilter = "in_progress" },
                label = { Text("In Progress ($inProgressCount)") }
            )
            FilterChip(
                selected = activeFilter == "goal",
                onClick = { activeFilter = "goal" },
                label = { Text("Goals (${goals.size})") }
            )
            FilterChip(
                selected = activeFilter == "done",
                onClick = { activeFilter = "done" },
                label = { Text("Done ($completedCount)") }
            )
        }

        // Friendly error notice
        userFriendlyError?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Task Items List
        if (loading && tasks.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (displayedTasks.isEmpty()) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("No tasks found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (activeFilter == "all") "Create your first personal task or monthly goal using the button above."
                        else "No tasks match this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(displayedTasks, key = { it.id }) { task ->
                    val isGoal = task.type == "monthly_goal" || task.type == "goal"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        if (isGoal) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    "GOAL",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (task.status == "done") MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        task.status.replace('_', ' ').uppercase(),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (task.status == "done") MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (task.description.isNotBlank()) {
                                Text(task.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (task.dueAt.isNotBlank()) {
                                Text("Target Date: " + task.dueAt.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when (task.status) {
                                    "open" -> {
                                        Button(
                                            onClick = { changeStatus(task, "in_progress") },
                                            enabled = busyId != task.id,
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Start Working")
                                        }
                                    }
                                    "in_progress" -> {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { changeStatus(task, "blocked") },
                                                enabled = busyId != task.id,
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("Hold")
                                            }
                                            Button(
                                                onClick = { changeStatus(task, "done") },
                                                enabled = busyId != task.id,
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Done")
                                            }
                                        }
                                    }
                                    "blocked" -> {
                                        Button(
                                            onClick = { changeStatus(task, "in_progress") },
                                            enabled = busyId != task.id,
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Resume")
                                        }
                                    }
                                    "done" -> {
                                        OutlinedButton(
                                            onClick = { changeStatus(task, "open") },
                                            enabled = busyId != task.id,
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Reopen")
                                        }
                                    }
                                }
                                if (busyId == task.id) {
                                    Spacer(Modifier.width(8.dp))
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Create Task or Monthly Goal Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSavingTask) showCreateDialog = false },
            title = { Text("Create Task or Goal", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Title *") },
                        placeholder = { Text("What do you want to accomplish?") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = newType == "personal",
                            onClick = { newType = "personal" },
                            label = { Text("Personal Task") }
                        )
                        FilterChip(
                            selected = newType == "monthly_goal",
                            onClick = { newType = "monthly_goal" },
                            label = { Text("Monthly Goal") }
                        )
                    }

                    OutlinedTextField(
                        value = newDueAt,
                        onValueChange = { newDueAt = it },
                        label = { Text("Target Due Date (Optional)") },
                        placeholder = { Text("YYYY-MM-DD") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = newDesc,
                        onValueChange = { newDesc = it },
                        label = { Text("Description (Optional)") },
                        placeholder = { Text("Add specific notes or milestones...") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = ::handleSaveTask,
                    enabled = newTitle.isNotBlank() && !isSavingTask,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isSavingTask) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Save Task")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateDialog = false },
                    enabled = !isSavingTask
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // BQ Code Run Gating Modal Dialog
    if (showCodeRunGate) {
        AlertDialog(
            onDismissRequest = { showCodeRunGate = false },
            icon = {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Paid Credits Required", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "BQ Code Run is an advanced cloud sandbox execution tool reserved for active credit accounts. Free tier users cannot run code sandboxes. Please top up your BQ credits to unlock this feature.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { showCodeRunGate = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Top Up Credits")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCodeRunGate = false }) {
                    Text("Close")
                }
            }
        )
    }
}
