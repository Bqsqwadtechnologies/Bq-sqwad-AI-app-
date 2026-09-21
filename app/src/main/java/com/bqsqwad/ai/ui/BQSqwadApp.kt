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
import android.webkit.WebView
import android.webkit.WebViewClient
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
    // Chat is always the entry surface. Authentication is requested only when a protected action is attempted.
    MainShell(modifier)
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
        AlertDialog(
            onDismissRequest = { browserUrl = null },
            title = { Text("BQ Sqwad AI") },
            text = {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(520.dp),
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = true
                            loadUrl(url)
                        }
                    }
                )
            },
            confirmButton = { TextButton(onClick = { browserUrl = null }) { Text("Close") } }
        )
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
    when {
        hub -> FeatureHub({ hub = false }, { plans = true }) { workspace = it; hub = false }
        plans -> PlansSheet { plans = false }
        settings -> SettingsScreen { settings = false }
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
    val messages = remember { mutableStateListOf<ChatMessage>() }

    var streamJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun sendAuthenticatedMessage(text: String) {
        if (text.isBlank() || busy) return
        message = ""
        messages.add(ChatMessage(true, text))
        val aiIndex = messages.size
        messages.add(ChatMessage(false, "", prompt = text))
        busy = true
        error = null
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                ChatApiClient().streamMessage(text) { delta ->
                    launch(Dispatchers.Main) {
                        val current = messages.getOrNull(aiIndex)
                        if (current != null) messages[aiIndex] = current.copy(text = current.text + delta)
                    }
                }
                launch(Dispatchers.Main) { busy = false; streamJob = null }
            } catch (t: Throwable) {
                launch(Dispatchers.Main) {
                    val current = messages.getOrNull(aiIndex)
                    if (current != null && current.text.isBlank()) messages.removeAt(aiIndex)
                    if (t !is kotlinx.coroutines.CancellationException) error = t.message ?: "AI request failed"
                    busy = false
                    streamJob = null
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isNotEmpty()) message = "Selected " + selected.size + " file(s). File upload/analysis is awaiting the configured secure file pipeline."
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
                BqLogo(46.dp)
                Column(Modifier.weight(1f)) {
                    Text("BQ Sqwad AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = openHub) { Icon(Icons.Default.Add, "AI workspaces") }
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, "Files") }
                OutlinedTextField(message, { message = it }, placeholder = { Text("Message BQ Sqwad AI") }, modifier = Modifier.weight(1f), maxLines = 5)
                if (busy) {
                    IconButton(onClick = { streamJob?.cancel() }) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop generating")
                    }
                } else {
                    IconButton(enabled = message.isNotBlank(), onClick = {
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
                        onAuthenticated = { showAuth = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureHub(onBack: () -> Unit, onPlans: () -> Unit, onWorkspace: (Workspace) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("BQ Sqwad AI Workspaces") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { IconButton(onPlans) { Icon(Icons.Default.CreditCard, "Plans") } }) }) { p ->
        LazyColumn(Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Core workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(Workspace.values().filter { it != Workspace.CHAT && it != Workspace.ADMIN }) { w -> FeatureCard(w.title, w.description) { onWorkspace(w) } }
            item { Text("Extended parity workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(NativeFeatureCatalog.workspaces.filter { name -> Workspace.values().none { it.title == name } }) { name ->
                FeatureCard(name, "Native feature surface from the former BQ Sqwad AI product.") { onWorkspace(Workspace.PERSONAL) }
            }
            item { Text("Capability catalog", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(NativeFeatureCatalog.features) { f -> FeatureCard(f.title, f.description) }
        }
    }
}

@Composable
private fun WorkspaceScreen(w: Workspace, email: String, onBack: () -> Unit) {
    val admin = email.equals("isaacoshiomole0@gmail.com", true)
    Scaffold(topBar = { TopAppBar(title = { Text(if (w == Workspace.BUSINESS) "Business AI" else w.title) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { p ->
        Column(Modifier.padding(p).padding(16.dp).fillMaxSize()) {
            when (w) {
                Workspace.SEARCH -> SearchWorkspace()
                Workspace.CAMERA -> CameraWorkspace()
                Workspace.VOICE -> VoiceWorkspace()
                Workspace.PERSONAL -> AiTaskWorkspace("Personal Intelligence", "Permission-based memory, goals, AI birthday, organization and cloud sync.")
                Workspace.LIFE -> AiTaskWorkspace("Life Matters", "Planning, reminders, calendar assistance, routines and practical guidance.")
                Workspace.LEARNING -> AiTaskWorkspace("Learning Studio", "Subjects, tutoring, homework, revision and exam preparation.")
                Workspace.BUSINESS -> AiTaskWorkspace("Business AI", "AI employees, agents, knowledge, connections, workflows and analytics.")
                Workspace.ORGANIZATION_STUDIO -> OrganizationStudioWorkspace()
                Workspace.ADMIN -> if (admin) AdminControlCenter() else AccessDenied()
                Workspace.CHAT -> AiTaskWorkspace("Main Chat", "General AI conversation.")
            }
        }
    }
}

@Composable
private fun AiTaskWorkspace(title: String, description: String) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(prompt, { prompt = it }, label = { Text("What do you want to do?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Button(enabled = prompt.isNotBlank() && !busy, onClick = {
            busy = true
            scope.launch {
                try { result = ChatApiClient().sendMessage("Workspace: " + title + "\nUser request: " + prompt).text }
                catch (t: Throwable) { result = t.message ?: "Request failed" }
                finally { busy = false }
            }
        }) { Text(if (busy) "Working…" else "Run with BQ Sqwad AI") }
        if (result.isNotBlank()) SurfaceCard("Result", result)
        SurfaceCard("Catalog capabilities", NativeFeatureCatalog.features.filter { it.workspace == title }.joinToString(" • ") { it.title }.ifBlank { "AI planning and conversation tools" })
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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(results) { r -> SurfaceCard(r.title, r.snippet ?: r.url) } }
    }
}

@Composable
private fun CameraWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var active by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("What do you see in this camera view?") }
    var answer by remember { mutableStateOf("") }
    var lens by remember { mutableStateOf(android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) {
            context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java).putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA))
            active = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { context.stopService(Intent(context, LiveMediaForegroundService::class.java)) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI Live Camera", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Live camera viewer with secure frame analysis. Camera access stays visible while the session is active.")
        if (!granted) {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Enable camera")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!active) context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java).putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA))
                    else context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                    active = !active
                }) { Text(if (active) "End live camera" else "Start live camera") }
                OutlinedButton(onClick = {
                    lens = if (lens == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                }, enabled = active) { Text("Flip") }
            }
        }
        if (granted && active) {
            NativeCameraPreview(
                Modifier.fillMaxWidth().height(280.dp),
                lensFacing = lens,
                onFrame = { frame ->
                    if (!analyzing) {
                        analyzing = true
                        scope.launch {
                            try {
                                val encoded = android.util.Base64.encodeToString(frame, android.util.Base64.NO_WRAP)
                                answer = ChatApiClient().analyzeCameraFrame(prompt, encoded).text
                            } catch (t: Throwable) {
                                answer = t.message ?: "Live camera analysis failed."
                            } finally {
                                analyzing = false
                            }
                        }
                    }
                }
            )
            SurfaceCard("Live indicator", "● Camera active • BQ Sqwad AI is viewing frames while this screen is active.")
            OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ask about the camera view") }, maxLines = 2)
            if (analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (answer.isNotBlank()) SurfaceCard("BQ Sqwad AI", answer)
        } else {
            SurfaceCard("Camera status", "Camera preview is stopped. Start the live session after granting permission.")
        }
    }
}

@Composable
private fun VoiceWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var transcript by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsFailed by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            ttsFailed = status != TextToSpeech.SUCCESS
        }
    }
    val recognizer = remember { android.speech.SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(tts, recognizer) {
        onDispose {
            recognizer.cancel()
            recognizer.destroy()
            tts.stop()
            tts.shutdown()
            context.stopService(Intent(context, LiveMediaForegroundService::class.java))
        }
    }
    val listener = remember {
        object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
            override fun onBeginningOfSpeech() { listening = true }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false }
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) return
                transcript = text
                scope.launch {
                    try {
                        val generated = ChatApiClient().sendMessage(text).text
                        reply = generated
                        if (generated.isNotBlank() && ttsReady) {
                            speaking = true
                            tts.speak(generated, TextToSpeech.QUEUE_FLUSH, null, "bq_live_voice")
                        }
                    } catch (t: Throwable) {
                        reply = t.message ?: "Voice request failed."
                    }
                }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                transcript = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        }
    }
    DisposableEffect(listener) {
        recognizer.setRecognitionListener(listener)
        onDispose { recognizer.setRecognitionListener(null) }
    }
    fun startListening() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java).putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_VOICE))
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startListening() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live Voice AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Live microphone input, partial transcription, AI reply and spoken response with a visible session indicator.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                tts.stop()
                speaking = false
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) else startListening()
            }) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (listening) "Listening…" else "Start live voice")
            }
            OutlinedButton(onClick = {
                recognizer.cancel()
                listening = false
                tts.stop()
                speaking = false
                context.stopService(Intent(context, LiveMediaForegroundService::class.java))
            }) { Text("End") }
        }
        if (listening) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (transcript.isNotBlank()) SurfaceCard("Live transcript", transcript)
        if (reply.isNotBlank()) SurfaceCard("BQ Sqwad AI", reply)
        OutlinedButton(onClick = { tts.stop(); speaking = false }, enabled = speaking) { Text("Stop speaking") }
        if (ttsFailed) Text("Android text-to-speech could not initialize. Check the device speech settings.", color = MaterialTheme.colorScheme.error)
        SurfaceCard("Live status", if (listening) "● Microphone active" else "Microphone stopped")
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
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "system") ?: "system") }
    var accent by remember { mutableStateOf(prefs.getString("accent_palette", "signature") ?: "signature") }
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
            item { FeatureCard("Privacy center", "Review the BQ Sqwad AI privacy policy in your browser.") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bqsqwadai.com/privacy"))) } }
            item { FeatureCard("AI Birthday", "Account age is derived from the account creation date.") }
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
