package com.bqsqwad.ai.ui.onboarding

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.*

private val Blue = Color(0xFF1457FF)

@Composable
fun OnboardingSurvey(
    onComplete: (OnboardingProfile) -> Unit,
    minAge: Int = DEFAULT_MIN_AGE,
) {
    var step by remember { mutableIntStateOf(0) }
    var ageText by remember { mutableStateOf("") }
    var blocked by remember { mutableStateOf(false) }
    var purposes by remember { mutableStateOf(setOf<String>()) }
    var role by remember { mutableStateOf<String?>(null) }
    var roleOther by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf("system") }

    val progress = (step + 1) / 5f

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (step) {
                    0 -> {
                        Text("How old are you?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Required. This helps keep BQ Sqwad AI safe and age-appropriate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (blocked) {
                            Text("You must be at least $minAge to use BQ Sqwad AI.", color = MaterialTheme.colorScheme.error)
                        } else {
                            OutlinedTextField(
                                value = ageText,
                                onValueChange = { ageText = it.filter { c -> c.isDigit() }.take(3) },
                                label = { Text("Your age") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    val n = ageText.toIntOrNull() ?: 0
                                    if (isUnderAge(n, minAge)) blocked = true else step = 1
                                },
                                enabled = ageText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Blue)
                            ) { Text("Continue") }
                        }
                    }
                    1 -> {
                        Text("What do you want to use BQ Sqwad AI for?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Select all that apply.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(PURPOSE_OPTIONS) { (id, label) ->
                                val selected = id in purposes
                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            purposes = if (selected) purposes - id else purposes + id
                                        }
                                        .then(
                                            if (selected) Modifier.border(2.dp, Blue, RoundedCornerShape(12.dp))
                                            else Modifier
                                        ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(label, Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        Button(
                            onClick = { step = 2 },
                            enabled = purposes.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue)
                        ) { Text("Continue") }
                    }
                    2 -> {
                        Text("Who are you?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(ROLE_OPTIONS) { (id, label) ->
                                val selected = role == id
                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { role = id }
                                        .then(
                                            if (selected) Modifier.border(2.dp, Blue, RoundedCornerShape(12.dp))
                                            else Modifier
                                        ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(label, Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        if (role == "other") {
                            OutlinedTextField(roleOther, { roleOther = it }, label = { Text("Tell us briefly") }, modifier = Modifier.fillMaxWidth())
                        }
                        Button(
                            onClick = { step = 3 },
                            enabled = role != null,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue)
                        ) { Text("Continue") }
                    }
                    3 -> {
                        Text("Choose your look", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("White (System) is recommended.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf(
                            "system" to "White (System) · Recommended",
                            "light" to "Light",
                            "dark" to "Dark",
                        ).forEach { (id, label) ->
                            OutlinedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { theme = id }
                                    .then(if (theme == id) Modifier.border(2.dp, Blue, RoundedCornerShape(12.dp)) else Modifier),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(label, Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Button(
                            onClick = { step = 4 },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue)
                        ) { Text("Continue") }
                    }
                    else -> {
                        Text("You're all set", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "BQ Sqwad AI remembers what you tell it, so every chat starts with context. You can see, edit or delete your memory at any time in Settings.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                onComplete(
                                    OnboardingProfile(
                                        age = ageText.toIntOrNull(),
                                        purposes = purposes.toList(),
                                        role = role,
                                        roleOther = if (role == "other") roleOther else null,
                                        theme = theme,
                                        completedAt = java.time.Instant.now().toString(),
                                        version = ONBOARDING_VERSION,
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue)
                        ) { Text("Start using BQ Sqwad AI") }
                    }
                }
            }
        }
    }
}
