package com.bqsqwad.ai.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.AdminSecurityClient
import kotlinx.coroutines.launch


private fun createQrBitmap(value: String, size: Int = 720): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints)
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}.getOrNull()

@Composable
private fun AuthenticatorQrCode(uri: String) {
    val bitmap = remember(uri) { createQrBitmap(uri) }
    bitmap?.let {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp
        ) {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Authenticator setup QR code",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .aspectRatio(1f)
            )
        }
    } ?: Text(
        "QR code could not be generated. Use the setup URI below.",
        color = MaterialTheme.colorScheme.error
    )
}

@Composable
fun AdminSecurityScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember { AdminSecurityClient() }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var setupSecret by remember { mutableStateOf("") }
    var setupUri by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(action: suspend () -> Unit, success: String) {
        busy = true; error = null
        scope.launch {
            runCatching { action() }
                .onSuccess { status = success }
                .onFailure { error = it.message ?: "Security operation failed." }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { client.status() }
            .onSuccess { status = "Password: " + if (it.passwordConfigured) "configured" else "not configured" +
                " • Authenticator: " + if (it.totpConfigured) "configured" else "not configured" }
            .onFailure { error = it.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin Security") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Step-up authentication", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Firebase admin identity remains the base identity. When you configure these factors, sensitive Admin Control Center operations require a recent password/authenticator verification.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Admin dashboard password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !busy && password.length >= 10,
                    onClick = { run({ client.setPassword(password) }, "Admin password saved.") }
                ) { Text("Set / Replace Password") }
            }
            item {
                Text("Authenticator app", style = MaterialTheme.typography.titleLarge)
                Text("Generate a TOTP setup. Use the displayed setup URI or secret in your authenticator app, then confirm with the current six-digit code.")
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { client.startTotp() }
                                .onSuccess {
                                    setupSecret = it.optString("secret")
                                    setupUri = it.optString("otpauthUri")
                                    status = "Authenticator setup generated."
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    }
                ) { Text("Generate Authenticator Setup") }
                if (setupSecret.isNotBlank()) {
                    Text("Scan with Google Authenticator", style = MaterialTheme.typography.titleMedium)
                    if (setupUri.isNotBlank()) AuthenticatorQrCode(setupUri)
                    SurfaceCard("Authenticator secret", setupSecret)
                    SurfaceCard("Setup URI", setupUri)
                    OutlinedTextField(
                        code, { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("6-digit authenticator code") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !busy && code.length == 6,
                        onClick = { run({ client.confirmTotp(code) }, "Authenticator enabled.") }
                    ) { Text("Confirm Authenticator") }
                }
            }
            item {
                HorizontalDivider()
                Text("Unlock Admin Control Center", style = MaterialTheme.typography.titleLarge)
                Text("After security is enabled, verify the configured factors here to receive a fresh admin step-up session.")
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Authenticator code (if configured)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !busy && password.isNotEmpty(),
                    onClick = { run({ client.unlock(password, code) }, "Admin session unlocked.") }
                ) { Text("Unlock") }
            }
            status?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
