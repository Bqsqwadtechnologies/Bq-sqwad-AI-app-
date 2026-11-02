package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun MonthlyFeedbackPrompt(eligible: Boolean) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var campaign by remember { mutableStateOf<JSONObject?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var rating by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf("chat") }
    var comment by remember { mutableStateOf("") }
    var categoryMenu by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(eligible) {
        if (eligible) campaign = runCatching { api.monthlyFeedbackState() }.getOrNull()
    }

    if (eligible && !dismissed && campaign?.optBoolean("available", false) == true &&
        campaign?.optBoolean("submitted", false) == false
    ) {
        AlertDialog(
            onDismissRequest = { if (!submitting) dismissed = true },
            title = { Text(campaign?.optString("title", "Monthly feedback") ?: "Monthly feedback") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(campaign?.optString("message", "Tell us how BQ Sqwad AI worked for you this month.") ?: "")
                    Text("How would you rate your experience?")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { value ->
                            if (rating == value) Button(onClick = { rating = value }) { Text(value.toString()) }
                            else OutlinedButton(onClick = { rating = value }) { Text(value.toString()) }
                        }
                    }
                    Text("What did you use most?")
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { categoryMenu = true }) { Text(category.replaceFirstChar { it.uppercase() }) }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            listOf("chat", "teams", "business", "organization", "media", "other").forEach { option ->
                                DropdownMenuItem(text = { Text(option.replaceFirstChar { it.uppercase() }) }, onClick = {
                                    category = option
                                    categoryMenu = false
                                })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it.take(1200) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Anything we should improve? (optional)") },
                        minLines = 2,
                        maxLines = 4
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = rating in 1..5 && !submitting, onClick = {
                    submitting = true
                    error = null
                    scope.launch {
                        runCatching { api.submitMonthlyFeedback(rating, category, comment) }
                            .onSuccess { dismissed = true }
                            .onFailure { error = "Your feedback could not be sent. Please try again." }
                        submitting = false
                    }
                }) { Text(if (submitting) "Sending…" else "Submit") }
            },
            dismissButton = { TextButton(enabled = !submitting, onClick = { dismissed = true }) { Text("Not now") } }
        )
    }
}
