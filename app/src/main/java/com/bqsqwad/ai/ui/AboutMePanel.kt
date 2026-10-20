package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.bqsqwad.ai.core.SettingsSyncClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutMePanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val prefs = remember(uid) { context.getSharedPreferences("bq_sqwad_about_me_$uid", android.content.Context.MODE_PRIVATE) }
    var aboutMe by remember(uid) { mutableStateOf(prefs.getString("about_me", "") ?: "") }
    var interests by remember(uid) { mutableStateOf("") }
    var goals by remember(uid) { mutableStateOf("") }
    var preferences by remember(uid) { mutableStateOf("") }
    var instructions by remember(uid) { mutableStateOf("") }
    var personality by remember(uid) { mutableStateOf(prefs.getString("assistant_personality", "Balanced") ?: "Balanced") }
    var loading by remember(uid) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val styles = listOf("Balanced", "Friendly", "Concise", "Professional", "Creative", "Tutor")

    LaunchedEffect(uid) {
        if (uid.isNotBlank()) {
            runCatching { SettingsSyncClient.load() }
                .onSuccess { response ->
                    val settings = response.optJSONObject("settings") ?: JSONObject()
                    val personal = settings.optJSONObject("personalInformation") ?: JSONObject()
                    aboutMe = personal.optString("aboutMe", aboutMe)
                    interests = personal.optString("interests", "")
                    goals = personal.optString("goals", "")
                    preferences = personal.optString("preferences", "")
                    instructions = personal.optString("instructions", "")
                    personality = personal.optString("responseStyle", personality).takeIf { it in styles } ?: "Balanced"
                    val compiled = listOf(
                        "About me: " to aboutMe,
                        "Interests: " to interests,
                        "Goals: " to goals,
                        "Response preferences: " to preferences,
                        "Important context: " to instructions
                    ).filter { it.second.isNotBlank() }.joinToString("\n") { it.first + it.second }.take(7000)
                    prefs.edit().putString("about_me", compiled).putString("assistant_personality", personality).apply()
                }
                .onFailure { notice = "Could not refresh your saved profile. You can retry or continue editing." }
        }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Personal information for your AI", style = MaterialTheme.typography.headlineSmall)
            Text("Account: " + (FirebaseAuth.getInstance().currentUser?.email ?: "Signed in"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Saved to your account and shared with the website. Only add information you want BQ Sqwad AI to use for personalisation. It is not automatically submitted for AI training.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = aboutMe, onValueChange = { aboutMe = it.take(2000) }, modifier = Modifier.fillMaxWidth(), label = { Text("About me") }, minLines = 3)
            OutlinedTextField(value = interests, onValueChange = { interests = it.take(1200) }, modifier = Modifier.fillMaxWidth(), label = { Text("Interests") }, minLines = 2)
            OutlinedTextField(value = goals, onValueChange = { goals = it.take(1200) }, modifier = Modifier.fillMaxWidth(), label = { Text("Goals") }, minLines = 2)
            OutlinedTextField(value = preferences, onValueChange = { preferences = it.take(1200) }, modifier = Modifier.fillMaxWidth(), label = { Text("How you prefer AI to help") }, minLines = 2)
            OutlinedTextField(value = instructions, onValueChange = { instructions = it.take(1200) }, modifier = Modifier.fillMaxWidth(), label = { Text("Important context or instructions (optional)") }, minLines = 2)
            Text("AI response style", style = MaterialTheme.typography.titleMedium)
            styles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { style ->
                        FilterChip(selected = personality == style, onClick = { personality = style }, label = { Text(style) })
                    }
                }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Button(enabled = !saving && !loading, onClick = {
                saving = true
                val personal = JSONObject()
                    .put("aboutMe", aboutMe.trim())
                    .put("interests", interests.trim())
                    .put("goals", goals.trim())
                    .put("preferences", preferences.trim())
                    .put("instructions", instructions.trim())
                    .put("responseStyle", personality)
                scope.launch {
                    runCatching { SettingsSyncClient.save(JSONObject().put("personalInformation", personal)) }
                        .onSuccess {
                            val compiled = listOf(
                                "About me: " to aboutMe.trim(),
                                "Interests: " to interests.trim(),
                                "Goals: " to goals.trim(),
                                "Response preferences: " to preferences.trim(),
                                "Important context: " to instructions.trim()
                            ).filter { it.second.isNotBlank() }.joinToString("\n") { it.first + it.second }.take(7000)
                            prefs.edit().putString("about_me", compiled).putString("assistant_personality", personality).apply()
                            notice = "Personal information saved to your account and synced with the website."
                            onDismiss()
                        }
                        .onFailure { notice = it.message?.take(180) ?: "Could not save your profile. Please try again." }
                    saving = false
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "Saving…" else if (loading) "Loading…" else "Save personal information")
            }
            OutlinedButton(enabled = !saving && !loading, onClick = {
                aboutMe = ""; interests = ""; goals = ""; preferences = ""; instructions = ""; personality = "Balanced"
                saving = true
                scope.launch {
                    runCatching {
                        SettingsSyncClient.save(JSONObject().put("personalInformation", JSONObject()
                            .put("aboutMe", "").put("interests", "").put("goals", "").put("preferences", "")
                            .put("instructions", "").put("responseStyle", "Balanced")))
                    }.onSuccess {
                        prefs.edit().putString("about_me", "").putString("assistant_personality", "Balanced").apply()
                        notice = "Saved personal information cleared from your account."
                    }.onFailure { notice = "Could not clear your saved profile." }
                    saving = false
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Clear saved information") }
            Spacer(Modifier.height(18.dp))
        }
    }
}
