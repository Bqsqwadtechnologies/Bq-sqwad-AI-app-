@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bqsqwad.ai.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.HistoryApiClient
import com.bqsqwad.ai.core.HistoryItem
import com.bqsqwad.ai.core.HistoryMessage
import com.bqsqwad.ai.core.SecretChatCrypto
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class SecretChatRow(val id: String, val updatedAt: String)

@Composable
fun SecretChatVaultDialog(
    open: Boolean,
    createFrom: Pair<HistoryItem, List<HistoryMessage>>?,
    onCreateConsumed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { WorkspaceApiClient() }
    var mode by remember { mutableStateOf("list") }
    var rows by remember { mutableStateOf<List<SecretChatRow>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }
    var primary by remember { mutableStateOf<JSONObject?>(null) }
    var recoveryBlob by remember { mutableStateOf<JSONObject?>(null) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var recoveryMode by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var openedTitle by remember { mutableStateOf("") }
    var openedTranscript by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun loadRows() {
        busy = true
        error = null
        scope.launch {
            runCatching {
                val response = api.listSecretChats()
                val array = response.optJSONArray("chats")
                List(array?.length() ?: 0) { index ->
                    val item = array!!.getJSONObject(index)
                    SecretChatRow(item.optString("id"), item.optString("updatedAt"))
                }
            }.onSuccess { rows = it }
                .onFailure { error = safeUserFacingIssue(it, "Secret chats could not be loaded.") }
            busy = false
        }
    }

    LaunchedEffect(open, createFrom?.first?.id) {
        if (open) {
            mode = if (createFrom != null) "create" else "list"
            password = ""
            confirm = ""
            recoveryCode = ""
            acknowledged = false
            error = null
            notice = null
            if (createFrom == null) loadRows()
        } else {
            openedTitle = ""
            openedTranscript = ""
            primary = null
            recoveryBlob = null
        }
    }

    LaunchedEffect(openedTranscript) {
        if (openedTranscript.isNotBlank()) {
            delay(5 * 60 * 1000L)
            openedTitle = ""
            openedTranscript = ""
            primary = null
            recoveryBlob = null
            mode = "list"
            notice = "Secret chat locked automatically after five minutes."
        }
    }

    DisposableEffect(openedTranscript) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val alreadySecure = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (openedTranscript.isNotBlank()) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (openedTranscript.isNotBlank() && alreadySecure != true) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    fun blobJson(blob: SecretChatCrypto.EncryptedBlob) = JSONObject()
        .put("salt", blob.salt).put("iv", blob.iv).put("ciphertext", blob.ciphertext)

    fun beginUnlock(id: String) {
        selectedId = id
        busy = true
        error = null
        scope.launch {
            runCatching {
                val chat = api.getSecretChat(id).optJSONObject("chat") ?: kotlin.error("Secret chat not found.")
                primary = chat.optJSONObject("primary") ?: kotlin.error("Primary encrypted copy is unavailable.")
                recoveryBlob = chat.optJSONObject("recovery") ?: kotlin.error("Recovery encrypted copy is unavailable.")
                mode = "unlock"
            }.onFailure { error = safeUserFacingIssue(it, "Secret chat could not be opened.") }
            busy = false
        }
    }

    fun unlock() {
        val blob = if (recoveryMode) recoveryBlob else primary
        if (blob == null || password.isBlank()) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                val encrypted = SecretChatCrypto.EncryptedBlob(
                    salt = blob.getString("salt"),
                    iv = blob.getString("iv"),
                    ciphertext = blob.getString("ciphertext"),
                )
                val chars = password.toCharArray()
                val plaintext = try { SecretChatCrypto.decrypt(chars, encrypted) } finally { chars.fill('\u0000') }
                val decoded = JSONObject(String(plaintext, Charsets.UTF_8))
                openedTitle = decoded.optString("title", "Secret chat")
                openedTranscript = decoded.optString("transcript")
                if (openedTranscript.isBlank()) kotlin.error("This secret chat has no readable transcript.")
                mode = "view"
                password = ""
            }.onFailure { error = "That password or recovery code could not unlock this secret chat." }
            busy = false
        }
    }

    fun createSecret() {
        val source = createFrom ?: return
        if (password.length < 8) { error = "Use a password of at least 8 characters."; return }
        if (password != confirm) { error = "Passwords do not match."; return }
        if (recoveryCode.isBlank() || !acknowledged) { error = "Generate and store the recovery code before continuing."; return }
        val transcript = source.second.joinToString("\n\n") { message ->
            (if (message.mine) "You" else "BQ Sqwad AI") + ": " + message.text
        }.trim()
        if (transcript.isBlank()) { error = "This conversation has no text to encrypt."; return }
        busy = true
        error = null
        scope.launch {
            val chatId = source.first.id
            runCatching {
                val plaintext = JSONObject().put("title", source.first.title).put("transcript", transcript).toString().toByteArray(Charsets.UTF_8)
                val passChars = password.toCharArray()
                val recoveryChars = recoveryCode.toCharArray()
                val encrypted = try {
                    SecretChatCrypto.encrypt(passChars, plaintext) to SecretChatCrypto.encrypt(recoveryChars, plaintext)
                } finally {
                    passChars.fill('\u0000')
                    recoveryChars.fill('\u0000')
                    plaintext.fill(0)
                }
                api.saveSecretChat(
                    JSONObject().put("chatId", chatId)
                        .put("primary", blobJson(encrypted.first))
                        .put("recovery", blobJson(encrypted.second))
                )
                try {
                    HistoryApiClient().delete(chatId)
                } catch (deleteError: Throwable) {
                    runCatching { api.deleteSecretChat(chatId) }
                    throw IllegalStateException("The normal history copy could not be removed. The secret-chat move was rolled back.", deleteError)
                }
            }.onSuccess {
                notice = "Conversation encrypted and moved to Secret Chats."
                onCreateConsumed()
                mode = "list"
                loadRows()
            }.onFailure { error = safeUserFacingIssue(it, "Secret chat could not be saved.") }
            busy = false
        }
    }

    if (!open) return
    val title = when (mode) {
        "create" -> "Create secret chat"
        "unlock" -> "Unlock secret chat"
        "view" -> "Secret chat"
        else -> "Secret Chats"
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (mode) {
                    "create" -> {
                        Text("The conversation is encrypted on this device before upload. The server receives ciphertext only. The ordinary history copy is removed after the encrypted save succeeds.")
                        OutlinedTextField(password, { password = it }, label = { Text("Secret-chat password (8+ characters)") }, singleLine = true)
                        OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm password") }, singleLine = true)
                        Button(onClick = { recoveryCode = SecretChatCrypto.generateRecoveryCode(); acknowledged = false }, enabled = !busy) { Text("Generate recovery code") }
                        if (recoveryCode.isNotBlank()) Text(recoveryCode)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                            Text("I saved this recovery code somewhere safe.")
                        }
                    }
                    "unlock" -> {
                        Text("Unlock with your password or recovery code.")
                        OutlinedTextField(password, { password = it }, label = { Text(if (recoveryMode) "Recovery code" else "Password") }, singleLine = true)
                        TextButton(onClick = { recoveryMode = !recoveryMode; password = "" }) { Text(if (recoveryMode) "Use password" else "Use recovery code") }
                    }
                    "view" -> {
                        Text(openedTitle, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text(openedTranscript, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        Text("This view locks automatically after five minutes. Screen capture is blocked while the secret is open.")
                    }
                    else -> {
                        notice?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
                        error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                        if (busy) Text("Loading encrypted chats…")
                        if (!busy && rows.isEmpty()) Text("No secret chats saved yet.")
                        rows.forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("Secret chat")
                                    Text(row.updatedAt, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { beginUnlock(row.id) }, enabled = !busy) { Text("Unlock") }
                            }
                        }
                    }
                }
                if (mode != "list") {
                    error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                    notice?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
                }
            }
        },
        confirmButton = {
            when (mode) {
                "create" -> TextButton(onClick = { createSecret() }, enabled = !busy) { Text(if (busy) "Encrypting…" else "Encrypt & move") }
                "unlock" -> TextButton(onClick = { unlock() }, enabled = !busy && password.isNotBlank()) { Text(if (busy) "Unlocking…" else "Unlock") }
                "view" -> TextButton(onClick = { openedTitle = ""; openedTranscript = ""; primary = null; recoveryBlob = null; mode = "list" }) { Text("Lock now") }
                else -> TextButton(onClick = { loadRows() }, enabled = !busy) { Text("Refresh") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mode == "view") {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { api.deleteSecretChat(selectedId) }
                                .onSuccess { openedTitle = ""; openedTranscript = ""; mode = "list"; loadRows(); notice = "Secret chat deleted." }
                                .onFailure { error = safeUserFacingIssue(it, "Secret chat could not be deleted.") }
                        }
                    }, enabled = !busy) { Text("Delete") }
                }
                TextButton(onClick = {
                    if (mode == "list") onDismiss()
                    else { mode = "list"; password = ""; error = null; openedTitle = ""; openedTranscript = "" }
                }, enabled = !busy) { Text(if (mode == "list") "Close" else "Back") }
            }
        }
    )
}
