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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth

@Composable
internal fun AboutMePanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val prefs = remember(uid) { context.getSharedPreferences("bq_sqwad_about_me_$uid", android.content.Context.MODE_PRIVATE) }
    var aboutMe by remember(uid) { mutableStateOf(prefs.getString("about_me", "") ?: "") }
    var personality by remember(uid) { mutableStateOf(prefs.getString("assistant_personality", "Balanced") ?: "Balanced") }
    val styles = listOf("Balanced", "Friendly", "Concise", "Professional", "Creative", "Tutor")
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("About Me", style = MaterialTheme.typography.headlineSmall)
            Text("Account: " + (FirebaseAuth.getInstance().currentUser?.email ?: "Signed in"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Add optional context and choose a response style. When you send a chat, these preferences are included with that request to personalize the reply. They are not automatically submitted for AI training.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = aboutMe, onValueChange = { aboutMe = it.take(2000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Optional notes about your preferences") }, minLines = 3)
            Text("AI personality", style = MaterialTheme.typography.titleMedium)
            styles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { style ->
                        FilterChip(selected = personality == style, onClick = { personality = style }, label = { Text(style) })
                    }
                }
            }
            Button(onClick = {
                prefs.edit().putString("about_me", aboutMe.trim()).putString("assistant_personality", personality).apply()
                onDismiss()
            }, modifier = Modifier.fillMaxWidth()) { Text("Save on this device") }
            Spacer(Modifier.height(18.dp))
        }
    }
}
