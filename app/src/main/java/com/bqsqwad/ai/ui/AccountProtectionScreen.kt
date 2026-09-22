package com.bqsqwad.ai.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.bqsqwad.ai.core.PasskeyClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun AccountProtectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    val passkeyClient = remember { PasskeyClient(context) }
    var providers by remember { mutableStateOf(auth.currentUser?.providerData?.map { it.providerId } ?: emptyList()) }
    var passkeys by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var biometricEnabled by remember {
        mutableStateOf(context.getSharedPreferences("bq_account_protection", Context.MODE_PRIVATE).getBoolean("biometric_enabled", false))
    }

    fun refresh() {
        providers = auth.currentUser?.providerData?.map { it.providerId } ?: emptyList()
        scope.launch {
            runCatching { passkeyClient.status() }
                .onSuccess { json ->
                    val array = json.optJSONArray("items")
                    passkeys = List(array?.length() ?: 0) { i ->
                        val item = array!!.getJSONObject(i)
                        item.optString("id") to item.optString("deviceType", "device")
                    }
                }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun authenticateBiometric(onSuccess: () -> Unit) {
        val activity = context as? androidx.fragment.app.FragmentActivity
        if (activity == null) {
            message = "Biometric authentication is unavailable on this screen."
            return
        }
        val manager = BiometricManager.from(context)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            message = "Set up a supported fingerprint or face unlock in Android settings first."
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                message = errString.toString()
            }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Protect BQ Sqwad AI")
                .setSubtitle("Confirm your device biometric")
                .setNegativeButtonText("Cancel")
                .build()
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Protection") },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Sign-in methods", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Connect more than one way to recover and access the same Firebase account.")
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Email & password", fontWeight = FontWeight.Bold)
                        Text(if ("password" in providers) "Connected" else "Not connected")
                        Text("Use your Firebase email/password sign-in as a recovery method.")
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Google", fontWeight = FontWeight.Bold)
                        Text(if ("google.com" in providers) "Connected" else "Not connected")
                        Button(
                            enabled = !busy && "google.com" !in providers,
                            onClick = {
                                busy = true; message = null
                                scope.launch {
                                    try {
                                        val client = CredentialManager.create(context)
                                        val webClientId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                                        if (webClientId == 0) error("Google client configuration is missing")
                                        val option = GetGoogleIdOption.Builder()
                                            .setServerClientId(context.getString(webClientId))
                                            .setFilterByAuthorizedAccounts(false)
                                            .setAutoSelectEnabled(false)
                                            .build()
                                        val result = client.getCredential(context, GetCredentialRequest(listOf(option)))
                                        val credential = GoogleIdTokenCredential.createFrom(result.credential.data)
                                        val firebaseCredential = GoogleAuthProvider.getCredential(credential.idToken, null)
                                        val user = auth.currentUser ?: error("Sign in is required")
                                        user.linkWithCredential(firebaseCredential).await()
                                        message = "Google connected to this account."
                                        refresh()
                                    } catch (t: Throwable) {
                                        message = t.message ?: "Could not connect Google."
                                    } finally { busy = false }
                                }
                            }
                        ) { Text(if (busy) "Connecting…" else "Connect Google") }
                        OutlinedButton(
                            enabled = !busy && "google.com" in providers && providers.size > 1,
                            onClick = {
                                busy = true; message = null
                                scope.launch {
                                    try {
                                        auth.currentUser?.unlink("google.com")?.await()
                                        message = "Google disconnected."
                                        refresh()
                                    } catch (t: Throwable) { message = t.message ?: "Could not disconnect Google." }
                                    finally { busy = false }
                                }
                            }
                        ) { Text("Disconnect Google") }
                        if ("google.com" in providers && providers.size <= 1) {
                            Text("Keep at least one sign-in method connected before disconnecting Google.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Passkeys", fontWeight = FontWeight.Bold)
                        Text(if (passkeys.isEmpty()) "No passkey connected" else passkeys.size.toString() + " passkey" + if (passkeys.size == 1) "" else "s" + " connected")
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true; message = null
                                scope.launch {
                                    try {
                                        passkeyClient.createPasskey()
                                        message = "Passkey connected. Your device credential provider protects the private key."
                                        refresh()
                                    } catch (t: Throwable) { message = t.message ?: "Could not create passkey." }
                                    finally { busy = false }
                                }
                            }
                        ) { Text("Create passkey") }
                        passkeys.forEach { (id, deviceType) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Passkey • " + deviceType)
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            try {
                                                passkeyClient.removePasskey(id)
                                                message = "Passkey disconnected."
                                                refresh()
                                            } catch (t: Throwable) { message = t.message ?: "Could not disconnect passkey." }
                                            finally { busy = false }
                                        }
                                    }
                                ) { Text("Disconnect") }
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Device biometric", fontWeight = FontWeight.Bold)
                        Text(if (biometricEnabled) "Enabled for this device" else "Not enabled on this device")
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    biometricEnabled = false
                                    context.getSharedPreferences("bq_account_protection", Context.MODE_PRIVATE).edit().putBoolean("biometric_enabled", false).apply()
                                } else {
                                    authenticateBiometric {
                                        biometricEnabled = true
                                        context.getSharedPreferences("bq_account_protection", Context.MODE_PRIVATE).edit().putBoolean("biometric_enabled", true).apply()
                                        message = "Device biometric protection enabled."
                                    }
                                }
                            }
                        )
                        Text("This is local app protection. Passkeys are the account-level phishing-resistant sign-in method.")
                    }
                }
            }
            message?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.primary) } }
        }
    }
}
