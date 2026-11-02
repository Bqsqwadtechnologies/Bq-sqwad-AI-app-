package com.bqsqwad.ai.ui

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bqsqwad.ai.core.HistoryApiClient
import com.bqsqwad.ai.core.SecretChatCrypto
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private data class SecretChatListItem(val id: String, val updatedAt: String)
private data class SecretCipherRecord(val id: String, val primary: SecretChatCrypto.EncryptedBlob, val recovery: SecretChatCrypto.EncryptedBlob)

@Composable
fun SecretChatsDialog(open: Boolean, chatId: String?, transcript: String, title: String, onClose: () -> Unit, onMoved: () -> Unit) {
    if (!open) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { WorkspaceApiClient() }
    var items by remember { mutableStateOf<List<SecretChatListItem>>(emptyList()) }
    var selected by remember { mutableStateOf<SecretCipherRecord?>(null) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var recoveryMode by remember { mutableStateOf(false) }
    var recoveryAcknowledged by remember { mutableStateOf(false) }
    var openedText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            runCatching {
                val response = api.listSecretChats()
                val rows = response.optJSONArray("chats")
                buildList {
                    for (index in 0 until (rows?.length() ?: 0)) {
                        val row = rows?.optJSONObject(index) ?: continue
                        val id = row.optString("id")
                        if (id.isNotBlank()) add(SecretChatListItem(id, row.optString("updatedAt")))
                    }
                }
            }.onSuccess { items = it }.onFailure { error = it.message ?: "Could not load secret chats." }
        }
    }

    LaunchedEffect(open) { if (open) refresh() }
    LaunchedEffect(openedText) {
        if (openedText != null) {
            delay(5 * 60 * 1000L)
            openedText = null
            selected = null
            password = ""
            recoveryMode = false
        }
    }
    DisposableEffect(open) {
        val activity = context as? android.app.Activity
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        activity?.window?.setFlags(flag, flag)
        onDispose { activity?.window?.clearFlags(flag) }
    }

    Dialog(onDismissRequest = { if (!busy) onClose() }) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 680.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Secret chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Conversations are encrypted on this device before upload. The server receives ciphertext only. Opened content auto-locks after five minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (transcript.isNotBlank()) {
                    HorizontalDivider()
                    Text("Move this conversation to Secret Chats", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(password, { password = it }, label = { Text("Secret password (8+ characters)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (recoveryCode.isBlank()) {
                        OutlinedButton(onClick = { recoveryCode = SecretChatCrypto.generateRecoveryCode(); recoveryAcknowledged = false }, enabled = !busy) { Text("Generate recovery code") }
                    } else {
                        Text("Recovery code — store it safely; it is shown only in this session:", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(recoveryCode, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = recoveryAcknowledged, onCheckedChange = { recoveryAcknowledged = it }, enabled = !busy)
                            Text("I have saved my recovery code.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = {
                        if (password.length < 8 || password != confirm || recoveryCode.isBlank() || !recoveryAcknowledged) {
                            error = "Use matching passwords of at least 8 characters and save the recovery code."
                            return@Button
                        }
                        busy = true
                        error = null
                        scope.launch {
                            val id = chatId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                            val plain = JSONObject().put("title", title.take(120)).put("transcript", transcript).toString()
                            runCatching {
                                val encrypted = withContext(Dispatchers.Default) {
                                    SecretChatCrypto.encrypt(password.toCharArray(), plain.toByteArray(Charsets.UTF_8)) to
                                        SecretChatCrypto.encrypt(recoveryCode.toCharArray(), plain.toByteArray(Charsets.UTF_8))
                                }
                                fun blob(value: SecretChatCrypto.EncryptedBlob) = JSONObject().put("salt", value.salt).put("iv", value.iv).put("ciphertext", value.ciphertext)
                                api.saveSecretChat(JSONObject().put("chatId", id).put("primary", blob(encrypted.first)).put("recovery", blob(encrypted.second)))
                                if (!chatId.isNullOrBlank()) {
                                    runCatching { HistoryApiClient().delete(chatId) }.getOrElse {
                                        api.deleteSecretChat(id)
                                        throw IllegalStateException("The ordinary conversation could not be removed. The encrypted move was rolled back; retry after resolving the history issue.")
                                    }
                                }
                            }.onSuccess {
                                password = ""; confirm = ""; recoveryCode = ""; recoveryAcknowledged = false
                                onMoved(); onClose()
                            }.onFailure { error = it.message ?: "Could not encrypt this conversation." }
                            busy = false
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Encrypting…" else "Encrypt and remove ordinary copy") }
                }
                HorizontalDivider()
                Text("Saved secret chats", fontWeight = FontWeight.SemiBold)
                if (items.isEmpty()) Text("No saved secret chats.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.id.take(18), fontWeight = FontWeight.Medium)
                            Text(item.updatedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            busy = true; error = null
                            scope.launch {
                                runCatching {
                                    val response = api.getSecretChat(item.id).optJSONObject("chat") ?: throw IllegalStateException("Secret chat not found.")
                                    fun blob(key: String): SecretChatCrypto.EncryptedBlob {
                                        val value = response.optJSONObject(key) ?: throw IllegalStateException("Encrypted record is incomplete.")
                                        return SecretChatCrypto.EncryptedBlob(value.getString("salt"), value.getString("iv"), value.getString("ciphertext"))
                                    }
                                    SecretCipherRecord(item.id, blob("primary"), blob("recovery"))
                                }.onSuccess { selected = it; openedText = null; password = ""; recoveryMode = false }
                                    .onFailure { error = it.message ?: "Could not load encrypted chat." }
                                busy = false
                            }
                        }, enabled = !busy) { Text("Unlock") }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { api.deleteSecretChat(item.id) }
                                    .onSuccess { if (selected?.id == item.id) { selected = null; openedText = null }; refresh() }
                                    .onFailure { error = it.message ?: "Could not delete secret chat." }
                            }
                        }, enabled = !busy) { Text("Delete") }
                    }
                }
                selected?.let { record ->
                    HorizontalDivider()
                    Text("Unlock selected secret chat", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(password, { password = it }, label = { Text(if (recoveryMode) "Recovery code" else "Password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = recoveryMode, onCheckedChange = { recoveryMode = it }, enabled = !busy)
                        Text("Use recovery code", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching {
                                val blob = if (recoveryMode) record.recovery else record.primary
                                val plain = withContext(Dispatchers.Default) { SecretChatCrypto.decrypt(password.toCharArray(), blob) }
                                String(plain, Charsets.UTF_8)
                            }.onSuccess { openedText = it; password = "" }
                                .onFailure { error = "That password or recovery code could not unlock this secret chat." }
                            busy = false
                        }
                    }, enabled = !busy && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Unlock on this device") }
                    openedText?.let { plaintext ->
                        SelectionContainer { Text(plaintext, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { openedText = null; selected = null; password = "" }) { Text("Lock now") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { refresh() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
                TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
