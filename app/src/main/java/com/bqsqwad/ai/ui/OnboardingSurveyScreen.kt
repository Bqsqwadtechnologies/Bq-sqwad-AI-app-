package com.bqsqwad.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.DEFAULT_MIN_AGE
import com.bqsqwad.ai.core.OnboardingProfile
import com.bqsqwad.ai.core.ONBOARDING_VERSION
import com.bqsqwad.ai.core.PURPOSE_OPTIONS
import com.bqsqwad.ai.core.ROLE_OPTIONS
import com.bqsqwad.ai.core.isUnderAge
import com.bqsqwad.ai.core.isUnder18
import java.time.Instant

/** Full-screen first-run survey (Prompt H). Cannot skip age. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingSurveyScreen(
    onComplete: (OnboardingProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableIntStateOf(0) }
    var ageText by remember { mutableStateOf("") }
    var blocked by remember { mutableStateOf(false) }
    var purposes by remember { mutableStateOf(setOf<String>()) }
    var role by remember { mutableStateOf<String?>(null) }
    var roleOther by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf("system") }

    val progress = (step + 1) / 5f

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        when (step) {
            0 -> {
                Text("How old are you?", style = MaterialTheme.typography.headlineSmall)
                Text("Required for a safe experience.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = ageText,
                    onValueChange = { ageText = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("Age") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (blocked) {
                    Text(
                        "Sorry — BQ Sqwad AI isn't available for your age group in this region.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = {
                        val age = ageText.toIntOrNull() ?: return@Button
                        if (isUnderAge(age, DEFAULT_MIN_AGE)) {
                            blocked = true
                        } else {
                            blocked = false
                            step = 1
                        }
                    },
                    enabled = ageText.isNotBlank() && !blocked
                ) { Text("Continue") }
            }
            1 -> {
                Text("What do you want to use BQ Sqwad AI for?", style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PURPOSE_OPTIONS.forEach { (id, label) ->
                        FilterChip(
                            selected = id in purposes,
                            onClick = {
                                purposes = if (id in purposes) purposes - id else purposes + id
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Button(onClick = { step = 2 }, enabled = purposes.isNotEmpty()) { Text("Continue") }
            }
            2 -> {
                Text("Who are you?", style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ROLE_OPTIONS.forEach { (id, label) ->
                        FilterChip(
                            selected = role == id,
                            onClick = { role = id },
                            label = { Text(label) }
                        )
                    }
                }
                if (role == "other") {
                    OutlinedTextField(
                        value = roleOther,
                        onValueChange = { roleOther = it },
                        label = { Text("Tell us more") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(onClick = { step = 3 }, enabled = role != null) { Text("Continue") }
            }
            3 -> {
                Text("Choose your look", style = MaterialTheme.typography.headlineSmall)
                listOf(
                    "system" to "White (System) · Recommended",
                    "light" to "Light",
                    "dark" to "Dark"
                ).forEach { (id, label) ->
                    Text(
                        label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { theme = id }
                            .padding(12.dp),
                        color = if (theme == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                Button(onClick = { step = 4 }) { Text("Continue") }
            }
            else -> {
                Text("You're all set", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "BQ Sqwad AI remembers what you tell it, so every chat starts with context. You can see, edit or delete your memory at any time in Settings."
                )
                val age = ageText.toIntOrNull()
                if (age != null && isUnder18(age)) {
                    Text("Safer mode is on for accounts under 18.", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        onComplete(
                            OnboardingProfile(
                                age = ageText.toIntOrNull(),
                                purposes = purposes.toList(),
                                role = role,
                                roleOther = roleOther.takeIf { role == "other" && it.isNotBlank() },
                                theme = theme,
                                completedAt = Instant.now().toString(),
                                version = ONBOARDING_VERSION
                            )
                        )
                    }
                ) { Text("Start chatting") }
            }
        }
    }
}
