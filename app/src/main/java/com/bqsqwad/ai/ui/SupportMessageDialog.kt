package com.bqsqwad.ai.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@Composable
fun SupportMessageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = FirebaseAuth.getInstance().currentUser
    var name by remember { mutableStateOf(user?.displayName.orEmpty()) }
    var email by remember { mutableStateOf(user?.email.orEmpty()) }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Contact support") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Send a question, complaint, or feedback to the BQ Sqwad AI team. You can track it from the admin support inbox.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(email, { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Email for reply") }, singleLine = true)
                OutlinedTextField(subject, { subject = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Subject") }, singleLine = true)
                OutlinedTextField(message, { message = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message") }, minLines = 3, maxLines = 6)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !sending && subject.trim().isNotEmpty() && message.trim().isNotEmpty(),
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        runCatching {
                            WorkspaceApiClient().submitSupportMessage(name.trim(), email.trim(), subject.trim(), message.trim())
                        }.onSuccess {
                            Toast.makeText(context, "Message sent to BQ Sqwad AI support.", Toast.LENGTH_LONG).show()
                            onDismiss()
                        }.onFailure {
                            error = it.message ?: "Message could not be sent. Please try again."
                        }
                        sending = false
                    }
                }
            ) { Text(if (sending) "Sending…" else "Send message") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") } }
    )
}
