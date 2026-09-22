@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bqsqwad.ai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.UUID
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.bqsqwad.ai.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.bqsqwad.ai.core.*
import com.bqsqwad.ai.domain.Workspace
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun BQSqwadApp(modifier: Modifier = Modifier) = AuthGate(modifier)

@Composable
private fun AuthGate(modifier: Modifier) {
    // Chat remains the entry surface. Protected actions require Firebase authentication and server-verified consent.
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { updated -> user = updated.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    if (user == null) MainShell(modifier) else ConsentGate(modifier)
}

@Composable
private fun ConsentGate(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var accepted by remember { mutableStateOf(false) }
    var trainingConsent by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            runCatching { WorkspaceApiClient().consent() }
                .onSuccess {
                    accepted = it.optBoolean("requiredAccepted")
                    trainingConsent = it.optBoolean("trainingConsent")
                }
                .onFailure { error = it.message ?: "Could not verify consent." }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (accepted) {
        MainShell(modifier)
    } else {
        AuthSurface {
            Text("Before you continue", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Please accept the BQ Sqwad AI Terms and Conditions and Privacy Policy. This consent is verified by the server before protected AI features can be used.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                Text("I accept the Terms and Conditions and Privacy Policy.", modifier = Modifier.padding(top = 12.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(checked = trainingConsent, onCheckedChange = { trainingConsent = it })
                Text("Optional: allow non-private training activity to improve the service.", modifier = Modifier.padding(top = 12.dp))
            }
            LegalConsentLinks()
            Button(
                enabled = accepted && !saving,
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        runCatching { WorkspaceApiClient().saveConsent("2026-01", trainingConsent) }
                            .onSuccess {
                                context.getSharedPreferences("bq_consent", Context.MODE_PRIVATE).edit()
                                    .putString("policy_version", "2026-01")
                                    .putLong("required_consent_at", System.currentTimeMillis())
                                    .putBoolean("training_consent", trainingConsent)
                                    .apply()
                            }
                            .onFailure {
                                accepted = false
                                error = it.message ?: "Consent could not be saved."
                            }
                        saving = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saving) "Saving…" else "Continue") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SignInScreen(onOpenSignUp: () -> Unit, onAuthenticated: () -> Unit = {}) {
    val auth = remember { FirebaseAuth.getInstance() }
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showForgot by remember { mutableStateOf(false) }

    AuthSurface {
        Text("BQ Sqwad AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Sign in to continue", color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
        )
        if (email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            Text("Enter a valid email address.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) "Hide" else "Show")
                }
            }
        )

        TextButton(
            onClick = { showForgot = true; notice = null; error = null },
            enabled = !busy
        ) { Text("Forgot password?") }

        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            onClick = {
                busy = true
                error = null
                notice = null
                auth.signInWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener { task ->
                        busy = false
                        if (task.isSuccessful) {
                            onAuthenticated()
                            val signedIn = auth.currentUser
                            if (signedIn?.isEmailVerified == false) {
                                notice = "Signed in. Please verify your email before using protected features."
                                signedIn.sendEmailVerification()
                            }
                        } else {
                            error = friendlyAuthError(task.exception)
                        }
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Signing in…" else "Sign in") }

        HorizontalDivider()
        Text("or", color = MaterialTheme.colorScheme.onSurfaceVariant)

        val scope = rememberCoroutineScope()
        OutlinedButton(
            onClick = {
                busy = true
                error = null
                notice = null
                scope.launch {
                    try {
                        withTimeout(30000) { performGoogleSignIn(context, auth) }
                        notice = "Google sign-in successful."
                        onAuthenticated()
                    } catch (t: Throwable) {
                        error = friendlyGoogleAuthError(t)
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Signing in with Google…" else "Continue with Google") }

        OutlinedButton(
            onClick = {
                busy = true
                error = null
                notice = null
                scope.launch {
                    try {
                        PasskeyClient(context).signInWithPasskey()
                        notice = "Passkey sign-in successful."
                        onAuthenticated()
                    } catch (t: Throwable) {
                        error = t.message ?: "Passkey sign-in failed."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Signing in with passkey…" else "Sign in with passkey") }

        TextButton(onClick = onOpenSignUp, enabled = !busy) {
            Text("Don't have an account? Sign up")
        }

        LegalConsentLinks()

        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (showForgot) {
        AlertDialog(
            onDismissRequest = { showForgot = false },
            title = { Text("Reset password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your account email and Firebase will send a password reset link.")
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        auth.sendPasswordResetEmail(email.trim())
                            .addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    notice = "Password reset email sent. Check your inbox."
                                    showForgot = false
                                } else {
                                    error = friendlyAuthError(task.exception)
                                }
                            }
                    },
                    enabled = email.trim().isNotBlank()
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { showForgot = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SignUpScreen(onBackToSignIn: () -> Unit, onAuthenticated: () -> Unit = {}) {
    val auth = remember { FirebaseAuth.getInstance() }
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var requiredConsent by remember { mutableStateOf(false) }
    var trainingConsent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AuthSurface {
        Text("Create your account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Join BQ Sqwad AI", color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(name, { name = it; error = null }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            email, { email = it; error = null }, label = { Text("Email") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
        )
        OutlinedTextField(
            password, { password = it; error = null }, label = { Text("Password") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "Hide" else "Show") } }
        )
        OutlinedTextField(
            confirmPassword, { confirmPassword = it; error = null }, label = { Text("Confirm password") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = confirmPassword.isNotEmpty() && confirmPassword != password,
            visualTransformation = if (confirmVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { confirmVisible = !confirmVisible }) { Text(if (confirmVisible) "Hide" else "Show") } }
        )

        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = requiredConsent, onCheckedChange = { requiredConsent = it })
            Text("I accept the Terms and Conditions and Privacy Policy.", modifier = Modifier.padding(top = 12.dp))
        }
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = trainingConsent, onCheckedChange = { trainingConsent = it })
            Text(
                "Allow BQ Sqwad AI to use my agent training activity to improve the service. Private details, credentials, API keys, and personal content are never used. Only non-private training activity applies.",
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        val googleScope = rememberCoroutineScope()

        OutlinedButton(
            onClick = {
                if (!requiredConsent) {
                    error = "Please accept the Terms and Conditions and Privacy Policy before continuing with Google."
                    return@OutlinedButton
                }
                busy = true
                error = null
                googleScope.launch {
                    try {
                        val googleUser = performGoogleSignIn(context, auth)
                        val consentPrefs = context.getSharedPreferences("bq_consent", Context.MODE_PRIVATE)
                        consentPrefs.edit()
                            .putString("policy_version", "2026-01")
                            .putLong("required_consent_at", System.currentTimeMillis())
                            .putBoolean("training_consent", trainingConsent)
                            .putString("account_name", googleUser.displayName.orEmpty())
                            .apply()
                        runCatching { WorkspaceApiClient().saveConsent("2026-01", trainingConsent) }
                            .onFailure { error = "Account signed in, but consent could not be saved to the server. Please try again." }
                        onAuthenticated()
                    } catch (t: Throwable) {
                        error = friendlyGoogleAuthError(t)
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Signing in with Google…" else "Continue with Google") }

        Button(
            enabled = !busy && name.isNotBlank() && email.isNotBlank() && password.length >= 6 &&
                password == confirmPassword && requiredConsent,
            onClick = {
                busy = true
                error = null
                auth.createUserWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener { task ->
                        if (!task.isSuccessful) {
                            busy = false
                            error = friendlyAuthError(task.exception)
                            return@addOnCompleteListener
                        }
                        val created = auth.currentUser
                        val consentPrefs = context.getSharedPreferences("bq_consent", Context.MODE_PRIVATE)
                        consentPrefs.edit()
                            .putString("policy_version", "2026-01")
                            .putLong("required_consent_at", System.currentTimeMillis())
                            .putBoolean("training_consent", trainingConsent)
                            .putString("account_name", name.trim())
                            .apply()
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            runCatching { WorkspaceApiClient().saveConsent("2026-01", trainingConsent) }
                        }
                        created?.sendEmailVerification()?.addOnCompleteListener {
                            busy = false
                            onAuthenticated()
                        } ?: run {
                            busy = false
                            onAuthenticated()
                        }
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Creating account…" else "Create account") }

        TextButton(onClick = onBackToSignIn, enabled = !busy) {
            Text("Already have an account? Sign in")
        }

        LegalConsentLinks()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun LegalConsentLinks() {
    val context = LocalContext.current
    var browserUrl by remember { mutableStateOf<String?>(null) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "By continuing, you agree to the BQ Sqwad AI Terms and Conditions and Privacy Policy.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { browserUrl = "https://bqsqwadai.com/terms" }) { Text("Terms and Conditions") }
            Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { browserUrl = "https://bqsqwadai.com/privacy-policy" }) { Text("Privacy Policy") }
        }
    }
    browserUrl?.let { url ->
        LaunchedEffect(url) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        browserUrl = null
    }
}

@Composable
private fun AuthSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = {
            Spacer(Modifier.height(28.dp))
            BqLogo(92.dp)
            content()
        }
    )
}

private fun friendlyAuthError(error: Exception?): String {
    return when (error) {
        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> "The email or password is incorrect."
        is com.google.firebase.auth.FirebaseAuthInvalidUserException -> "No account was found for that email."
        is com.google.firebase.auth.FirebaseAuthUserCollisionException -> "An account already exists with a different sign-in provider. Try the provider originally used."
        is com.google.firebase.FirebaseNetworkException -> "Network connection failed. Check your internet connection and try again."
        else -> error?.message ?: "Authentication failed. Please try again."
    }
}

private suspend fun performGoogleSignIn(context: Context, auth: FirebaseAuth): com.google.firebase.auth.FirebaseUser {
    val credentialManager = CredentialManager.create(context)
    val googleIdOption = GetGoogleIdOption.Builder()
        .setServerClientId("848359744340-etobns9cu6fjrqfo511kln2bape737cg.apps.googleusercontent.com")
        .setFilterByAuthorizedAccounts(false)
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    val result = credentialManager.getCredential(context, request)
    val credential = result.credential
    if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        throw IllegalStateException("Google account credential was not returned.")
    }

    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
    val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
    val authResult = suspendCancellableCoroutine<com.google.firebase.auth.AuthResult> { continuation ->
        val task = auth.signInWithCredential(firebaseCredential)
        task.addOnCompleteListener { completed ->
            if (completed.isSuccessful) {
                completed.result?.let(continuation::resume)
                    ?: continuation.resumeWithException(IllegalStateException("Firebase did not return an authentication result."))
            } else {
                continuation.resumeWithException(
                    completed.exception ?: IllegalStateException("Firebase Google authentication failed.")
                )
            }
        }
        continuation.invokeOnCancellation { /* Firebase Task cancellation is not supported; completion is ignored after cancellation. */ }
    }
    return authResult.user ?: throw IllegalStateException("Firebase did not return a signed-in user.")
}

private fun friendlyGoogleAuthError(error: Throwable): String {
    return when (error) {
        is GetCredentialException -> "Google sign-in was cancelled or could not be completed. Please try again."
        is TimeoutCancellationException -> "Google sign-in was cancelled or could not be completed. Please try again."
        is com.google.firebase.auth.FirebaseAuthUserCollisionException -> "Google sign-in was cancelled or could not be completed. Please try again."
        is com.google.firebase.FirebaseNetworkException -> "Google sign-in was cancelled or could not be completed. Please try again."
        else -> "Google sign-in was cancelled or could not be completed. Please try again."
    }
}

@Composable
private fun MainShell(modifier: Modifier) {
    val email = FirebaseAuth.getInstance().currentUser?.email.orEmpty()
    var workspace by remember { mutableStateOf<Workspace?>(null) }
    var hub by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var accountProtection by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    when {
        hub -> FeatureHub({ hub = false }, { plans = true }, { history = true }) { workspace = it; hub = false }
        plans -> ProductCatalogScreen { plans = false }
        settings -> SettingsScreen(onBack = { settings = false }, onOpenAccountProtection = { accountProtection = true })
        accountProtection -> AccountProtectionScreen { accountProtection = false }
        history -> HistoryScreen { history = false }
        workspace != null -> WorkspaceScreen(workspace!!, email) { workspace = null }
        else -> MainChatScreen(modifier, { hub = true }, { settings = true })
    }
}

private data class ChatMessage(val mine: Boolean, val text: String, val id: String = UUID.randomUUID().toString(), val prompt: String? = null)

@Composable
private fun MainChatScreen(modifier: Modifier, openHub: () -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("bq_ui", Context.MODE_PRIVATE) }
    var wallpaper by remember { mutableStateOf(prefs.getInt("wallpaper", 0)) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAuth by remember { mutableStateOf(false) }
    var showSignUp by remember { mutableStateOf(false) }
    var feedbackTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var feedbackReason by remember { mutableStateOf("inaccurate") }
    var feedbackComment by remember { mutableStateOf("") }
    var micError by remember { mutableStateOf<String?>(null) }
    var attachments by remember { mutableStateOf<List<PreparedChatAttachment>>(emptyList()) }
    var historyId by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    var streamJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (spoken.isNotBlank()) message = if (message.isBlank()) spoken else "$message $spoken"
        else micError = "No speech was detected. Try again."
    }

    fun sendAuthenticatedMessage(text: String) {
        if ((text.isBlank() && attachments.isEmpty()) || busy) return
        val selectedAttachments = attachments
        attachments = emptyList()
        message = ""
        val attachmentNames = selectedAttachments.joinToString(", ") { it.name }
        val displayText = if (attachmentNames.isBlank()) text else (text.ifBlank { "Please analyze these attachments." } + "\n📎 " + attachmentNames)
        val textParts = selectedAttachments.mapNotNull { a -> a.text?.let { "Attachment: " + a.name + "\n" + it } }
        val imageParts = selectedAttachments.filter { it.imageBase64 != null }
        if (imageParts.size > 1) {
            error = "Please attach one image at a time. Multiple text attachments are supported."
            attachments = selectedAttachments
            return
        }
        val actualPrompt = listOf(text.ifBlank { "Please analyze the attached content." }, textParts.joinToString("\n\n")).filter { it.isNotBlank() }.joinToString("\n\n")
        messages.add(ChatMessage(true, displayText))
        val aiIndex = messages.size
        messages.add(ChatMessage(false, "", prompt = actualPrompt))
        busy = true
        error = null
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                val reply = if (imageParts.isNotEmpty()) {
                    val image = imageParts.first()
                    ChatApiClient().analyzeCameraFrame(actualPrompt, image.imageBase64!!, image.mimeType)
                } else {
                    ChatApiClient().streamMessage(actualPrompt) { delta ->
                        scope.launch(Dispatchers.Main) {
                            val current = messages.getOrNull(aiIndex)
                            if (current != null) messages[aiIndex] = current.copy(text = current.text + delta)
                        }
                    }
                }
                scope.launch(Dispatchers.Main) {
                    val current = messages.getOrNull(aiIndex)
                    if (current != null) messages[aiIndex] = current.copy(text = reply.text)
                    busy = false
                    streamJob = null
                    val snapshot = messages.map { HistoryMessage(it.mine, it.text, it.id, it.prompt) }
                    runCatching {
                        historyId = HistoryApiClient().save(historyId, text.ifBlank { attachmentNames.ifBlank { "New chat" } }, snapshot)
                    }.onFailure { error = "The response was generated, but chat history could not be saved." }
                }
            } catch (t: Throwable) {
                scope.launch(Dispatchers.Main) {
                    val current = messages.getOrNull(aiIndex)
                    if (current != null && current.text.isBlank()) messages.removeAt(aiIndex)
                    attachments = selectedAttachments
                    if (t !is kotlinx.coroutines.CancellationException) error = t.message ?: "AI request failed"
                    busy = false
                    streamJob = null
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val prepared = mutableListOf<PreparedChatAttachment>()
                var failure: String? = null
                for (uri in selected) {
                    runCatching { ChatAttachmentReader.read(context, uri) }
                        .onSuccess { prepared += it }
                        .onFailure { failure = it.message ?: "Could not read attachment." }
                }
                scope.launch(Dispatchers.Main) {
                    if (failure != null) error = failure
                    attachments = prepared
                }
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        WallpaperBackground(wallpaper)
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(onClick = openHub) {
                    Icon(Icons.Default.Menu, contentDescription = "Open BQ Sqwad AI menu")
                }
                Column(Modifier.weight(1f)) {
                    BqLogo(46.dp)
                    Spacer(Modifier.height(2.dp))
                    Text("The AI that grows with you", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { wallpaper = (wallpaper + 1) % 5; prefs.edit().putInt("wallpaper", wallpaper).apply() }) {
                    Icon(Icons.Default.Wallpaper, contentDescription = "Change chat background")
                }
                IconButton(onClick = openSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "General",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Ready for your next request", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            micError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { m ->
                    Column(Modifier.fillMaxWidth(if (m.mine) .88f else 1f).align(if (m.mine) Alignment.End else Alignment.Start)) {
                        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) { Text(m.text, Modifier.padding(14.dp)) }
                        if (!m.mine) Row {
                            TextButton({ clipboard.setText(AnnotatedString(m.text)) }) { Text("Copy") }
                            TextButton({ shareText(context, m.text) }) { Text("Share") }
                            TextButton({ speakTextWhenReady(context, m.text) { error = it } }) { Text("Read aloud") }
                            TextButton({ message = m.prompt ?: m.text }) { Text("Resend") }
                            TextButton({
                                scope.launch {
                                    runCatching { WorkspaceApiClient().reportMessage(m.text, "report", m.id, "report") }
                                        .onSuccess { error = "Report submitted for review." }
                                        .onFailure { error = it.message ?: "Report failed." }
                                }
                            }) { Text("Report") }
                            TextButton({
                                scope.launch {
                                    runCatching { WorkspaceApiClient().reportMessage(m.text, "response", m.id, "up") }
                                        .onSuccess { error = "Thanks for the feedback." }
                                        .onFailure { error = it.message ?: "Feedback failed." }
                                }
                            }) { Text("👍") }
                            TextButton({ feedbackTarget = m }) { Text("👎") }
                            if (m.prompt != null) {
                                TextButton({
                                    message = m.prompt
                                    val text = m.prompt
                                    if (FirebaseAuth.getInstance().currentUser != null && !busy) {
                                        sendAuthenticatedMessage(text)
                                    }
                                }) { Text("Regenerate") }
                            }
                        }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
            if (attachments.isNotEmpty()) {
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(attachments, key = { it.name + it.mimeType }) { attachment ->
                        InputChip(
                            selected = true,
                            onClick = { attachments = attachments.filterNot { it === attachment } },
                            label = { Text(attachment.name, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, "Remove attachment") }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = openHub) { Icon(Icons.Default.Add, "AI workspaces") }
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, "Files") }
                OutlinedTextField(
                    message,
                    { value ->
                        micError = null
                        message = value
                    },
                    placeholder = { Text("Message BQ Sqwad AI") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5
                )
                IconButton(onClick = {
                    micError = null
                    speechLauncher.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your message")
                    })
                }, enabled = !busy) { Icon(Icons.Default.Mic, contentDescription = "Voice input") }
                if (busy) {
                    IconButton(onClick = { streamJob?.cancel() }) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop generating")
                    }
                } else {
                    IconButton(enabled = message.isNotBlank() || attachments.isNotEmpty(), onClick = {
                        val text = message.trim()
                        if (FirebaseAuth.getInstance().currentUser == null) {
                            showAuth = true
                            return@IconButton
                        }
                        sendAuthenticatedMessage(text)
                    }) { Icon(Icons.Default.Send, "Send") }
                }
            }
        }
        FloatingLogo(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(62.dp))
        feedbackTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { feedbackTarget = null },
                title = { Text("What was wrong with this response?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("inaccurate", "unhelpful", "unsafe", "other").forEach { reason ->
                            FilterChip(
                                selected = feedbackReason == reason,
                                onClick = { feedbackReason = reason },
                                label = { Text(reason.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                        OutlinedTextField(
                            value = feedbackComment,
                            onValueChange = { feedbackComment = it },
                            label = { Text("Optional comment") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                WorkspaceApiClient().reportMessage(
                                    target.text,
                                    "response",
                                    target.id,
                                    "down",
                                    feedbackReason,
                                    feedbackComment.trim().ifBlank { null }
                                )
                            }.onSuccess {
                                error = "Thanks for the feedback."
                            }.onFailure {
                                error = it.message ?: "Feedback failed."
                            }
                            feedbackTarget = null
                            feedbackComment = ""
                        }
                    }) { Text("Send") }
                },
                dismissButton = { TextButton(onClick = { feedbackTarget = null }) { Text("Cancel") } }
            )
        }
        if (showAuth) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f)
            ) {
                if (showSignUp) {
                    SignUpScreen(
                        onBackToSignIn = { showSignUp = false },
                        onAuthenticated = { showAuth = false; sendAuthenticatedMessage(message.trim()) }
                    )
                } else {
                    SignInScreen(
                        onOpenSignUp = { showSignUp = true },
                        onAuthenticated = {
                            showAuth = false
                            val pending = message.trim()
                            if (pending.isNotBlank()) sendAuthenticatedMessage(pending)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureHub(onBack: () -> Unit, onPlans: () -> Unit, onHistory: () -> Unit, onWorkspace: (Workspace) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("BQ Sqwad AI Workspaces") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = {
    IconButton(onClick = onHistory) { Icon(Icons.Default.History, "Chat history") }
    IconButton(onClick = onPlans) { Icon(Icons.Default.CreditCard, "Plans") }
}) }) { p ->
        LazyColumn(Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Core workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }            items(Workspace.values().filter { it != Workspace.CHAT }) { w -> FeatureCard(w.title, w.description) { onWorkspace(w) } }
            item { Text("Live product catalog", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { Text("Plan features, limits, and availability are loaded from the backend catalog. Product screens do not hard-code commercial entitlements.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text("Admin configuration controls what is available without requiring an app release.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun WorkspaceScreen(w: Workspace, email: String, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(if (w == Workspace.BUSINESS) "Business AI" else w.title) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { p ->
        Column(Modifier.padding(p).padding(16.dp).fillMaxSize()) {
            when (w) {
                Workspace.SEARCH -> SearchWorkspace()
                Workspace.CAMERA -> CameraWorkspace()
                Workspace.VOICE -> VoiceWorkspace()
                Workspace.PERSONAL -> AiTaskWorkspace("Personal Intelligence", "Permission-based memory, goals, AI birthday, organization and cloud sync.", "personal_intelligence", true)
                Workspace.LIFE -> AiTaskWorkspace("Life Matters", "Planning, reminders, calendar assistance, routines and practical guidance.", "life_matters", false)
                Workspace.COMPANION -> CompanionWorkspace()
                Workspace.LEARNING -> AiTaskWorkspace("Learning Studio", "Subjects, tutoring, homework, revision and exam preparation.", "learning_studio", false)
                Workspace.BUSINESS -> BusinessWorkspaceScreen()
                Workspace.ORGANIZATION_STUDIO -> OrganizationStudioWorkspace()
                Workspace.ADMIN -> AdminControlCenter()
                Workspace.CHAT -> AiTaskWorkspace("Main Chat", "General AI conversation.")
            }
        }
    }
}

@Composable
private fun AiTaskWorkspace(
    title: String,
    description: String,
    capability: String = "chat",
    showMemory: Boolean = false
) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf("") }
    var memoryEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showMemory) {
        if (showMemory) {
            runCatching { WorkspaceApiClient().memory() }.onSuccess { body ->
                memoryEnabled = body.optBoolean("enabled", false)
                memoryText = body.optJSONArray("items")?.let { items ->
                    (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() } }
                        .takeLast(10).joinToString("\n")
                }.orEmpty()
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            prompt,
            { prompt = it; error = null },
            label = { Text("What do you want to do?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = prompt.isNotBlank() && !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        result = ChatApiClient().sendMessage(
                            "Workspace: " + title + "\nUser request: " + prompt,
                            capability
                        ).text
                    } catch (t: Throwable) {
                        error = t.message ?: "Request failed"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Working…" else "Run with BQ Sqwad AI") }

            if (showMemory) {
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { WorkspaceApiClient().setMemoryEnabled(!memoryEnabled) }
                            .onSuccess { memoryEnabled = it.optBoolean("enabled", !memoryEnabled) }
                            .onFailure { error = it.message ?: "Memory setting could not be updated." }
                    }
                }) { Text(if (memoryEnabled) "Memory on" else "Memory off") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (showMemory && memoryText.isNotBlank()) {
            SurfaceCard("Saved memory", memoryText)
        }
        if (result.isNotBlank()) SurfaceCard("Result", result)
        SurfaceCard("Live catalog", "Availability, limits, access and feature configuration are controlled by the admin backend.")
    }
}

@Composable
private fun SearchWorkspace() {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var deep by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<SearchSource>() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Web Search", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search") })
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(deep, { deep = it }); Text("Deep Research") }
        Button(enabled = query.isNotBlank(), onClick = {
            error = null
            scope.launch { try { results.clear(); results.addAll(WorkspaceApiClient().search(query.trim(), deep).sources) } catch (t: Throwable) { error = t.message ?: "Search failed" } }
        }) { Text("Search") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { r ->
                Card(Modifier.fillMaxWidth().clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }
                        .onFailure { error = "No browser is available to open this result." }
                }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        r.snippet?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(r.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var active by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var permissionExplanation by remember { mutableStateOf(false) }
    var lastAnalysisAt by remember { mutableLongStateOf(0L) }
    var prompt by remember { mutableStateOf("What do you see in this camera view?") }
    var answer by remember { mutableStateOf("") }
    var lens by remember { mutableStateOf(android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) }
    var screenActive by remember { mutableStateOf(false) }
    var screenAnalyzing by remember { mutableStateOf(false) }
    var screenAnswer by remember { mutableStateOf("") }
    var screenPrompt by remember { mutableStateOf("What is happening on my screen?") }
    var screenLastAnalysisAt by remember { mutableLongStateOf(0L) }
    var liveServiceActive by remember { mutableStateOf(false) }
    var liveServiceMode by remember { mutableStateOf("") }
    var liveAnswer by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) {
            context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens))
            active = true
        }
    }
    val screenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            screenAnswer = "Screen sharing was cancelled. Nothing was captured."
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val metrics = context.resources.displayMetrics
            context.startForegroundService(
                Intent(context, LiveMediaForegroundService::class.java)
                    .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_SCREEN)
                    .putExtra(LiveMediaForegroundService.EXTRA_PROJECTION_DATA, data)
                    .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, screenPrompt)
                    .putExtra(LiveMediaForegroundService.EXTRA_WIDTH, metrics.widthPixels)
                    .putExtra(LiveMediaForegroundService.EXTRA_HEIGHT, metrics.heightPixels)
                    .putExtra(LiveMediaForegroundService.EXTRA_DENSITY, metrics.densityDpi)
            )
            screenActive = true
            screenAnswer = "Android screen sharing is active. BQ Sqwad AI is receiving screen frames through the foreground service."
        }.onFailure { t ->
            context.stopService(Intent(context, LiveMediaForegroundService::class.java))
            screenActive = false
            screenAnswer = t.message ?: "Could not start screen sharing."
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val prefs = context.getSharedPreferences("bq_live_media", Context.MODE_PRIVATE)
            liveServiceActive = prefs.getBoolean("active", false)
            liveServiceMode = prefs.getString("mode", "") ?: ""
            liveAnswer = prefs.getString("answer", "") ?: ""
            if (liveServiceMode == LiveMediaForegroundService.MODE_SCREEN) {
                screenActive = liveServiceActive
                if (liveAnswer.isNotBlank()) screenAnswer = liveAnswer
            } else if (liveServiceMode == LiveMediaForegroundService.MODE_CAMERA) {
                active = liveServiceActive
                if (liveAnswer.isNotBlank()) answer = liveAnswer
            }
            kotlinx.coroutines.delay(750)
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI Live Camera", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Live camera viewer with secure frame analysis. Camera access stays visible while the session is active.")
        if (!granted) {
            Button(onClick = { permissionExplanation = true }) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Enable camera")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!active) {
                        context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                            .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                            .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                            .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens))
                        active = true
                    } else {
                        context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                        active = false
                    }
                }) { Text(if (active) "End live camera" else "Start live camera") }
                OutlinedButton(onClick = {
                    lens = if (lens == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                    if (active) {
                        context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                        context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                            .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                            .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                            .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens))
                    }
                }, enabled = active) { Text("Flip") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (screenActive) {
                        context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                        screenActive = false
                    } else {
                        screenLauncher.launch(
                            context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                                .createScreenCaptureIntent()
                        )
                    }
                }, enabled = !active || screenActive) {
                    Text(if (screenActive) "Stop screen viewer" else "Share screen with AI")
                }
            }
        }
        if (screenActive) {
            SurfaceCard("Screen viewer", "● Screen sharing active • Android permission was granted for this capture session.")
            OutlinedTextField(
                screenPrompt,
                { screenPrompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ask about the screen") },
                maxLines = 2
            )
            if (screenActive) {
                TextButton(onClick = {
                    val metrics = context.resources.displayMetrics
                    context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                        .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_SCREEN)
                        .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, screenPrompt)
                        .putExtra(LiveMediaForegroundService.EXTRA_WIDTH, metrics.widthPixels)
                        .putExtra(LiveMediaForegroundService.EXTRA_HEIGHT, metrics.heightPixels)
                        .putExtra(LiveMediaForegroundService.EXTRA_DENSITY, metrics.densityDpi))
                }) { Text("Apply screen question") }
            }
            if (screenAnalyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (screenAnswer.isNotBlank()) SurfaceCard("BQ Sqwad AI", screenAnswer)
        } else if (granted && active) {
            Surface(
                Modifier.fillMaxWidth().height(280.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                    Text("● Live camera foreground session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("BQ Sqwad AI is capturing camera frames through Android's visible foreground service. The session can continue while you leave the app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (liveServiceActive) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }
            SurfaceCard("Live indicator", "● Camera active • Android foreground service is running and BQ Sqwad AI is receiving sampled camera frames.")
            OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ask about the camera view") }, maxLines = 2)
            if (active) {
                TextButton(onClick = {
                    context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                        .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                        .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                        .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens))
                }) { Text("Apply camera question") }
            }
            if (analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (answer.isNotBlank()) SurfaceCard("BQ Sqwad AI", answer)
        } else {
            SurfaceCard("Camera status", "Camera preview is stopped. Start the live session after granting permission.")
        }
    }
    if (permissionExplanation) {
        AlertDialog(
            onDismissRequest = { permissionExplanation = false },
            title = { Text("Camera access") },
            text = { Text("BQ Sqwad AI needs camera access only while you use Live Camera. A visible Android live-session indicator stays on while the camera is active.") },
            confirmButton = {
                TextButton(onClick = { permissionExplanation = false; launcher.launch(Manifest.permission.CAMERA) }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { permissionExplanation = false }) { Text("Not now") } }
        )
    }
}

@Composable
private fun VoiceWorkspace() {
    val context = LocalContext.current
    var active by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("stopped") }
    var permissionExplanation by remember { mutableStateOf(false) }

    fun startVoice() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionExplanation = true
            return
        }
        context.startForegroundService(
            Intent(context, LiveMediaForegroundService::class.java)
                .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_VOICE)
        )
        active = true
        state = "connecting"
    }

    fun stopVoice() {
        context.stopService(Intent(context, LiveMediaForegroundService::class.java))
        active = false
        state = "stopped"
    }

    DisposableEffect(Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                val prefs = context.getSharedPreferences("bq_live_media", Context.MODE_PRIVATE)
                if (prefs.getString("mode", "") == LiveMediaForegroundService.MODE_VOICE) {
                    active = prefs.getBoolean("active", false)
                    state = prefs.getString("answer", "stopped") ?: "stopped"
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poll)
        onDispose {
            handler.removeCallbacksAndMessages(null)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoice() }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Surface(
            modifier = Modifier.size(190.dp),
            shape = CircleShape,
            tonalElevation = 12.dp,
            shadowElevation = 16.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Live voice",
                    modifier = Modifier.size(72.dp)
                )
            }
        }

        Text(
            if (active) state.replaceFirstChar { it.uppercase() } else "Voice off",
            style = MaterialTheme.typography.titleMedium
        )

        Button(
            onClick = { if (active) stopVoice() else startVoice() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(if (active) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (active) "End conversation" else "Start conversation")
        }

        if (active) {
            Text(
                "Speak naturally. The AI listens and responds with audio; speaking while it responds interrupts the response.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (permissionExplanation) {
        AlertDialog(
            onDismissRequest = { permissionExplanation = false },
            title = { Text("Microphone access") },
            text = { Text("Microphone access is required for the live voice conversation. Android shows a microphone indicator while the session is active.") },
            confirmButton = {
                TextButton(onClick = {
                    permissionExplanation = false
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { permissionExplanation = false }) { Text("Not now") }
            }
        )
    }
}

@Composable
private fun PlansSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    var status by remember { mutableStateOf("Connecting to Google Play…") }
    var billing by remember { mutableStateOf<PlayBillingClient?>(null) }
    var products by remember { mutableStateOf(emptyList<com.android.billingclient.api.ProductDetails>()) }

    DisposableEffect(activity) {
        if (activity == null) {
            status = "Google Play billing is unavailable in this context."
            onDispose { }
        } else {
            val manager = PlayBillingClient(activity) { state ->
                status = when (state.status) {
                    "success" -> state.message
                    "pending" -> state.message
                    "verifying" -> state.message
                    else -> state.message
                }
            }
            billing = manager
            manager.start()
            status = "Loading Play products…"
            onDispose {
                manager.close()
                billing = null
            }
        }
    }

    LaunchedEffect(billing) {
        repeat(12) {
            kotlinx.coroutines.delay(500)
            val current = billing
            if (current?.isReady() == true) {
                products = current.availableProducts()
                status = if (products.isEmpty()) {
                    "No approved Play products are configured yet."
                } else {
                    "Prices are supplied by Google Play."
                }
                if (products.isNotEmpty()) return@LaunchedEffect
            }
        }
        if (products.isEmpty()) status = "Google Play is unavailable. Retry when connected."
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Credits & Plans", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Google Play is the only native purchase path. The app never hard-codes a purchase price.")
            }
            item { Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                OutlinedButton(
                    onClick = {
                        billing?.restorePurchases()
                        status = "Restoring purchases…"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = billing?.isReady() == true
                ) { Text("Restore purchases") }
            }
            if (products.isEmpty()) {
                item { SurfaceCard("Price unavailable", "Approved Play product mappings must be configured before native purchases can be offered.") }
            }
            items(products, key = { it.productId }) { product ->
                val price = product.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
                    ?: product.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(product.name, fontWeight = FontWeight.Bold)
                        Text(product.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(price ?: "Price unavailable", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = {
                                val result = billing?.launchPurchase(product.productId)
                                if (result == null) status = "This product is not currently available."
                                else if (result.responseCode != com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
                                    status = result.debugMessage.ifBlank { "Purchase could not be started." }
                                } else {
                                    status = "Purchase started. Access unlocks only after server verification."
                                }
                            },
                            enabled = price != null && billing?.isReady() == true,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Purchase with Google Play") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onOpenAccountProtection: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "system") ?: "system") }
    var accent by remember { mutableStateOf(prefs.getString("accent_palette", "signature") ?: "signature") }
    var trainingConsent by remember { mutableStateOf(prefs.getBoolean("training_consent", false)) }
    var consentStatus by remember { mutableStateOf<String?>(null) }
    val modes = listOf("system", "dark", "light", "amoled")
    val accents = listOf("signature", "blue", "indigo", "violet", "teal", "emerald", "rose", "amber", "graphite")

    fun saveTheme(value: String) {
        themeMode = value
        prefs.edit().putString("theme_mode", value).apply()
        (context as? android.app.Activity)?.recreate()
    }
    fun saveAccent(value: String) {
        accent = value
        prefs.edit().putString("accent_palette", value).apply()
        (context as? android.app.Activity)?.recreate()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings & Privacy") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { p ->
        LazyColumn(Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { SurfaceCard("Signed-in account", FirebaseAuth.getInstance().currentUser?.email ?: "Signed in") }
            item { FeatureCard("Account protection", "Connect or disconnect Google, passkeys and this device biometric for account protection.") { onOpenAccountProtection() } }

            item { Text("Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                SurfaceCard("Theme mode", "Current: $themeMode. Choose System, Dark, Light or AMOLED.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modes.forEach { mode ->
                        FilterChip(selected = themeMode == mode, onClick = { saveTheme(mode) }, label = { Text(mode.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
            item {
                SurfaceCard("Accent palette", "Current: $accent. Changes apply to native surfaces and controls.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(accents) { name ->
                        FilterChip(selected = accent == name, onClick = { saveAccent(name) }, label = { Text(name.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }

            item { Text("Security & privacy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { FeatureCard("Android permissions", "Review camera, microphone and other permissions in Android settings.") { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) } }
            item { FeatureCard("Provider secrets", "AI provider credentials remain server-side and are never placed in the APK.") }
            item {
                SurfaceCard("AI training consent", if (trainingConsent) "Optional training consent is on." else "Optional training consent is off.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = trainingConsent, onCheckedChange = { enabled ->
                        trainingConsent = enabled
                        prefs.edit().putBoolean("training_consent", enabled).apply()
                        consentStatus = "Saving consent…"
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            runCatching { WorkspaceApiClient().updateTrainingConsent(enabled) }
                                .onSuccess { consentStatus = "Training consent updated." }
                                .onFailure { consentStatus = "Could not update consent. Try again." }
                        }
                    })
                    Text("Allow non-private training activity to be used to improve the service.")
                }
                consentStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { FeatureCard("Terms and Conditions", "Open the current BQ Sqwad AI terms in your browser.") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bqsqwadai.com/terms"))) } }
            item { FeatureCard("Privacy Policy", "Open the current BQ Sqwad AI privacy policy in your browser.") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bqsqwadai.com/privacy-policy"))) } }
            item { FeatureCard("Sign out", "End the current authenticated session.") { FirebaseAuth.getInstance().signOut(); onBack() } }
            item { Text("BQ Sqwad AI • © 2026 BQ Sqwad Technologies", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun FeatureCard(title: String, body: String, onClick: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)) { Column(Modifier.padding(14.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable fun SurfaceCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable private fun AccessDenied() { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("Admin access required", fontWeight = FontWeight.Bold) } }

private fun shareText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share"))
}

private fun speakTextWhenReady(context: Context, text: String, onError: (String) -> Unit) {
    if (text.isBlank()) return
    lateinit var engine: TextToSpeech
    engine = TextToSpeech(context) { status ->
        if (status != TextToSpeech.SUCCESS) {
            onError("Text-to-speech could not initialize.")
            engine.shutdown()
            return@TextToSpeech
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bq_read_aloud")
        if (result == TextToSpeech.ERROR) {
            onError("Text-to-speech could not start.")
            engine.shutdown()
        }
    }
    engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { engine.shutdown() }
        override fun onError(utteranceId: String?) { onError("Text-to-speech playback failed."); engine.shutdown() }
    })
}

@Composable private fun WallpaperBackground(index: Int) {
    val brush = when (index) {
        1 -> Brush.linearGradient(listOf(Color(0xFF071A2B), Color(0xFF123D5A)))
        2 -> Brush.linearGradient(listOf(Color(0xFF120B2B), Color(0xFF263A73)))
        3 -> Brush.linearGradient(listOf(Color(0xFF061E1A), Color(0xFF164B3D)))
        4 -> Brush.linearGradient(listOf(Color(0xFF1D1022), Color(0xFF4C244E)))
        else -> Brush.linearGradient(listOf(Color(0xFF05070D), Color(0xFF10243A)))
    }
    Box(Modifier.fillMaxSize().background(brush))
}

@Composable private fun FloatingLogo(modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF5FB7FF), Color(0xFF0A5DB8), Color.Transparent)))) { LogoBubble(Modifier.fillMaxSize().padding(8.dp)) }
}

@Composable
private fun LogoBubble(modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Image(
            painter = painterResource(R.drawable.bq_sqwad_logo),
            contentDescription = "BQ Sqwad AI logo",
            modifier = Modifier.fillMaxSize().padding(6.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    }
}