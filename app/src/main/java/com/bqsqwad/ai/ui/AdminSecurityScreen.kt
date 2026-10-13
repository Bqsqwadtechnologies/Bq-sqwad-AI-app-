@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.platform.LocalContext
import com.bqsqwad.ai.core.AdminSecurityClient
import com.bqsqwad.ai.core.AdminBiometricClient
import com.bqsqwad.ai.core.PasskeyClient
import com.google.firebase.auth.FirebaseAuth
import androidx.biometric.BiometricManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay


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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { AdminSecurityClient() }
    val biometricClient = remember { AdminBiometricClient(context) }
    val passkeyClient = remember { PasskeyClient(context) }
    var passkeyIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var setupSecret by remember { mutableStateOf("") }
    var setupUri by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var biometricConfigured by remember { mutableStateOf(false) }
    var passwordConfigured by remember { mutableStateOf(false) }
    var totpConfigured by remember { mutableStateOf(false) }
    var sessionRemainingSeconds by remember { mutableIntStateOf(0) }
    var sessionExpiry by remember { mutableStateOf<String?>(null) }

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
            .onSuccess {
                biometricConfigured = it.biometricConfigured
                passwordConfigured = it.passwordConfigured
                totpConfigured = it.totpConfigured
                status = "Password: " + (if (it.passwordConfigured) "configured" else "not configured") +
                    " • Authenticator: " + (if (it.totpConfigured) "configured" else "not configured") +
                    " • Fingerprint: " + (if (it.biometricConfigured) "configured" else "not configured") +
                    " • Encryption key: " + (if (it.encryptionKeyConfigured) "configured" else "missing")
            }
            .onFailure { error = it.message }
        runCatching { passkeyClient.status() }
            .onSuccess { json ->
                val items = json.optJSONArray("items")
                passkeyIds = List(items?.length() ?: 0) { i -> items!!.getJSONObject(i).optString("id") }
            }
    }

    LaunchedEffect(Unit) {
        while (true) {
            runCatching { client.sessionStatus() }.onSuccess { session ->
                sessionRemainingSeconds = session.optInt("expiresInSeconds", 0).coerceAtLeast(0)
                sessionExpiry = session.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" }
            }
            repeat(15) {
                delay(1000)
                if (sessionRemainingSeconds > 0) sessionRemainingSeconds--
            }
        }
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
                Text("Admin passkeys", style = MaterialTheme.typography.titleLarge)
                Text("Manage passkeys for account sign-in. Passkeys do not replace the separate 30-minute Admin Control Center unlock session; use the admin password/authenticator or enrolled biometric to unlock sensitive admin actions.")
                Button(
                    enabled = !busy,
                    onClick = {
                        run({
                            passkeyClient.createPasskey()
                            val items = passkeyClient.status().optJSONArray("items")
                            passkeyIds = List(items?.length() ?: 0) { i -> items!!.getJSONObject(i).optString("id") }
                        }, "Admin passkey created.")
                    }
                ) { Text("Create admin passkey") }
                passkeyIds.forEach { id ->
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            run({
                                passkeyClient.removePasskey(id)
                                passkeyIds = passkeyIds.filterNot { it == id }
                            }, "Admin passkey disconnected.")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disconnect passkey") }
                }
                if (passkeyIds.isEmpty()) Text("No admin passkey is connected.", style = MaterialTheme.typography.bodySmall)
            }

            item {
                Text("Fingerprint / biometric unlock", style = MaterialTheme.typography.titleLarge)
                Text("Enroll a device-bound biometric key for direct admin unlock. Password + authenticator remains available as the recovery path.")
                Text("Status: " + if (biometricConfigured) "Connected" else "Not connected")
                Button(
                    enabled = !busy && !biometricConfigured,
                    onClick = {
                        val manager = BiometricManager.from(context)
                        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
                            error = "Set up a supported fingerprint or strong biometric on this device first."
                            return@Button
                        }
                        run({
                            biometricClient.register()
                            biometricConfigured = true
                        }, "Admin biometric enrolled.")
                    }
                ) { Text("Enroll fingerprint / biometric") }
                if (biometricConfigured) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            val activity = context as? androidx.fragment.app.FragmentActivity
                            if (activity == null) {
                                error = "Admin biometric requires an Activity context."
                                return@Button
                            }
                            busy = true; error = null
                            scope.launch {
                                runCatching { biometricClient.unlock(activity) }
                                    .onSuccess {
                                        status = "Fingerprint unlock successful."
                                        FirebaseAuth.getInstance().currentUser?.getIdToken(true)
                                    }
                                    .onFailure { error = it.message ?: "Fingerprint unlock failed." }
                                busy = false
                            }
                        }
                    ) { Text("Unlock with fingerprint") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { run({ biometricClient.remove(); biometricConfigured = false }, "Admin biometric disconnected.") }
                    ) { Text("Disconnect fingerprint") }
                }
            }

            item {
                HorizontalDivider()
                Text("Unlock Admin Control Center", style = MaterialTheme.typography.titleLarge)
                Text("After security is enabled, verify the configured factors here to receive a fresh admin step-up session.")
                Text(
                    if (sessionRemainingSeconds > 0) {
                        "Session active • ${sessionRemainingSeconds / 60}m ${sessionRemainingSeconds % 60}s remaining" +
                            (sessionExpiry?.let { " • expires at " + it.take(19).replace('T', ' ') + " UTC" } ?: "")
                    } else "No active admin unlock session.",
                    color = if (sessionRemainingSeconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { password = ""; code = ""; status = "Enter your factors below to unlock again." }) {
                    Text("Unlock again")
                }
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
                    enabled = !busy && (!passwordConfigured || password.isNotEmpty()) && (!totpConfigured || code.length == 6) && (password.isNotEmpty() || code.length == 6),
                    onClick = { run({ client.unlock(password, code); val session = client.sessionStatus(); sessionRemainingSeconds = session.optInt("expiresInSeconds", 1800); sessionExpiry = session.optString("expiresAt").takeIf { it.isNotBlank() } }, "Admin session unlocked.") }
                ) { Text("Unlock") }
            }
            status?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
