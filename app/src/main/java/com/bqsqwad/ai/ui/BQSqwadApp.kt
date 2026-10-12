@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bqsqwad.ai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.BitmapFactory
import java.net.URL
import android.net.Uri
import android.util.Base64
import android.provider.Settings
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.widget.VideoView
import java.util.UUID
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.bqsqwad.ai.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.bqsqwad.ai.core.*
import com.bqsqwad.ai.domain.Workspace
import io.github.thibaultbee.streampack.compose.SourcePreview
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject

internal fun safeUserFacingIssue(throwable: Throwable, fallback: String): String {
    val message = throwable.message?.trim().orEmpty()
    if (message.isBlank()) return fallback
    val lower = message.lowercase()
    if (lower.contains("insufficient credits") || lower.contains("insufficient_credits") || lower.contains("credit_balance")) {
        return "You've run out of BQ Sqwad AI credits. Open Plans & Credits to continue."
    }
    if (lower.contains("capability_disabled") || lower.contains("capability_killed") ||
        lower.contains("image_generation_disabled") || lower.contains("feature_disabled")) {
        return "This BQ Sqwad AI feature is currently turned off. Try another feature or contact support."
    }
    if (lower.contains("history_message_limit_exceeded")) return "This conversation has more than 100 messages. Start a new chat to keep history complete."
    if (lower.contains("history_message_too_large") || lower.contains("history_prompt_too_large")) return "A message is too large to save in history. Keep individual messages under 20,000 characters."
    if (lower.contains("history_payload_too_large")) return "This conversation is too large to save as one history item. Start a new chat to keep history complete."
    if (lower.contains("history_attachment_limit_exceeded") || lower.contains("history_attachment_name_too_large")) return "This message has too many or too-large attachment names to save."
    if (lower.contains("attachment_payload_too_large")) return "The combined attachment size is too large. Send fewer or smaller files."
    if (lower.contains("attachment_count_invalid")) return "Attach up to four supported files at a time."
    if (lower.contains("file_type_invalid")) return "This file type is not supported for original-file analysis. Use a PDF or a supported text document."
    if (lower.contains("consent_required")) return "Accept the required consent in Settings before using AI features."
    if (lower.contains("plan_required") || lower.contains("entitlement_required")) return "This feature is not included in your current plan. Open Plans & Credits to review access."
    if (lower.contains("rate limit") || lower.contains("rate_limited") || lower.contains("http 429")) return "You are sending requests too quickly. Wait a moment and try again."
    val technical = Regex("https?://|\\bHTTP\\s*\\d{3}\\b|route_not_migrated|UnknownHostException|SocketTimeoutException|java\\.|com\\.bqsqwad|CLEARTEXT|Exception:|provider_http|provider_not_configured|not_configured|client_secret_missing|realtime_session_failed|image_generation_|image_edit_|firebase|firestore|storage/|stack trace|internal server|\\bstatus code\\b|\\b(?:401|403|404|429|500|501|502|503)\\b", RegexOption.IGNORE_CASE)
    return if (technical.containsMatchIn(message)) {
        "BQ Sqwad AI is temporarily unavailable. The BQ Sqwad AI team is working on it. Please try again shortly."
    } else message.take(240)
}

@Composable
fun BQSqwadApp(
    modifier: Modifier = Modifier,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {}
) = AuthGate(modifier, sharedText, onSharedTextConsumed)

@Composable
private fun AuthGate(modifier: Modifier, sharedText: String?, onSharedTextConsumed: () -> Unit) {
    // Guest browsing remains available, but protected actions open the real Firebase auth flow.
    // MainShell observes FirebaseAuth state so a successful sign-in immediately updates the UI.
    MainShell(modifier, sharedText, onSharedTextConsumed)
}

@Composable
private fun EmailVerificationGate(modifier: Modifier, onVerified: () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshVerification() {
        val current = auth.currentUser ?: return
        busy = true
        notice = null
        error = null
        current.reload().addOnCompleteListener { task ->
            busy = false
            if (task.isSuccessful && auth.currentUser?.isEmailVerified == true) {
                onVerified()
                notice = "Email verified. Checking your required consent…"
            } else if (task.isSuccessful) {
                error = "Your email is not verified yet. Open the verification email, then tap Check again."
            } else {
                error = task.exception?.message ?: "Could not refresh your account status."
            }
        }
    }

    AuthSurface {
        Text("Verify your email", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Please verify your email address before continuing. This is separate from the required Terms and Privacy consent.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { refreshVerification() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Checking…" else "I verified my email — check again")
        }
        OutlinedButton(
            onClick = {
                val current = auth.currentUser ?: return@OutlinedButton
                busy = true
                error = null
                current.sendEmailVerification().addOnCompleteListener { task ->
                    busy = false
                    if (task.isSuccessful) notice = "A new verification email was sent."
                    else error = task.exception?.message ?: "Could not send the verification email."
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Resend verification email") }
        TextButton(onClick = { auth.signOut() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun authSignOutForConsent() {
    FirebaseAuth.getInstance().signOut()
}

@Composable
private fun ConsentGate(modifier: Modifier, onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var trainingConsent by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            runCatching {
                withTimeoutOrNull(12000) { WorkspaceApiClient().consent() } ?: error("consent_timeout")
            }
                .onSuccess {
                    accepted = it.optBoolean("requiredAccepted")
                    trainingConsent = it.optBoolean("trainingConsent")
                }
                .onFailure {
                    error = when (it) {
                        is TimeoutCancellationException ->
                            "BQ Sqwad AI could not reach its secure service in time. Please check your connection and try again."
                        else -> it.message?.takeIf { message -> message.isNotBlank() }
                            ?: "BQ Sqwad AI could not verify your consent right now. Please try again."
                    }
                }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (completed) {
        onComplete()
    } else if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        AuthSurface {
            Text("Before you continue", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Please accept the BQ Sqwad AI Terms and Conditions and Privacy Policy. This consent is verified by the server before protected AI features can be used.",
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = accepted,
                    onCheckedChange = { accepted = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.outline,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                Text(
                    "I accept the Terms and Conditions and Privacy Policy.",
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = trainingConsent,
                    onCheckedChange = { trainingConsent = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.outline,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                Text(
                    "Optional: allow non-private training activity to improve the service.",
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            LegalConsentLinks()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        authSignOutForConsent()
                    },
                    enabled = !saving,
                    modifier = Modifier.weight(1f)
                ) { Text("Reject & sign out") }
                Button(
                    enabled = accepted && !saving,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f)
                    ),
                    onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            runCatching { WorkspaceApiClient().saveConsent("2026-09", trainingConsent) }
                                .onSuccess {
                                    completed = true
                                    context.getSharedPreferences("bq_consent", Context.MODE_PRIVATE).edit()
                                        .putString("policy_version", "2026-09")
                                        .putLong("required_consent_at", System.currentTimeMillis())
                                        .putBoolean("required_accepted", true)
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
                    modifier = Modifier.weight(1f)
                ) { Text(if (saving) "Saving…" else "Accept & continue") }
            }
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
                                notice = "Signed in. We sent a verification email to " + (signedIn.email ?: "your email address") + ". Verification is recommended, but it does not replace the required Terms and Privacy consent."
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
                        withTimeout(15000) { performGoogleSignIn(context, auth) }
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
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GoogleLogoMark(22.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (busy) "Signing in with Google…" else "Continue with Google")
            }
        }

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

        val googleScope = rememberCoroutineScope()

        OutlinedButton(
            onClick = {
                busy = true
                error = null
                googleScope.launch {
                    try {
                        performGoogleSignIn(context, auth)
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
            enabled = !busy && name.isNotBlank() &&
                android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() &&
                password.length >= 6 && password == confirmPassword,
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
                        if (created == null) {
                            busy = false
                            error = "Account was created, but the session could not be established. Please sign in again."
                            return@addOnCompleteListener
                        }
                        created.updateProfile(
                            com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                .setDisplayName(name.trim())
                                .build()
                        ).addOnCompleteListener {
                            created.sendEmailVerification().addOnCompleteListener {
                                busy = false
                                onAuthenticated()
                            }
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
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { browserUrl = "https://bqsqwadai.com/terms#:~:text=Using%20the%20service" }) { Text("Terms and Conditions") }
            Text("•", color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = { browserUrl = "https://bqsqwadai.com/privacy-policy#:~:text=Information%20we%20collect" }) { Text("Privacy Policy") }
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
    var backgroundVideoUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { WorkspaceApiClient().publicAppConfig() }
            .onSuccess { config ->
                if (config.optBoolean("authBackgroundVideoEnabled", false)) {
                    backgroundVideoUrl = config.optString("authBackgroundVideoUrl").takeIf { it.isNotBlank() }
                }
            }
    }
    Box(Modifier.fillMaxSize()) {
        backgroundVideoUrl?.let { url ->
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(Uri.parse(url))
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().alpha(0.18f)
            )
        }
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background.copy(alpha = if (backgroundVideoUrl == null) 1f else 0.90f)
        ) {
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
    }
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

    // This is the explicit Google button flow. It avoids presenting the
    // generic Credential Manager sheet (which can include passkeys) and lets
    // users add/re-authenticate a Google account when necessary.
    val serverClientId = context.getString(R.string.default_web_client_id)
    if (serverClientId.isBlank()) {
        throw IllegalStateException("Google sign-in is not configured: default web client ID is missing.")
    }
    val googleOption = GetSignInWithGoogleOption.Builder(serverClientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleOption)
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
                    ?: continuation.resumeWithException(
                        IllegalStateException("Firebase did not return an authentication result.")
                    )
            } else {
                continuation.resumeWithException(
                    completed.exception ?: IllegalStateException("Firebase Google authentication failed.")
                )
            }
        }
        continuation.invokeOnCancellation { }
    }
    return authResult.user
        ?: throw IllegalStateException("Firebase did not return a signed-in user.")
}

private fun friendlyGoogleAuthError(error: Throwable): String {
    return when (error) {
        is GetCredentialException -> "Google sign-in could not be completed. Please choose a Google account and try again."
        is TimeoutCancellationException -> "Google sign-in timed out. Please try again."
        is com.google.firebase.auth.FirebaseAuthUserCollisionException -> "This Google account is already linked to another sign-in method."
        is com.google.firebase.FirebaseNetworkException -> "Network connection failed. Check your internet connection and try again."
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: "Google sign-in could not be completed. Please try again."
    }
}

@Composable
private fun GoogleLogoMark(size: Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = size.toPx() * 0.18f
        val radius = (size.toPx() - stroke) / 2f
        val center = androidx.compose.ui.geometry.Offset(size.toPx() / 2f, size.toPx() / 2f)
        val arcSize = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)
        val style = Stroke(width = stroke, cap = StrokeCap.Butt)
        val topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius)
        drawArc(Color(0xFF4285F4), -45f, 100f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFF34A853), 55f, 95f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFFFBBC05), 150f, 95f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFFEA4335), 245f, 70f, false, topLeft, arcSize, style = style)
        drawLine(Color(0xFF4285F4), center, androidx.compose.ui.geometry.Offset(size.toPx() * 0.88f, center.y), stroke, StrokeCap.Butt)
    }
}


@Composable
private fun MainShell(modifier: Modifier, sharedText: String?, onSharedTextConsumed: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    var firebaseUser by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseUser = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    val email = firebaseUser?.email.orEmpty()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var workspace by remember { mutableStateOf<Workspace?>(null) }
    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) workspace = null
    }
    var drawerOpen by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var brandingSettings by remember { mutableStateOf(false) }
    var accountProtection by remember { mutableStateOf(false) }
    var legalCenter by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var restoredChat by remember { mutableStateOf<Pair<String, List<HistoryMessage>>?>(null) }
    var featureInfo by remember { mutableStateOf<Workspace?>(null) }
    var featurePrivacy by remember { mutableStateOf<Workspace?>(null) }
    var featureHub by remember { mutableStateOf(false) }
    var featureSettings by remember { mutableStateOf<Workspace?>(null) }
    var advancedFeature by remember { mutableStateOf<AppFeature?>(null) }
    var adminAuthorized by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE).getBoolean("notifications_enabled", true)) }
    var notificationStatus by remember { mutableStateOf<String?>(null) }
    var workspaceAccess by remember { mutableStateOf(WorkspaceAccessSnapshot.empty()) }
    var accessLoading by remember { mutableStateOf(true) }
    var paywallWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var signInRequired by remember { mutableStateOf(false) }
    var signUpRequired by remember { mutableStateOf(false) }
    var appLayoutOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var appLayoutEnabled by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var consentRequired by remember { mutableStateOf(false) }
    var consentLoading by remember { mutableStateOf(false) }

    LaunchedEffect(email) {
        if (email.isBlank()) {
            adminAuthorized = false
            return@LaunchedEffect
        }
        // Keep startup work parallel: chat stays visible while authorization and
        // notification registration load independently.
        launch {
            adminAuthorized = runCatching {
                WorkspaceApiClient().adminAccess().optBoolean("allowed")
            }.getOrDefault(false)
        }
    }

    LaunchedEffect(email) {
        if (email.isBlank()) {
            consentRequired = false
            consentLoading = false
            accessLoading = false
            workspaceAccess = WorkspaceAccessSnapshot.empty()
            return@LaunchedEffect
        }
        // Keep the main chat interactive during startup. Consent is still enforced by
        // the backend for protected actions; a slow status check must not block the chat UI.
        consentLoading = false
        runCatching { WorkspaceApiClient().consent() }
            .onSuccess { consentRequired = !it.optBoolean("requiredAccepted", false) }
            .onFailure {
                // Do not trap users behind a startup spinner if the consent-status
                // endpoint is temporarily unavailable; protected requests are still
                // checked by the backend before they execute.
                consentRequired = false
            }
    }

    LaunchedEffect(email, notificationsEnabled) {
        if (email.isBlank()) {
            // Invalidate the device token on sign-out so a previous account cannot
            // continue delivering notifications to this device.
            launch { runCatching { withContext(Dispatchers.IO) { Tasks.await(FirebaseMessaging.getInstance().deleteToken()) } } }
            return@LaunchedEffect
        }
        launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val token = Tasks.await(FirebaseMessaging.getInstance().token)
                    if (token.isNotBlank()) {
                        val api = WorkspaceApiClient()
                        if (notificationsEnabled) api.registerNotificationToken(token)
                        else api.unregisterNotificationToken(token)
                    }
                }
            }.onSuccess {
                notificationStatus = if (notificationsEnabled) "Notifications are enabled for this account." else "Notifications are paused."
            }.onFailure {
                notificationStatus = "Notification preferences could not be synced. Check your connection and try again."
            }
        }
        if (
            notificationsEnabled &&
            android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(email, workspace) {
        // Refresh when returning from a workspace so an administrator's test-mode
        // change is reflected without requiring an app restart.
        if (email.isBlank()) {
            workspaceAccess = WorkspaceAccessSnapshot.empty()
            accessLoading = false
            return@LaunchedEffect
        }
        accessLoading = true
        val api = WorkspaceApiClient()
        runCatching {
            val catalog = api.nativeCatalog()
            val entitlements = runCatching { api.entitlements() }.getOrElse { JSONObject() }
            val admin = runCatching { api.adminAccess().optBoolean("allowed") }.getOrDefault(false)
            val testMode = if (admin) runCatching { AdminAdvancedClient().testMode() }.getOrDefault(false) else false
            WorkspaceAccessSnapshot.fromCatalog(catalog, entitlements, admin, testMode)
        }.onSuccess { workspaceAccess = it }
         .onFailure { workspaceAccess = WorkspaceAccessSnapshot.empty() }
        accessLoading = false
    }

    val openWorkspace: (Workspace) -> Unit = { selected ->
        drawerOpen = false
        // Guest browsing is allowed in Main Chat, but every other workspace is
        // account-scoped and must require Firebase authentication first.
        if (FirebaseAuth.getInstance().currentUser == null) {
            workspace = null
            signInRequired = true
        } else if (selected == Workspace.ADMIN) {
            // Never expose an admin-only destination as a user-facing plan/paywall option.
            if (adminAuthorized) workspace = selected
        } else if (accessLoading) {
            paywallWorkspace = selected
        } else if (workspaceAccess.hasVerifiedAccess(selected)) {
            workspace = selected
        } else {
            paywallWorkspace = selected
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) drawerState.open() else drawerState.close()
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                LazyColumn(
                    Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BqLogo(48.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("BQ Sqwad AI", fontWeight = FontWeight.Bold)
                                Text("The AI That Grows With You", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { HorizontalDivider() }
                    item {
                        NavigationDrawerItem(
                            label = { Text("New Chat") },
                            selected = workspace == null && !history && !settings,
                            onClick = { drawerOpen = false; history = false; settings = false; workspace = null },
                            icon = { Icon(Icons.Default.AddComment, null) }
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Chat History") },
                            selected = history,
                            onClick = { drawerOpen = false; history = true },
                            icon = { Icon(Icons.Default.History, null) }
                        )
                    }
                    item {
                        Text(
                            "AI Features",
                            modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(orderedDrawerWorkspaces(appLayoutOrder, appLayoutEnabled, adminAuthorized)) { w ->
                        NavigationDrawerItem(
                            label = { Text(w.title) },
                            selected = workspace == w,
                            onClick = { openWorkspace(w) },
                            icon = {
                                Icon(
                                    when (w) {
                                        Workspace.VIDEO -> Icons.Default.VideoLibrary
                                        Workspace.SEARCH -> Icons.Default.Search
                                        Workspace.TRUTH_GUARD -> Icons.Default.Verified
                                        Workspace.FRONTIER_LAB -> Icons.Default.Science
                                        Workspace.DATA_ANALYSIS -> Icons.Default.Analytics
                                        Workspace.CAMERA -> Icons.Default.CameraAlt
                                        Workspace.VOICE -> Icons.Default.Mic
                                        Workspace.PERSONAL -> Icons.Default.Psychology
                                        Workspace.LIFE -> Icons.Default.FavoriteBorder
                                        Workspace.COMPANION -> Icons.Default.Face
                                        Workspace.LEARNING -> Icons.Default.School
                                        Workspace.BUSINESS -> Icons.Default.Business
                                        Workspace.ORGANIZATION_STUDIO -> Icons.Default.Dashboard
                                        Workspace.ADMIN -> Icons.Default.AdminPanelSettings
                                        else -> Icons.Default.AutoAwesome
                                    },
                                    contentDescription = null
                                )
                            }
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Plans & Credits") },
                            selected = plans,
                            onClick = { drawerOpen = false; plans = true },
                            icon = { Icon(Icons.Default.CreditCard, null) }
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Settings") },
                            selected = settings,
                            onClick = { drawerOpen = false; settings = true },
                            icon = { Icon(Icons.Default.Settings, null) }
                        )
                    }
                }
            }
        },
        modifier = modifier
    ) {
        if (firebaseUser != null && (consentLoading || consentRequired)) {
            if (consentLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                ConsentGate(Modifier.fillMaxSize()) {
                    consentRequired = false
                }
            }
        } else when {
            featureHub -> FeatureHub(
                onBack = { featureHub = false },
                onPlans = { featureHub = false; plans = true },
                onHistory = { featureHub = false; history = true },
                onWorkspace = { selected ->
                    featureHub = false
                    openWorkspace(selected)
                },
                onAdvancedFeature = { selected -> featureHub = false; advancedFeature = selected }
            )
            featureSettings != null -> FeatureSettingsScreen(featureSettings!!) { featureSettings = null }
            advancedFeature != null -> AdvancedFeatureScreen(
                feature = advancedFeature!!,
                onBack = { advancedFeature = null },
                onOpenSettings = { settings = true }
            )
            plans -> ProductCatalogScreen { plans = false }
            brandingSettings -> BrandingSettingsScreen { brandingSettings = false }
            accountProtection -> AccountProtectionScreen { accountProtection = false }
            featureInfo != null -> FeatureInfoScreen(featureInfo!!) { featureInfo = null }
            featurePrivacy != null -> LegalCenterScreen(initialTopic = featurePrivacy!!) { featurePrivacy = null }
            legalCenter -> LegalCenterScreen { legalCenter = false }
            settings -> SettingsScreen(
                onBack = { settings = false },
                onOpenAccountProtection = { accountProtection = true },
                onOpenLegalCenter = { legalCenter = true },
                onOpenBranding = { brandingSettings = true },
                onNotificationsChanged = { notificationsEnabled = it },
                notificationStatus = notificationStatus,
                onOpenWorkspace = { selected -> settings = false; openWorkspace(selected) }
            )
            history -> HistoryScreen(
                onBack = { history = false },
                onContinue = { item, savedMessages ->
                    restoredChat = item.id to savedMessages
                    history = false
                }
            )
            workspace != null -> {
                if (workspace == Workspace.ADMIN) {
                    if (adminAuthorized) {
                        AdminOnlyWorkspace(onBack = { workspace = null })
                    } else {
                        AccessDenied()
                    }
                } else {
                    WorkspaceScreen(
                        workspace!!,
                        email,
                        adminAuthorized,
                        onBack = { workspace = null },
                        onLearnMore = { featureInfo = workspace!! },
                        onPrivacy = { featurePrivacy = workspace!! },
                        onSettings = { featureSettings = workspace!! },
                        onOpenAppSettings = { settings = true },
                        onOpenPlans = { plans = true }
                    )
                }
            }
            else -> MainChatScreen(
                modifier = Modifier.fillMaxSize(),
                openHub = { drawerOpen = true },
                openFeatures = { featureHub = true },
                openSettings = { settings = true },
                openPlans = { plans = true },
                openHistory = { history = true },
                openPlugins = { featureHub = true },
                onLearnMore = { featureInfo = Workspace.CHAT },
                onPrivacy = { featurePrivacy = Workspace.CHAT },
                restoredChat = restoredChat,
                onRestoredChatConsumed = { restoredChat = null },
                sharedText = sharedText,
                onSharedTextConsumed = onSharedTextConsumed
            )
        }
    }

    if (signInRequired || signUpRequired) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f)
        ) {
            if (signUpRequired) {
                SignUpScreen(
                    onBackToSignIn = {
                        signUpRequired = false
                        signInRequired = true
                    },
                    onAuthenticated = {
                        signUpRequired = false
                        signInRequired = false
                        workspace = null
                    }
                )
            } else {
                SignInScreen(
                    onOpenSignUp = {
                        signInRequired = false
                        signUpRequired = true
                        workspace = null
                    },
                    onAuthenticated = {
                        signInRequired = false
                        signUpRequired = false
                        workspace = null
                    }
                )
            }
        }
    }

    paywallWorkspace?.let { selected ->
        AlertDialog(
            onDismissRequest = { paywallWorkspace = null },
            title = { Text("Plan required") },
            text = {
                Text(
                    if (accessLoading) "Checking verified access for " + selected.title + "…"
                    else selected.title + " requires a verified plan or an eligible free tier. Your access is checked by the server before the workspace opens."
                )
            },
            confirmButton = {
                Button(onClick = { paywallWorkspace = null; plans = true }) { Text("View Plans & Credits") }
            },
            dismissButton = {
                TextButton(onClick = { paywallWorkspace = null }) { Text("Cancel") }
            }
        )
    }
}

internal fun isImageGenerationRequest(text: String): Boolean {
    val q = text.trim().lowercase().replace(Regex("\\s+"), " ")
    if (q.isBlank()) return false

    // Exclude informational questions about image concepts; "Can you create..." remains a request.
    if (Regex("^(what is|what are|why does|why is|explain|can you explain|could you explain|can you tell me about|could you tell me about|can you tell me how|could you tell me how|tell me about|tell me how|describe|difference between|show me how|how do i|how can i|how would i|how does .* work|how can i learn|how to|do you know how to|does .* know how to|what is the best way to|what are the steps to|i want to know|i need to know|what should i|is there a way to|should i|do i need to|is it okay to|is it worth|would it be better|can i|could i|may i|should we)\\b")
            .containsMatchIn(q)) return false
    if (Regex("\\b(don't|do not|never|avoid|stop)\\b.{0,50}\\b(generate|create|make|draw|design|produce|render|illustrate|paint)\\b.{0,50}\\b(images?|pictures?|photos?|logos?|posters?|illustrations?|wallpapers?|flyers?|banners?|artwork|portraits?)\\b")
            .containsMatchIn(q)) return false

    val action = Regex("\\b(generate|create|make|draw|design|produce|render|illustrate|paint|craft|build)\\b|\\bshow me\\b")
    val asset = Regex("\\b(images?|pictures?|photos?|logos?|posters?|illustrations?|wallpapers?|flyers?|banners?|artwork|portraits?|drawings?|scenes?|characters?|thumbnails?|covers?|icons?)\\b")
    val actionAtStart = Regex("^(please )?(can you |could you |would you )?(help me )?(generate|create|make|draw|design|produce|render|illustrate|paint|craft|build|show me)\\b")
    val directCreationRequest = Regex("\\b(i want|i need|give me|make me|create for me|generate for me|please make|please create|please generate)\\b")
    val drawObjectRequest = Regex("\\b(draw|illustrate|paint)\\s+(me\\s+)?(a|an|the)\\s+[a-z0-9-]+\\b")
    return (action.containsMatchIn(q) && asset.containsMatchIn(q)) ||
        (directCreationRequest.containsMatchIn(q) && asset.containsMatchIn(q)) ||
        (actionAtStart.containsMatchIn(q) && drawObjectRequest.containsMatchIn(q))
}

private data class ChatMessageAttachment(val name: String, val mimeType: String, val imageBase64: String? = null)
private data class ChatMessage(
    val mine: Boolean,
    val text: String,
    val id: String = UUID.randomUUID().toString(),
    val prompt: String? = null,
    val attachments: List<ChatMessageAttachment> = emptyList(),
    val imageUrl: String? = null
)


private fun decodeImageBytes(imageBase64: String): ByteArray = Base64.decode(imageBase64, Base64.DEFAULT)

/** Load an owner's HTTPS Storage image when a saved chat is reopened. */
private fun downloadGeneratedImageBase64(imageUrl: String): String {
    require(imageUrl.startsWith("https://", ignoreCase = true)) { "Only secure image links can be opened." }
    val connection = (java.net.URL(imageUrl).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        requestMethod = "GET"
    }
    try {
        val status = connection.responseCode
        if (status !in 200..299) error("The saved image could not be loaded.")
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 20 * 1024 * 1024) { "The saved image is too large to open." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.isNotEmpty()) { "The saved image is empty." }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    } finally {
        connection.disconnect()
    }
}

/**
 * Upload generated bytes to the signed-in user's Storage path and return a durable
 * HTTPS URL for history. The URL is never logged. A Storage denial does not discard
 * the in-memory image; the UI can still show it and explain that persistence failed.
 */
private fun uploadGeneratedImage(imageBase64: String): String {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in is required")
    val bytes = decodeImageBytes(imageBase64)
    val ref = FirebaseStorage.getInstance().reference
        .child("users/" + uid + "/generated-images/" + UUID.randomUUID() + ".png")
    val metadata = StorageMetadata.Builder().setContentType("image/png").build()
    com.google.android.gms.tasks.Tasks.await(ref.putBytes(bytes, metadata))
    return com.google.android.gms.tasks.Tasks.await(ref.downloadUrl).toString()
}

private fun saveGeneratedImage(context: Context, imageBase64: String): Uri {
    val bytes = decodeImageBytes(imageBase64)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "BQ-Sqwad-AI-" + System.currentTimeMillis() + ".png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BQ Sqwad AI")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create an image file on this device.")
    try {
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("Could not write the generated image.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    } catch (t: Throwable) { resolver.delete(uri, null, null); throw t }
}

private fun shareGeneratedImage(context: Context, imageBase64: String) {
    val uri = saveGeneratedImage(context, imageBase64)
    val intent = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(Intent.createChooser(intent, "Share image"))
}

@Composable
private fun ChatAttachmentPreview(attachment: ChatMessageAttachment, onImageClick: (() -> Unit)? = null) {
    var decoding by remember(attachment.imageBase64) { mutableStateOf(attachment.imageBase64 != null) }
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = attachment.imageBase64) {
        val encoded = attachment.imageBase64
        value = if (encoded.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        decoding = false
    }
    if (image != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Image(
                bitmap = image!!,
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).then(if (onImageClick != null) Modifier.clickable(onClick = onImageClick) else Modifier)
            )
            Text(attachment.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (decoding) {
        Text("Loading " + attachment.name + "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (attachment.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.AttachFile, contentDescription = null)
                Column {
                    Text(attachment.name, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text(
                        when {
                            attachment.mimeType.startsWith("image/") -> "Image attachment"
                            attachment.mimeType.startsWith("text/") -> "Text attachment"
                            else -> attachment.mimeType
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChatRemoteImage(url: String, description: String = "Image") {
    var loadFailed by remember(url) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        loadFailed = false
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (!url.startsWith("https://", ignoreCase = true)) return@runCatching null
                URL(url).openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 20000
                }.getInputStream().use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
        value = loaded
        loadFailed = loaded == null
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp))
        )
    } else {
        Text(
            if (loadFailed) "This image could not be loaded. The image link may have expired."
            else "Loading image…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BqHeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MainChatScreen(modifier: Modifier, openHub: () -> Unit, openFeatures: () -> Unit, openSettings: () -> Unit, openPlans: () -> Unit, openHistory: () -> Unit, openPlugins: () -> Unit, onLearnMore: () -> Unit, onPrivacy: () -> Unit, restoredChat: Pair<String, List<HistoryMessage>>?, onRestoredChatConsumed: () -> Unit, sharedText: String?, onSharedTextConsumed: () -> Unit) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("bq_ui", Context.MODE_PRIVATE) }
    val settingsPrefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    var chatHistoryEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("chat_history", true)) }
    var autoSaveEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("auto_save", true)) }
    var voiceRepliesEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("voice_replies", true)) }
    var savingChat by remember { mutableStateOf(false) }
    var wallpaper by remember { mutableStateOf(prefs.getInt("wallpaper", 0)) }
    var message by remember { mutableStateOf("") }
    // Text shared from another Android app is placed into the composer for user review.
    // It is never submitted automatically.
    LaunchedEffect(sharedText) {
        val incoming = sharedText?.trim().orEmpty()
        if (incoming.isNotBlank()) {
            message = "Please help me with this selected text:\n\n$incoming"
            onSharedTextConsumed()
        }
    }
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
    var selectedAdvanced by remember { mutableStateOf<AppFeature?>(null) }
    var publishedAiModels by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedTextModelId by remember { mutableStateOf("") }
    var selectedImageModelId by remember { mutableStateOf("") }

    var privateChat by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready") }
    var creditNotice by remember { mutableStateOf<String?>(null) }
    var creditsExhausted by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    // Keep Private Chat turns visible in this session but never include them in a saved history payload.
    val privateMessageIds = remember { mutableStateListOf<String>() }
    fun appendChatMessage(chatMessage: ChatMessage) {
        messages.add(chatMessage)
        if (privateChat) privateMessageIds.add(chatMessage.id)
    }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMimeType by remember { mutableStateOf("image/png") }
    var selectedImagePrompt by remember { mutableStateOf("") }
    var selectedImageMessageId by remember { mutableStateOf<String?>(null) }
    var showImageActions by remember { mutableStateOf(false) }
    var imageActionLoading by remember { mutableStateOf(false) }
    var showImageViewer by remember { mutableStateOf(false) }
    var showImageEdit by remember { mutableStateOf(false) }
    var imageEditPrompt by remember { mutableStateOf("") }
    var showImageResize by remember { mutableStateOf(false) }
    var selectedImageSize by remember { mutableStateOf("1024x1024") }
    var textModelMenuOpen by remember { mutableStateOf(false) }
    var imageModelMenuOpen by remember { mutableStateOf(false) }

    fun applyPublishedAiModels(array: JSONArray) {
        val latest = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        publishedAiModels = latest
        if (selectedTextModelId.isNotBlank() && latest.none {
                it.optString("id") == selectedTextModelId &&
                    it.optString("feature") == "text-chat" &&
                    it.optBoolean("active") && it.optBoolean("published")
            }) selectedTextModelId = ""
        if (selectedImageModelId.isNotBlank() && latest.none {
                it.optString("id") == selectedImageModelId &&
                    it.optString("feature") == "image-generation" &&
                    it.optBoolean("active") && it.optBoolean("published")
            }) selectedImageModelId = ""
    }

    LaunchedEffect(Unit) {
        runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }
            .onSuccess { array -> applyPublishedAiModels(array) }
    }

    LaunchedEffect(restoredChat?.first) {
        val saved = restoredChat ?: return@LaunchedEffect
        historyId = saved.first
        messages.clear()
        privateMessageIds.clear()
        messages.addAll(saved.second.map { item ->
            ChatMessage(
                mine = item.mine,
                text = item.text,
                id = item.id,
                prompt = item.prompt,
                imageUrl = item.imageUrl,
                attachments = item.attachmentNames.map { encoded ->
                    val split = encoded.indexOf('|')
                    if (split > 0) ChatMessageAttachment(encoded.substring(split + 1), encoded.substring(0, split))
                    else ChatMessageAttachment(encoded, "application/octet-stream")
                }
            )
        })
        attachments = emptyList()
        selectedAdvanced = null
        privateChat = false
        error = null
        statusText = "Saved conversation loaded."
        onRestoredChatConsumed()
    }

    fun applyCreditSnapshot(snapshot: JSONObject) {
        if (snapshot.optBoolean("adminUnlimited")) {
            creditNotice = null
            creditsExhausted = false
            return
        }
        val available = snapshot.optDouble("available", 0.0).coerceAtLeast(0.0)
        creditsExhausted = available <= 0.0
        val userKey = FirebaseAuth.getInstance().currentUser?.uid?.take(12) ?: "guest"
        val creditPrefs = context.getSharedPreferences("bq_ui", Context.MODE_PRIVATE)
        val baselineKey = "credit_cycle_baseline_$userKey"
        val previousBaseline = creditPrefs.getFloat(baselineKey, -1f).toDouble()
        val baseline = if (previousBaseline <= 0.0 || available > previousBaseline) available else previousBaseline
        if (baseline > 0.0) creditPrefs.edit().putFloat(baselineKey, baseline.toFloat()).apply()
        val used = (baseline - available).coerceAtLeast(0.0)
        val usedPercent = if (baseline > 0.0) (used / baseline).coerceIn(0.0, 1.0) else 0.0
        val expiryMillis = listOf("subscriptionExpiresAt", "trialExpiresAt")
            .mapNotNull { key ->
                snapshot.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { value ->
                    runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", java.util.Locale.US).parse(value)?.time }.getOrNull()
                        ?: runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", java.util.Locale.US).parse(value)?.time }.getOrNull()
                }
            }
            .filter { it > System.currentTimeMillis() }
            .minOrNull()
        val daysToExpiry = expiryMillis?.let { kotlin.math.ceil((it - System.currentTimeMillis()) / 86_400_000.0).toInt() }
        creditNotice = when {
            available <= 0.0 -> "You've run out of BQ Sqwad AI credits. AI chat, live camera, live voice and other AI features are paused. Buy credits to continue."
            baseline > 0.0 && usedPercent >= 0.90 -> "You've used ${(usedPercent * 100).toInt()}% of your credits (${String.format(java.util.Locale.US, "%.1f", used)} of ${String.format(java.util.Locale.US, "%.1f", baseline)} used). ${String.format(java.util.Locale.US, "%.1f", available)} remain."
            daysToExpiry != null && daysToExpiry <= 5 -> "Your plan or free-credit period expires in ${daysToExpiry.coerceAtLeast(1)} day(s). Renew or buy credits to keep using BQ Sqwad AI."
            else -> null
        }
    }

    LaunchedEffect(FirebaseAuth.getInstance().currentUser?.uid) {
        if (FirebaseAuth.getInstance().currentUser != null) {
            while (true) {
                runCatching { withContext(Dispatchers.IO) { WorkspaceApiClient().credits() } }
                    .onSuccess { applyCreditSnapshot(it) }
                delay(30_000L)
            }
        }
    }

    var streamJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isListening by remember { mutableStateOf(false) }
    val fallbackSpeechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isListening = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                message = if (message.isBlank()) spoken else "$message $spoken"
                micError = null
            } else {
                micError = "No speech was detected. Try again."
            }
        }
    }
    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }
    DisposableEffect(speechRecognizer) {
        val recognizer = speechRecognizer
        if (recognizer != null) {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isListening = true; micError = null }
                override fun onBeginningOfSpeech() { isListening = true }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { isListening = false }
                override fun onError(errorCode: Int) {
                    isListening = false
                    micError = when (errorCode) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected. Try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice input is busy. Try again in a moment."
                        else -> "Voice input failed. Please try again."
                    }
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) {
                        message = if (message.isBlank()) spoken else "$message $spoken"
                        micError = null
                    } else micError = "No speech was detected. Try again."
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
        }
    }
    val startSpeechRecognition: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to BQ Sqwad AI")
        }
        val recognizer = speechRecognizer
        if (recognizer != null) {
            runCatching { recognizer.startListening(intent); isListening = true; micError = null }
                .onFailure { isListening = false; micError = it.message ?: "Could not start voice input." }
        } else {
            val canHandle = runCatching {
                context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
            }.getOrDefault(false)
            if (canHandle) {
                runCatching { fallbackSpeechLauncher.launch(intent); isListening = true; micError = null }
                    .onFailure { isListening = false; micError = "Voice input could not be opened. Please check your installed speech apps." }
            } else {
                micError = "Voice input is not available on this device. Install or enable a speech recognition app and try again."
            }
        }
    }
    val speechPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSpeechRecognition()
        else micError = "Allow microphone access to use voice input."
    }

    fun shouldAutoSaveHistory(): Boolean = com.bqsqwad.ai.core.shouldAutoSaveChat(privateChat, chatHistoryEnabled, autoSaveEnabled)

    fun saveConversationNow() {
        if (busy) {
            error = "Wait for the current response to finish before saving this conversation."
            return
        }
        if (privateChat) {
            error = "Private Chat conversations are never saved."
            return
        }
        if (!chatHistoryEnabled) {
            error = "Turn on Conversation history in Settings before saving a chat."
            return
        }
        val snapshot = messages.filterNot { it.id in privateMessageIds }.map { m ->
            HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name }, m.imageUrl)
        }
        if (snapshot.isEmpty()) {
            error = "There are no non-private messages to save."
            return
        }
        if (savingChat) return
        savingChat = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    HistoryApiClient().save(historyId, snapshot.firstOrNull { it.mine }?.text ?: "New chat", snapshot)
                }
            }.onSuccess {
                historyId = it
                error = null
                statusText = "Conversation saved."
            }.onFailure {
                error = safeUserFacingIssue(it, "Could not save this conversation. Please try again.")
            }
            savingChat = false
        }
    }

    fun sendAuthenticatedMessage(text: String) {
        if ((text.isBlank() && attachments.isEmpty()) || busy) return
        if (creditsExhausted) {
            error = "You've run out of BQ Sqwad AI credits. Buy credits to continue."
            creditNotice = error
            return
        }
        val activeCapability = selectedAdvanced?.capability ?: "chat"
        val conversationContext = messages
            .filter { it.text.isNotBlank() && it.text != "Generating image…" }
            .takeLast(20)
            .joinToString("\n\n") { msg ->
                val role = if (msg.mine) "User" else "BQ Sqwad AI"
                role + ": " + msg.text.take(4000)
            }
            .takeLast(16000)
        val activeSystem = listOfNotNull(
            selectedAdvanced?.let { "You are operating in the BQ Sqwad AI ${it.title} mode. Use the capabilities of this mode when relevant, while remaining accurate about what you can and cannot do." },
            conversationContext.takeIf { it.isNotBlank() }?.let {
                "Recent conversation context. Continue the conversation naturally and do not repeat questions already answered:\n" + it
            }
        ).joinToString("\n\n")
        val imageModeSelected = selectedAdvanced?.capability == AdvancedCapabilities.IMAGE_GENERATION
        val imageRequest = text.isNotBlank() && attachments.isEmpty() &&
            (imageModeSelected || isImageGenerationRequest(text))
        if (imageRequest) {
            val promptText = text.trim()
            message = ""
            appendChatMessage(ChatMessage(true, promptText))
            val aiIndex = messages.size
            appendChatMessage(ChatMessage(false, "Generating image…"))
            busy = true
            statusText = "Generating image…"
            error = null
            streamJob = scope.launch(Dispatchers.IO) {
                try {
                    val generated = GenerationApiClient().generateImage(promptText, modelRegistryId = selectedImageModelId.takeIf { it.isNotBlank() })
                    val durableImageUrl = generated.imageBase64?.let {
                        runCatching { uploadGeneratedImage(it) }.getOrNull()
                    } ?: generated.url.takeIf { it.startsWith("https://", ignoreCase = true) }
                    scope.launch(Dispatchers.Main) {
                        messages[aiIndex] = messages[aiIndex].copy(
                            text = "Image generated.",
                            prompt = promptText,
                            attachments = generated.imageBase64?.let {
                                listOf(ChatMessageAttachment("Generated image.png", "image/png", it))
                            } ?: emptyList(),
                            imageUrl = durableImageUrl
                        )
                        busy = false
                        statusText = "Ready"
                        streamJob = null
                        if (generated.imageBase64 != null && durableImageUrl == null) {
                            error = "Your image was created, but secure image storage is unavailable, so it may not appear when you reopen this chat."
                        }
                        // Image generation is charged server-side; refresh the visible balance
                        // after the request so the chat never keeps showing a stale credit count.
                        runCatching { withContext(Dispatchers.IO) { WorkspaceApiClient().credits() } }
                            .onSuccess { applyCreditSnapshot(it) }
                        if (shouldAutoSaveHistory()) {
                            runCatching {
                                val snapshot = messages.filterNot { it.id in privateMessageIds }.map { m ->
                                    HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name }, m.imageUrl)
                                }
                                withContext(Dispatchers.IO) {
                                    HistoryApiClient().save(historyId, promptText, snapshot)
                                }
                            }.onSuccess { savedId ->
                                historyId = savedId
                            }.onFailure {
                                error = safeUserFacingIssue(it, "The image was generated, but this conversation could not be saved.")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    scope.launch(Dispatchers.Main) {
                        if (messages.getOrNull(aiIndex)?.text == "Generating image…") messages.removeAt(aiIndex)
                        val cancelled = t is kotlinx.coroutines.CancellationException
                        if (cancelled) {
                            error = null
                            statusText = "Stopped."
                        } else {
                            error = safeUserFacingIssue(t, "BQ Sqwad AI could not complete the image request. The team is working on it.")
                            statusText = "Request failed. You can try again."
                        }
                        busy = false
                        streamJob = null
                        // Failed or cancelled generation can settle a server-side reservation;
                        // refresh the canonical balance instead of leaving stale credit state.
                        runCatching { withContext(Dispatchers.IO) { WorkspaceApiClient().credits() } }
                            .onSuccess { applyCreditSnapshot(it) }
                    }
                }
            }
            return
        }
        val selectedAttachments = attachments
        attachments = emptyList()
        message = ""
        val attachmentNames = selectedAttachments.joinToString(", ") { it.name }
        val displayText = if (attachmentNames.isBlank()) text else (text.ifBlank { "Please analyze these attachments." } + "\n📎 " + attachmentNames)
        val textParts = selectedAttachments.mapNotNull { a -> a.text?.let { "Attachment: " + a.name + "\n" + it } }
        if (textParts.sumOf { it.length } > 240_000) {
            error = "The combined text from these files is too large. Remove a file or choose smaller documents, then try again."
            attachments = selectedAttachments
            message = text
            return
        }
        val imageParts = selectedAttachments.filter { it.imageBase64 != null }
        val fileParts = selectedAttachments.filter { it.fileBase64 != null }
        if (imageParts.size > 4 || fileParts.size > 4) {
            error = "You can attach up to 4 images and 4 PDF files per message. Remove extra attachments and try again."
            attachments = selectedAttachments
            message = text
            return
        }
        val actualPrompt = listOf(text.ifBlank { "Please analyze the attached content." }, textParts.joinToString("\n\n")).filter { it.isNotBlank() }.joinToString("\n\n")
        appendChatMessage(ChatMessage(true, displayText, attachments = selectedAttachments.map { ChatMessageAttachment(it.name, it.mimeType, it.imageBase64) }))
        val aiIndex = messages.size
        appendChatMessage(ChatMessage(false, "", prompt = actualPrompt))
        busy = true
        error = null
        statusText = "Connecting to BQ Sqwad AI…"
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                val reply = if (imageParts.isNotEmpty() || fileParts.isNotEmpty()) {
                    val firstImage = imageParts.firstOrNull()
                    ChatApiClient().analyzeCameraFrame(
                        actualPrompt,
                        firstImage?.imageBase64,
                        firstImage?.mimeType ?: "image/jpeg",
                        activeCapability,
                        activeSystem,
                        imageParts.drop(1).map { it.mimeType to it.imageBase64!! },
                        fileParts.map { Triple(it.name, it.mimeType, it.fileBase64!!) }
                    )
                } else {
                    ChatApiClient().streamMessage(actualPrompt, capability = activeCapability, system = activeSystem, modelRegistryId = selectedTextModelId.takeIf { activeCapability == "chat" && it.isNotBlank() }, onDelta = { delta ->
                        scope.launch(Dispatchers.Main) {
                            val current = messages.getOrNull(aiIndex)
                            if (current != null) messages[aiIndex] = current.copy(text = current.text + delta)
                        }
                    })
                }
                scope.launch(Dispatchers.Main) {
                    val current = messages.getOrNull(aiIndex)
                    if (current != null) messages[aiIndex] = current.copy(text = reply.text)
                    busy = false
                    streamJob = null
                    statusText = "Done."
                    runCatching { withContext(Dispatchers.IO) { WorkspaceApiClient().credits() } }
                        .onSuccess { applyCreditSnapshot(it) }
                    if (shouldAutoSaveHistory()) {
                        val snapshot = messages.filterNot { it.id in privateMessageIds }.map { m -> HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name }, m.imageUrl) }
                        runCatching {
                            historyId = HistoryApiClient().save(historyId, text.ifBlank { attachmentNames.ifBlank { "New chat" } }, snapshot)
                        }.onFailure { error = safeUserFacingIssue(it, "The response was generated, but chat history could not be saved.") }
                    }
                }
            } catch (t: Throwable) {
                scope.launch(Dispatchers.Main) {
                    val current = messages.getOrNull(aiIndex)
                    if (current != null && current.text.isBlank()) messages.removeAt(aiIndex)
                    attachments = selectedAttachments
                    message = text
                    if (t !is kotlinx.coroutines.CancellationException) {
                        val detail = t.message.orEmpty().lowercase()
                        if (detail.contains("attachment_payload_too_large") || detail.contains("image_too_large")) {
                            error = "These attachments are too large to send together. Remove a file or choose smaller files, then try again."
                        } else if (detail.contains("file_type_invalid") || detail.contains("file_required")) {
                            error = "BQ Sqwad AI could not read that file format. Try a PDF, DOCX, XLSX, PPTX, or a supported text document, then send again."
                        } else if (detail.contains("image_type_invalid")) {
                            error = "One selected image format is not supported. Choose a JPEG, PNG, or WebP image and try again."
                        } else if (detail.contains("attachment_count_invalid") || detail.contains("image_count_invalid")) {
                            error = "Attach up to four images and four PDF files per message."
                        } else if (detail.contains("insufficient credit") || detail.contains("credit balance is too low") || detail.contains("run out of") || detail.contains("out of credits")) {
                            creditsExhausted = true
                            creditNotice = "You've run out of BQ Sqwad AI credits. Buy credits to continue using AI features."
                            error = creditNotice
                        } else {
                            if (Regex("https?://|\\bHTTP\\s*\\d{3}\\b|route_not_migrated|provider_http|not_configured|realtime_session_failed|unknownhost|timeout|internal server|\\b(?:500|501|502|503)\\b", RegexOption.IGNORE_CASE).containsMatchIn(detail)) {
                                scope.launch(Dispatchers.IO) {
                                    runCatching { WorkspaceApiClient().reportIssue("/ai/stream", "chat_request_failed") }
                                }
                            }
                            error = safeUserFacingIssue(t, "BQ Sqwad AI could not complete your request. The BQ Sqwad AI team is working on it.")
                        }
                        statusText = "Request failed. You can try again."
                    } else {
                        statusText = "Stopped."
                    }
                    busy = false
                    streamJob = null
                    // Stopped or failed turns can still finish or reverse server-side;
                    // resync the canonical balance instead of leaving a stale credit badge.
                    runCatching { withContext(Dispatchers.IO) { WorkspaceApiClient().credits() } }
                        .onSuccess { applyCreditSnapshot(it) }
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val prepared = mutableListOf<PreparedChatAttachment>()
                val failures = mutableListOf<String>()
                for (uri in selected) {
                    runCatching { ChatAttachmentReader.read(context, uri) }
                        .onSuccess { prepared += it }
                        .onFailure { failure ->
                            failures += (failure.message ?: "Could not read an attachment.")
                        }
                }
                scope.launch(Dispatchers.Main) {
                    if (failures.isNotEmpty()) {
                        // Do not silently send only the subset of files that happened
                        // to parse successfully; keep the previous selection unchanged.
                        error = "Could not prepare all selected files:\n" +
                            failures.distinct().take(3).joinToString("\n") +
                            if (failures.size > 3) "\n…and ${failures.size - 3} more." else ""
                    } else {
                        attachments = prepared
                        error = null
                    }
                }
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        WallpaperBackground(wallpaper)
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 3.dp)) {
            Row(
                Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BqHeaderIconButton(
                    icon = Icons.Default.Menu,
                    contentDescription = "Open BQ Sqwad AI menu",
                    onClick = openHub
                )
                BqLogo(30.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text("BQ Sqwad AI", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("The AI That Grows With You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                // Keep the established image-generation animation untouched. For ordinary
                // chat requests, show a compact circular progress indicator in the header.
                if (busy && !statusText.contains("image", ignoreCase = true)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    BqHeaderIconButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = "More chat options",
                        onClick = { menuOpen = true; scope.launch { runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }.onSuccess { array -> applyPublishedAiModels(array) } } } }
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (publishedAiModels.any { it.optString("feature") == "text-chat" && it.optBoolean("active") && it.optBoolean("published") }) {
                            DropdownMenuItem(text = { Text("Text model: " + (publishedAiModels.firstOrNull { it.optString("id") == selectedTextModelId }?.optString("name") ?: publishedAiModels.firstOrNull { it.optString("feature") == "text-chat" && it.optBoolean("isDefault") }?.optString("name") ?: "Server default")) }, onClick = { menuOpen = false; textModelMenuOpen = true })
                        }
                        if (publishedAiModels.any { it.optString("feature") == "image-generation" && it.optBoolean("active") && it.optBoolean("published") }) {
                            DropdownMenuItem(text = { Text("Image model: " + (publishedAiModels.firstOrNull { it.optString("id") == selectedImageModelId }?.optString("name") ?: publishedAiModels.firstOrNull { it.optString("feature") == "image-generation" && it.optBoolean("isDefault") }?.optString("name") ?: "Server default")) }, onClick = { menuOpen = false; imageModelMenuOpen = true })
                        }
                        if (chatHistoryEnabled && !privateChat && messages.isNotEmpty()) {
                            DropdownMenuItem(
                                enabled = !savingChat && !busy,
                                text = { Text(if (savingChat) "Saving conversation…" else "Save conversation now") },
                                onClick = { menuOpen = false; saveConversationNow() }
                            )
                        }
                        DropdownMenuItem(text = { Text("Chat History") }, onClick = { menuOpen = false; openHistory() })
                        DropdownMenuItem(text = { Text("Plugins & AI Features") }, onClick = { menuOpen = false; openPlugins() })
                        DropdownMenuItem(text = { Text("Plans & Credits") }, onClick = { menuOpen = false; openPlans() })
                        DropdownMenuItem(text = { Text("Change background") }, onClick = { menuOpen = false; wallpaper = (wallpaper + 1) % 5; prefs.edit().putInt("wallpaper", wallpaper).apply() })
                        DropdownMenuItem(text = { Text("Learn more") }, onClick = { menuOpen = false; onLearnMore() })
                        DropdownMenuItem(text = { Text("Privacy") }, onClick = { menuOpen = false; onPrivacy() })
                        DropdownMenuItem(text = { Text(if (privateChat) "Turn off Private Chat" else "Private Chat — not saved") }, onClick = { menuOpen = false; privateChat = !privateChat })
                    }
                }
                BqHeaderIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "Settings",
                    onClick = openSettings
                )
            }
            DropdownMenu(expanded = textModelMenuOpen, onDismissRequest = { textModelMenuOpen = false }) {
                DropdownMenuItem(text = { Text("Use server default") }, onClick = { selectedTextModelId = ""; textModelMenuOpen = false })
                publishedAiModels.filter { it.optString("feature") == "text-chat" && it.optBoolean("active") && it.optBoolean("published") }.forEach { model ->
                    DropdownMenuItem(text = { Text(model.optString("name") + " · " + model.optInt("creditCost") + " credits/request · $" + "%.4f".format(model.optDouble("priceUsd")) + " reference" + if (model.optBoolean("isDefault")) " · Default" else "") }, onClick = { selectedTextModelId = model.optString("id"); textModelMenuOpen = false })
                }
            }
            DropdownMenu(expanded = imageModelMenuOpen, onDismissRequest = { imageModelMenuOpen = false }) {
                DropdownMenuItem(text = { Text("Use server default") }, onClick = { selectedImageModelId = ""; imageModelMenuOpen = false })
                publishedAiModels.filter { it.optString("feature") == "image-generation" && it.optBoolean("active") && it.optBoolean("published") }.forEach { model ->
                    DropdownMenuItem(text = { Text(model.optString("name") + " · " + model.optInt("creditCost") + " credits/request · $" + "%.4f".format(model.optDouble("priceUsd")) + " reference" + if (model.optBoolean("isDefault")) " · Default" else "") }, onClick = { selectedImageModelId = model.optString("id"); imageModelMenuOpen = false })
                }
            }
            creditNotice?.let { notice ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (creditsExhausted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BqLogo(28.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (creditsExhausted) "Credits used up" else "Credit balance notice", fontWeight = FontWeight.Bold)
                            Text(notice, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = openPlans) { Text("Buy credits") }
                    }
                }
            }
            if (privateChat) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BqLogo(20.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Private Chat • not saved to History", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
            if (ADVANCED_APP_FEATURES.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().height(34.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("AI", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    LazyRow(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 1.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedAdvanced == null,
                                onClick = { selectedAdvanced = null },
                                label = { Text("Main", maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(14.dp)) }
                            )
                        }
                        items(ADVANCED_APP_FEATURES) { feature ->
                            FilterChip(
                                selected = selectedAdvanced?.id == feature.id,
                                onClick = { selectedAdvanced = if (selectedAdvanced?.id == feature.id) null else feature },
                                label = { Text(feature.title, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.Bolt, null, Modifier.size(14.dp)) }
                            )
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(28.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    Modifier.padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "General",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Ready for your next request",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
            micError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                        .clickable(onClick = openFeatures)
                                    .padding(horizontal = 18.dp)
                            ) {
                                BqLogo(76.dp)
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
                                    shadowElevation = 8.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                                        Text("AI Features", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
                items(messages) { m ->
                    Box(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(if (m.mine) 0.88f else 1f)
                                .align(if (m.mine) Alignment.CenterEnd else Alignment.CenterStart),
                            horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start
                        ) {
                        if (!m.mine && m.text == "Generating image…") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                BqGeneratingIndicator()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "Creating your image…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    "This can take a moment. You can keep this chat open while it finishes.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
                                SelectionContainer {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (m.text.isNotBlank()) {
                                            if (m.mine || (streamJob != null && messages.lastOrNull()?.id == m.id)) {
                                                Text(m.text, color = MaterialTheme.colorScheme.onSurface)
                                            } else {
                                                MarkdownText(m.text)
                                            }
                                        }
                                        m.attachments.forEach { attachment ->
                                            ChatAttachmentPreview(attachment) {
                                                if (!attachment.imageBase64.isNullOrBlank()) {
                                                    selectedImageBase64 = attachment.imageBase64
                                                    selectedImageMimeType = attachment.mimeType
                                                    selectedImagePrompt = m.prompt ?: messages.getOrNull((messages.indexOf(m) - 1).coerceAtLeast(0))?.text.orEmpty()
                                                    selectedImageMessageId = m.id
                                                    showImageViewer = true
                                                }
                                            }
                                        }
                                        // Prefer the in-memory image during this session; use the durable URL
                                        // only when restoring history or when no inline image bytes exist.
                                        if (m.attachments.none { !it.imageBase64.isNullOrBlank() }) {
                                            m.imageUrl?.let { url -> ChatRemoteImage(url, "Generated image") }
                                        }
                                    }
                                }
                            }
                        }
                        if (!m.mine) Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            BqLogo(20.dp)
                            if (m.attachments.any { !it.imageBase64.isNullOrBlank() } || !m.imageUrl.isNullOrBlank()) {
                                IconButton(
                                    modifier = Modifier.size(36.dp),
                                    enabled = !imageActionLoading,
                                    onClick = {
                                        val image = m.attachments.firstOrNull { !it.imageBase64.isNullOrBlank() }
                                        selectedImageMimeType = image?.mimeType ?: "image/png"
                                        selectedImagePrompt = m.prompt ?: messages.getOrNull((messages.indexOf(m) - 1).coerceAtLeast(0))?.text.orEmpty()
                                        selectedImageMessageId = m.id
                                        if (!image?.imageBase64.isNullOrBlank()) {
                                            selectedImageBase64 = image?.imageBase64
                                            showImageActions = true
                                        } else {
                                            val savedUrl = m.imageUrl.orEmpty()
                                            if (!savedUrl.startsWith("https://", ignoreCase = true)) {
                                                error = "This saved image has no secure download link."
                                            } else {
                                                imageActionLoading = true
                                                scope.launch(Dispatchers.IO) {
                                                    val loaded = runCatching { downloadGeneratedImageBase64(savedUrl) }
                                                    withContext(Dispatchers.Main) {
                                                        loaded.onSuccess {
                                                            selectedImageBase64 = it
                                                            showImageActions = true
                                                        }.onFailure {
                                                            error = "The saved image could not be loaded. Check your connection and try again."
                                                        }
                                                        imageActionLoading = false
                                                    }
                                                }
                                            }
                                        }
                                    }
                                ) { Icon(Icons.Default.MoreVert, contentDescription = if (imageActionLoading) "Loading saved image" else "Image actions") }
                            }
                            IconButton(modifier = Modifier.size(36.dp), onClick = { clipboard.setText(AnnotatedString(m.text)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy response", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(modifier = Modifier.size(36.dp), onClick = { shareText(context, m.text) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share response", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            if (voiceRepliesEnabled) {
                                IconButton(modifier = Modifier.size(36.dp), onClick = { speakTextWhenReady(context, m.text) { error = it } }) {
                                    Icon(Icons.Default.VolumeUp, contentDescription = "Read response aloud", tint = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            IconButton(modifier = Modifier.size(36.dp), onClick = { message = m.prompt ?: m.text }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Resend prompt", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(modifier = Modifier.size(36.dp), onClick = {
                                scope.launch {
                                    runCatching { WorkspaceApiClient().reportMessage(m.text, "report", m.id, "report") }
                                        .onSuccess { error = "Report submitted for review." }
                                        .onFailure { error = safeUserFacingIssue(it, "Your report could not be submitted. Please try again shortly.") }
                                }
                            }) {
                                Icon(Icons.Default.Flag, contentDescription = "Report response", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(modifier = Modifier.size(36.dp), onClick = {
                                scope.launch {
                                    runCatching { WorkspaceApiClient().reportMessage(m.text, "response", m.id, "up") }
                                        .onSuccess { error = "Thanks for the feedback." }
                                        .onFailure { error = safeUserFacingIssue(it, "Your feedback could not be submitted. Please try again shortly.") }
                                }
                            }) {
                                Icon(Icons.Default.ThumbUp, contentDescription = "Helpful response", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(modifier = Modifier.size(36.dp), onClick = { feedbackTarget = m }) {
                                Icon(Icons.Default.ThumbDown, contentDescription = "Not helpful", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            if (m.prompt != null) {
                                IconButton(modifier = Modifier.size(36.dp), onClick = {
                                    message = m.prompt
                                    val text = m.prompt
                                    if (FirebaseAuth.getInstance().currentUser != null && !busy) {
                                        sendAuthenticatedMessage(text)
                                    }
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Regenerate response", tint = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
                }
                if (busy) item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let { message ->
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BqLogo(34.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("BQ Sqwad AI", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
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
val composerShape = RoundedCornerShape(26.dp)
            val pulse by rememberInfiniteTransition(label = "composerPulse").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "composerPulseValue"
            )
            val composerBlue = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f + (pulse * 0.45f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .border(
                            2.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f + pulse * 0.25f),
                            CircleShape
                        ),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 18.dp
                ) {
                    IconButton(
                        onClick = openFeatures,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Open AI Features",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, composerBlue, composerShape),
                    shape = composerShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 10.dp
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(
                            modifier = Modifier.width(44.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            IconButton(
                                onClick = {
                                    micError = null
                                    if (isListening) {
                                        speechRecognizer?.stopListening()
                                    } else {
                                        speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                enabled = !busy && !creditsExhausted
                            ) {
                                Icon(
                                    if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = if (isListening) "Stop voice input" else "Start voice input",
                                    tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { if (!creditsExhausted) picker.launch(arrayOf("*/*")) }, enabled = !creditsExhausted) {
                                Icon(
                                    Icons.Default.AttachFile,
                                    contentDescription = "Upload attachment",
                                    tint = Color(0xFF1457FF)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = message,
                            onValueChange = { value ->
                                micError = null
                                message = value
                            },
                            enabled = !creditsExhausted,
                            placeholder = {
                                Text(
                                    if (creditsExhausted) "Credits used up — buy credits to continue" else "Message BQ Sqwad AI",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .onFocusChanged { state ->
                                    if (state.isFocused) keyboardController?.show()
                                },
                            minLines = 1,
                            maxLines = 5,
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent,
                                errorBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent
                            )
                        )

                        IconButton(
                            enabled = busy || (!creditsExhausted && (message.isNotBlank() || attachments.isNotEmpty())),
                            onClick = {
                                if (busy) {
                                    streamJob?.cancel()
                                    return@IconButton
                                }
                                val text = message.trim()
                                if (FirebaseAuth.getInstance().currentUser == null) {
                                    showAuth = true
                                    return@IconButton
                                }
                                sendAuthenticatedMessage(text)
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                if (busy) Icons.Default.Stop else Icons.Default.Send,
                                contentDescription = if (busy) "Stop generating" else "Send",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                    }
                }
            }
        }
        if (showImageViewer && selectedImageBase64 != null) {
            val encodedImage = selectedImageBase64.orEmpty()
            val fullImage by produceState<ImageBitmap?>(initialValue = null, key1 = encodedImage) {
                value = withContext(Dispatchers.IO) { runCatching { val bytes = decodeImageBytes(encodedImage); BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
            }
            Dialog(onDismissRequest = { showImageViewer = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BqLogo(38.dp)
                            Column(Modifier.weight(1f)) { Text("BQ Sqwad AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("Generated image", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = { showImageViewer = false }) { Icon(Icons.Default.Close, contentDescription = "Close full-screen image") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { if (fullImage != null) Image(bitmap = fullImage!!, contentDescription = "Generated image full screen", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) else CircularProgressIndicator() }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(modifier = Modifier.weight(1f), onClick = { runCatching { saveGeneratedImage(context, encodedImage) }.onSuccess { error = "Image saved to Pictures/BQ Sqwad AI." }.onFailure { error = it.message ?: "Could not save image." } }) { Icon(Icons.Default.Download, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Save") }
                            OutlinedButton(modifier = Modifier.weight(1f), onClick = { runCatching { shareGeneratedImage(context, encodedImage) }.onFailure { error = it.message ?: "Could not share image." } }) { Icon(Icons.Default.Share, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Share") }
                            Button(modifier = Modifier.weight(1f), onClick = { showImageViewer = false; showImageActions = true }) { Icon(Icons.Default.MoreVert, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("More") }
                        }
                    }
                }
            }
        }
        if (showImageActions && selectedImageBase64 != null) {
            AlertDialog(onDismissRequest = { showImageActions = false }, title = { Text("Image options") }, text = { Text("Open full screen, save or share this image, edit it with instructions, or create another output size.") },
                confirmButton = { TextButton(onClick = { showImageActions = false; showImageViewer = true }) { Text("Full screen") } },
                dismissButton = { Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(onClick = { runCatching { saveGeneratedImage(context, selectedImageBase64.orEmpty()) }.onSuccess { error = "Image saved to Pictures/BQ Sqwad AI." }.onFailure { error = it.message ?: "Could not save image." }; showImageActions = false }) { Text("Save") }
                    TextButton(onClick = { runCatching { shareGeneratedImage(context, selectedImageBase64.orEmpty()) }.onFailure { error = it.message ?: "Could not share image." }; showImageActions = false }) { Text("Share") }
                    TextButton(onClick = { imageEditPrompt = ""; showImageActions = false; showImageEdit = true }) { Text("Edit") }
                    TextButton(onClick = {
                        val prompt = selectedImagePrompt.ifBlank { "Create a new version of this image" }
                        val targetId = selectedImageMessageId
                        showImageActions = false
                        busy = true
                        statusText = "Generating image…"
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { GenerationApiClient().generateImage(prompt, modelRegistryId = selectedImageModelId.takeIf { it.isNotBlank() }) }
                            val durableUrl = result.getOrNull()?.let { generated ->
                                generated.imageBase64?.let { runCatching { uploadGeneratedImage(it) }.getOrNull() }
                                    ?: generated.url.takeIf { it.startsWith("https://", ignoreCase = true) }
                            }
                            withContext(Dispatchers.Main) {
                                result.onSuccess { generated ->
                                    val index = messages.indexOfFirst { it.id == targetId }
                                    if (index >= 0) messages[index] = messages[index].copy(
                                        text = "Image regenerated.", prompt = prompt,
                                        attachments = generated.imageBase64?.let { listOf(ChatMessageAttachment("Regenerated image.png", "image/png", it)) } ?: emptyList(),
                                        imageUrl = durableUrl
                                    )
                                    selectedImageBase64 = generated.imageBase64
                                    selectedImageMimeType = "image/png"
                                    error = if (durableUrl == null) "The image was regenerated, but secure storage is unavailable; it may not appear when you reopen this chat." else null
                                    if (shouldAutoSaveHistory()) runCatching {
                                        val snapshot = messages.filterNot { it.id in privateMessageIds }.map { item -> HistoryMessage(item.mine, item.text, item.id, item.prompt, item.attachments.map { it.mimeType + "|" + it.name }, item.imageUrl) }
                                        withContext(Dispatchers.IO) { HistoryApiClient().save(historyId, prompt, snapshot) }
                                    }.onSuccess { savedId -> historyId = savedId }
                                        .onFailure { error = safeUserFacingIssue(it, "The image was regenerated, but this conversation could not be saved.") }
                                }.onFailure { error = safeUserFacingIssue(it, "BQ Sqwad AI could not regenerate the image. Please try again.") }
                                runCatching { withContext(Dispatchers.IO) { WorkspaceApiClient().credits() } }
                                    .onSuccess { applyCreditSnapshot(it) }
                                busy = false
                                statusText = "Ready"
                            }
                        }
                    }) { Text("Regenerate") }
                    TextButton(onClick = { showImageActions = false; showImageResize = true }) { Text("Resize") }
                } }
            )
        }
        if (showImageEdit) {
            AlertDialog(onDismissRequest = { showImageEdit = false }, title = { Text("Edit image with AI") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Describe what should change in the current image."); OutlinedTextField(value = imageEditPrompt, onValueChange = { imageEditPrompt = it }, label = { Text("Describe the edit") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth()) } },
                confirmButton = { Button(enabled = imageEditPrompt.isNotBlank() && !busy, onClick = {
                    val image = selectedImageBase64.orEmpty(); val prompt = imageEditPrompt.trim(); val targetId = selectedImageMessageId; showImageEdit = false; busy = true; statusText = "Editing image…"
                    scope.launch {
                        runCatching { GenerationApiClient().editImage(prompt, image, selectedImageSize, modelRegistryId = selectedImageModelId.takeIf { it.isNotBlank() }, mimeType = selectedImageMimeType) }
                            .onSuccess { generated ->
                                val durableImageUrl = generated.imageBase64?.let {
                                    runCatching { withContext(Dispatchers.IO) { uploadGeneratedImage(it) } }.getOrNull()
                                } ?: generated.url.takeIf { it.startsWith("https://", ignoreCase = true) }
                                val idx = messages.indexOfFirst { it.id == targetId }
                                if (idx >= 0) messages[idx] = messages[idx].copy(
                                    text = "Image edited.", prompt = prompt,
                                    attachments = generated.imageBase64?.let { listOf(ChatMessageAttachment("Edited image.png", "image/png", it)) } ?: emptyList(),
                                    imageUrl = durableImageUrl
                                )
                                selectedImageBase64 = generated.imageBase64
                                selectedImageMimeType = "image/png"
                                selectedImagePrompt = prompt
                                error = if (generated.imageBase64 != null && durableImageUrl == null)
                                    "Image edit completed, but secure image storage is unavailable; it may not appear when you reopen this chat."
                                else "Image edit completed."
                                if (shouldAutoSaveHistory()) {
                                    runCatching {
                                        val snapshot = messages.filterNot { it.id in privateMessageIds }.map { m ->
                                            HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name }, m.imageUrl)
                                        }
                                        withContext(Dispatchers.IO) { HistoryApiClient().save(historyId, prompt, snapshot) }
                                    }.onSuccess { savedId -> historyId = savedId }
                                        .onFailure { error = safeUserFacingIssue(it, "Image edit completed, but this conversation could not be saved.") }
                                }
                            }
                            .onFailure { error = safeUserFacingIssue(it, "The image could not be edited. Please try again.") }
                        runCatching { withContext(Dispatchers.IO) { WorkspaceApiClient().credits() } }
                            .onSuccess { applyCreditSnapshot(it) }
                        busy = false; statusText = "Ready"
                    }
                }) { Text("Apply edit") } },
                dismissButton = { TextButton(onClick = { showImageEdit = false }) { Text("Cancel") } }
            )
        }
        if (showImageResize) {
            AlertDialog(onDismissRequest = { showImageResize = false }, title = { Text("Image size") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Choose an output size. This creates a new version using the original prompt."); listOf("1024x1024" to "Square · 1024 × 1024", "1536x1024" to "Landscape · 1536 × 1024", "1024x1536" to "Portrait · 1024 × 1536").forEach { option -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = selectedImageSize == option.first, onClick = { selectedImageSize = option.first }); Text(option.second) } } } },
                confirmButton = { Button(enabled = !busy, onClick = {
                    val prompt = selectedImagePrompt.ifBlank { "Create an image" }; val targetId = selectedImageMessageId; showImageResize = false; busy = true; statusText = "Generating image…"
                    scope.launch {
                        runCatching { GenerationApiClient().generateImage(prompt, selectedImageSize, modelRegistryId = selectedImageModelId.takeIf { it.isNotBlank() }) }
                            .onSuccess { generated ->
                                val durableImageUrl = generated.imageBase64?.let {
                                    runCatching { withContext(Dispatchers.IO) { uploadGeneratedImage(it) } }.getOrNull()
                                } ?: generated.url.takeIf { it.startsWith("https://", ignoreCase = true) }
                                val idx = messages.indexOfFirst { it.id == targetId }
                                if (idx >= 0) messages[idx] = messages[idx].copy(
                                    text = "Image resized.", prompt = prompt,
                                    attachments = generated.imageBase64?.let { listOf(ChatMessageAttachment("Resized image.png", "image/png", it)) } ?: emptyList(),
                                    imageUrl = durableImageUrl
                                )
                                selectedImageBase64 = generated.imageBase64
                                selectedImageMimeType = "image/png"
                                selectedImagePrompt = prompt
                                error = if (generated.imageBase64 != null && durableImageUrl == null)
                                    "New image size generated, but secure image storage is unavailable; it may not appear when you reopen this chat."
                                else "New image size generated."
                                if (shouldAutoSaveHistory()) {
                                    runCatching {
                                        val snapshot = messages.filterNot { it.id in privateMessageIds }.map { m ->
                                            HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name }, m.imageUrl)
                                        }
                                        withContext(Dispatchers.IO) { HistoryApiClient().save(historyId, prompt, snapshot) }
                                    }.onSuccess { savedId -> historyId = savedId }
                                        .onFailure { error = safeUserFacingIssue(it, "New image size generated, but this conversation could not be saved.") }
                                }
                            }
                            .onFailure { error = safeUserFacingIssue(it, "The new image size could not be generated.") }
                        runCatching { withContext(Dispatchers.IO) { WorkspaceApiClient().credits() } }
                            .onSuccess { applyCreditSnapshot(it) }
                        busy = false
                        statusText = "Ready"
                    }
                }) { Text("Create new size") } },
                dismissButton = { TextButton(onClick = { showImageResize = false }) { Text("Cancel") } }
            )
        }
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
                        onAuthenticated = { showAuth = false }
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
private fun FeatureInfoScreen(w: Workspace, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Learn more • ${w.title}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(w.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(w.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { SurfaceCard("What this feature does", featureLongDescription(w)) }
            item { SurfaceCard("How it works", featureHowItWorks(w)) }
            item { SurfaceCard("Permissions & controls", featurePermissions(w)) }
            item { SurfaceCard("Privacy", "This feature has a dedicated privacy section: ${featurePrivacyHeading(w)}. Use the Privacy button on the feature screen to open that section directly.") }
            item {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Return to ${w.title}")
                }
            }
        }
    }
}

private fun featureLongDescription(w: Workspace): String = when (w) {
    Workspace.VIDEO -> "AI video creation and video-focused workflows using the configured BQ Sqwad AI capabilities."
    Workspace.CHAT -> "General BQ Sqwad AI conversation with streaming responses, attachments, speech-to-text, response actions, history saving and account-gated protected requests."
    Workspace.SEARCH -> "Search the web through the configured backend, with source-aware results and direct links that open in the device browser."
    Workspace.TRUTH_GUARD -> "Review claims against web sources and separate evidence from unsupported conclusions."
    Workspace.FRONTIER_LAB -> "Explore advanced AI capabilities using the authenticated, credit-controlled AI runtime."
    Workspace.DATA_ANALYSIS -> "Analyze structured information and turn findings into readable reports."
    Workspace.CAMERA -> "Use the camera or supported screen-sharing flow for live visual analysis. Android permission indicators and foreground-service controls remain visible during active sessions."
    Workspace.VOICE -> "Have a real-time voice conversation with interruption support. The live voice session uses the configured realtime backend and microphone permission."
    Workspace.PERSONAL -> "A personal intelligence workspace for planning, organization, goals and permission-based memory backed by the account."
    Workspace.LIFE -> "A dedicated life-planning workspace for routines, reminders, practical guidance and structured assistance."
    Workspace.COMPANION -> "A dedicated companion workspace with personality, memory and check-in controls governed by the account and feature configuration."
    Workspace.LEARNING -> "Learning Studio provides subject tutoring, lessons, homework help, revision and exam-preparation workflows."
    Workspace.BUSINESS -> "Business AI connects the business workspace, assistants, knowledge, contacts, leads, pipelines, inbox, automation, channels, advanced AI and enterprise configuration."
    Workspace.ORGANIZATION_STUDIO -> "Organization AI / Studio provides professional organization intelligence, production coordination and the connected organization tools exposed by the backend."
    Workspace.ADMIN -> "Admin Control Center manages server-side configuration, providers, billing/catalog controls, security and testing. Administrative secrets remain server-side."
}

private fun featureHowItWorks(w: Workspace): String = when (w) {
    Workspace.CHAT -> "The app sends authenticated protected requests through the existing backend, streams the response into the chat, and saves the conversation to history when possible."
    Workspace.VIDEO -> "Video generation requests are sent to the configured backend render capability; generated media and job status remain account-scoped."
    Workspace.SEARCH -> "The app sends the query to the backend search route. Search-provider credentials stay server-side; returned sources remain actionable."
    Workspace.TRUTH_GUARD -> "TruthGuard can search the web first, then send the evidence to its configured AI capability for claim analysis."
    Workspace.FRONTIER_LAB -> "Frontier Lab uses the configured capability route and can attach web-search evidence before the AI response."
    Workspace.DATA_ANALYSIS -> "Data Analysis uses the server capability route and central credit controls."
    Workspace.CAMERA -> "Android starts the camera or screen foreground service, captures supported frames, and sends analysis requests through the configured backend."
    Workspace.VOICE -> "The app starts the foreground voice session, obtains the server-authorized realtime session, and exchanges audio through the existing realtime client."
    Workspace.PERSONAL -> "Workspace requests are routed with the selected capability and account context; memory is loaded or changed only through the account APIs."
    Workspace.LIFE -> "Requests are routed to the life capability and returned to the workspace result surface."
    Workspace.COMPANION -> "Companion requests use the companion workspace and its configured account features, including available memory and check-in controls."
    Workspace.LEARNING -> "Learning requests are routed to the learning capability and use the server catalog and configuration for availability and limits."
    Workspace.BUSINESS -> "Business screens call the workspace APIs for records, automation, social accounts, integrations and enterprise settings; provider credentials remain encrypted server-side."
    Workspace.ORGANIZATION_STUDIO -> "Organization requests are routed to the organization capability and its connected backend services."
    Workspace.ADMIN -> "Admin actions call protected server endpoints after server-side authorization. Sensitive provider credentials and security material are never embedded in the APK."
}

private fun featurePermissions(w: Workspace): String = when (w) {
    Workspace.CHAT -> "No camera or microphone permission is required just to read or type chat. Speech-to-text requests microphone access through Android when used; attachments use Android's document picker."
    Workspace.VIDEO -> "Video generation does not require camera or microphone permission unless the user explicitly selects a capture or media input feature."
    Workspace.SEARCH -> "No special device permission is required for ordinary web search."
    Workspace.TRUTH_GUARD -> "No special device permission is required; web-search credentials remain server-side."
    Workspace.FRONTIER_LAB -> "No special device permission is required unless an enabled experiment explicitly requests one."
    Workspace.DATA_ANALYSIS -> "No special device permission is required for ordinary structured-data analysis."
    Workspace.CAMERA -> "Camera permission is required for camera capture. Screen analysis separately uses Android's MediaProjection consent flow."
    Workspace.VOICE -> "Microphone permission is required while live voice is active. Android displays the system microphone indicator during use."
    Workspace.PERSONAL -> "Memory is account-controlled. The user can turn personal memory on or off where exposed by the workspace."
    Workspace.LIFE -> "Ordinary requests do not require special device permissions unless a connected tool explicitly requests one."
    Workspace.COMPANION -> "Memory and personalization are account-controlled; optional capabilities use only the permissions needed for that capability."
    Workspace.LEARNING -> "Ordinary learning requests do not require special device permissions."
    Workspace.BUSINESS -> "Connected channels and integrations use account authorization and provider permissions configured for that integration."
    Workspace.ORGANIZATION_STUDIO -> "Connected production or organization tools request only the permissions required for the selected operation."
    Workspace.ADMIN -> "Administrative operations require server-side authorization; secrets are managed on the server rather than exposed to end users."
}

private fun featurePrivacyHeading(w: Workspace): String = when (w) {
    Workspace.CHAT -> "Main Chat"
    Workspace.VIDEO -> "Video Generation"
    Workspace.SEARCH -> "Web Search"
    Workspace.TRUTH_GUARD -> "TruthGuard AI"
    Workspace.FRONTIER_LAB -> "Frontier Lab"
    Workspace.DATA_ANALYSIS -> "Data Analysis"
    Workspace.CAMERA -> "AI Live Camera"
    Workspace.VOICE -> "Live Voice AI"
    Workspace.PERSONAL -> "Personal Intelligence"
    Workspace.LIFE -> "Life Matters"
    Workspace.COMPANION -> "AI Companion"
    Workspace.LEARNING -> "Learning Studio"
    Workspace.BUSINESS -> "Business AI"
    Workspace.ORGANIZATION_STUDIO -> "Organization AI / Studio"
    Workspace.ADMIN -> "Admin Control Center"
}

private data class AppFeature(val id: String, val title: String, val description: String, val capability: String, val webSearch: Boolean = false)

private val PRIMARY_ADVANCED_FEATURES = listOf(
    AppFeature("codron", "Codron AI", "AI coding, repository understanding, debugging and controlled code changes.", "codron"),
    AppFeature("deep_research", "Deep Research", "Multi-step research with web evidence, source collection and report generation.", "deep_research", true),
    AppFeature("knowledge_graphs", "Knowledge Graphs", "Connect people, facts, documents and relationships into searchable knowledge.", "knowledge_graphs", true),
    AppFeature("semantic_file_search", "Semantic File Search", "Find relevant information across supported uploaded files by meaning.", "semantic_file_search"),
    AppFeature("conversation_intelligence", "Conversation Intelligence", "Extract important facts, topics, actions and summaries from conversations.", "conversation_intelligence"),
    AppFeature("project_workspaces", "Project Workspaces", "Keep related conversations, files, instructions and memory together.", "project_workspaces"),
    AppFeature("ai_generated_reports", "AI-Generated Reports", "Turn conversations and structured data into organized reports and exports.", "ai_generated_reports"),
    AppFeature("scheduled_ai_tasks", "Scheduled AI Tasks", "Run supported AI tasks on a schedule and keep execution history.", "scheduled_ai_tasks")
)

private val FEATURE_DISPLAY_NAMES = mapOf(
    "ai_task_planner" to "AI Task Planner",
    "agent_tool_use" to "Agent Tool Use",
    "agent_sandbox" to "Agent Sandbox",
    "ai_workflow_builder" to "AI Workflow Builder",
    "ai_monitoring_agents" to "AI Monitoring Agents",
    "multimodal_images" to "Multimodal Images",
    "multifile_reasoning" to "Multi-File Reasoning",
    "document_analysis" to "Document Analysis",
    "audio_analysis" to "Audio Analysis",
    "video_analysis" to "Video Analysis",
    "response_confidence_context" to "Response Confidence",
    "source_verification" to "Source Verification",
    "custom_ai_assistants" to "Custom AI Assistants",
    "business_ai_employee" to "Business AI Employees",
    "agent_to_agent_workflows" to "Agent-to-Agent Workflows",
    "human_approval_checkpoints" to "Human Approval Checkpoints",
    "action_preview" to "Action Preview",
    "permission_center" to "Permission Center",
    "privacy_dashboard" to "Privacy Dashboard",
    "ai_activity_timeline" to "AI Activity Timeline",
    "undoable_ai_actions" to "Undoable AI Actions",
    "secure_temporary_file_vault" to "Secure Temporary File Vault",
    "large_file_resumable_uploads" to "Resumable Large-File Uploads",
    "background_generation_jobs" to "Background Generation Jobs",
    "generation_queue" to "Generation Queue",
    "usage_forecasting" to "Usage Forecasting",
    "admin_experimentation" to "AI Experimentation",
    "feature_rollout_percentages" to "Feature Rollouts",
    "emergency_kill_switches" to "Emergency Kill Switches",
    "provider_health_checks" to "Provider Health",
    "ai_incident_replay" to "AI Incident Replay",
    "model_version_rollback" to "Model Version Rollback",
    "security_event_monitoring" to "Security Event Monitoring",
    "ai_abuse_risk_detection" to "AI Abuse Risk Detection",
    "encrypted_local_sensitive_storage" to "Encrypted Local Storage",
    "passkey_auth" to "Passkeys",
    "account_session_device_management" to "Sessions & Devices",
    "data_export_account_deletion" to "Data Export & Account Deletion",
    "accessibility_ai_controls" to "Accessibility AI Controls",
    "multilingual_ui_voice" to "Multilingual UI & Voice",
    "cross_device_sync" to "Cross-Device Sync",
    "admin_capability_marketplace" to "Capability Marketplace",
    "ai_orchestration" to "AI Orchestration",
    "model_routing" to "Model Routing",
    "provider_failover" to "Provider Failover",
    "personal_memory" to "Personal Memory",
    "project_memory" to "Project Memory",
    "business_memory" to "Business Memory",
    "device_intelligence" to "Device Intelligence",
    "calendar_actions" to "Calendar Actions",
    "reminder_actions" to "Reminder Actions",
    "location_assistance" to "Location Assistance",
    "screen_awareness" to "Screen Awareness",
    "media_controls" to "Media Controls",
    "web_research" to "Web Research",
    "research_export" to "Research Export",
    "live_camera_ocr" to "Live Camera OCR",
    "live_camera_scene_understanding" to "Live Camera Scene Understanding",
    "live_voice_barge_in" to "Live Voice Barge-In",
    "voice_activity_detection" to "Voice Activity Detection",
    "learning_quizzes" to "Learning Quizzes",
    "document_to_course" to "Document to Course",
    "business_crm" to "Business CRM",
    "business_workflows" to "Business Workflows",
    "business_analytics" to "Business Analytics",
    "business_knowledge" to "Business Knowledge",
    "business_agents" to "Business Agents",
    "business_channels" to "Business Channels",
    "business_automation" to "Business Automation",
    "agent_training" to "Agent Training",
    "agent_evaluation" to "Agent Evaluation",
    "agent_versioning" to "Agent Versioning",
    "agent_rollback" to "Agent Rollback",
    "notifications" to "Notifications",
    "referrals" to "Referrals",
    "credit_reservation" to "Credit Reservation",
    "credit_reversal" to "Credit Reversal",
    "rate_limiting" to "Rate Limiting",
    "abuse_protection" to "Abuse Protection",
    "incident_system" to "Incident System",
    "ai_evaluation_center" to "AI Evaluation Center",
    "secure_file_pipeline" to "Secure File Pipeline",
    "offline_retry_queue" to "Offline Retry Queue"
)

private fun capabilityDisplayName(id: String): String =
    FEATURE_DISPLAY_NAMES[id] ?: id.split('_').joinToString(" ") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun capabilityDescription(id: String): String = when (id) {
    "web_research", "deep_research" -> "Research the web with source-aware AI evidence and saved results."
    "knowledge_graphs" -> "Build connected knowledge from people, facts, files and relationships."
    "semantic_file_search" -> "Find relevant information across supported files by meaning."
    "conversation_intelligence" -> "Extract topics, facts, actions and summaries from conversations."
    "project_workspaces" -> "Keep related conversations, files, instructions and memory together."
    "data_analysis_workspace" -> "Analyze structured datasets with calculations, charts and AI reports."
    "ai_generated_reports" -> "Generate structured reports from AI conversations and analyzed data."
    "background_generation_jobs", "generation_queue" -> "Run longer AI generation work in a tracked background queue."
    "cross_device_sync" -> "Keep supported AI data synchronized across signed-in devices."
    else -> "Connected BQ Sqwad AI capability controlled by the server-side feature catalog."
}

private val ADMIN_ONLY_CAPABILITIES = setOf(
    "admin_experimentation", "feature_rollout_percentages", "emergency_kill_switches",
    "provider_health_checks", "ai_incident_replay", "model_version_rollback",
    "admin_capability_marketplace", "security_event_monitoring", "ai_abuse_risk_detection",
    "rate_limiting", "abuse_protection", "incident_system", "ai_evaluation_center",
    "ai_evaluation_laboratory", "provider_cost_optimizer", "automatic_quality_fallback",
    "credit_reservation", "credit_reversal", "secure_file_pipeline", "offline_retry_queue",
    "model_routing", "provider_failover", "ai_orchestration"
)

private val ADVANCED_APP_FEATURES: List<AppFeature>
    get() = NativeFeatureRegistry.advanced.filterNot { it.id in ADMIN_ONLY_CAPABILITIES }.map { entry ->
        AppFeature(
            id = entry.id,
            title = entry.title,
            description = entry.description,
            capability = entry.id,
            webSearch = entry.webSearch
        )
    }

private fun orderedDrawerWorkspaces(order: List<String>, enabled: Map<String, Boolean>, adminAuthorized: Boolean): List<Workspace> {
    val all = Workspace.values().filter { it != Workspace.CHAT && it != Workspace.SEARCH && (it != Workspace.ADMIN || adminAuthorized) }
    val byName = all.associateBy { it.name }
    val configured = order.mapNotNull { id -> byName[id] }.filter { enabled[it.name] != false }
    val remaining = all.filter { it.name !in order && enabled[it.name] != false }
    return configured + remaining
}

@Composable
private fun BqGeneratingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bqGenerating")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bqGeneratingPulse"
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bqGeneratingSweep"
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 4.dp
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                        rotationZ = sweep
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                BqLogo(38.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("BQ Sqwad AI is creating", fontWeight = FontWeight.Bold)
                Text("Working on your request…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FeatureHub(
    onBack: () -> Unit,
    onPlans: () -> Unit,
    onHistory: () -> Unit,
    onWorkspace: (Workspace) -> Unit,
    onAdvancedFeature: (AppFeature) -> Unit
) {
    val visibleWorkspaces = Workspace.values().filter { it != Workspace.CHAT && it != Workspace.SEARCH && it != Workspace.ADMIN }
    val primary = listOf(
        Workspace.LEARNING,
        Workspace.TRUTH_GUARD,
        Workspace.LIFE,
        Workspace.COMPANION,
        Workspace.BUSINESS,
        Workspace.CAMERA,
        Workspace.VOICE,
        Workspace.DATA_ANALYSIS
    ).filter { visibleWorkspaces.contains(it) }
    val more = visibleWorkspaces.filter { it !in primary }
    val advancedFeatures = ADVANCED_APP_FEATURES

    fun iconFor(w: Workspace) = when (w) {
        Workspace.LEARNING -> Icons.Default.School
        Workspace.TRUTH_GUARD -> Icons.Default.Verified
        Workspace.LIFE -> Icons.Default.Favorite
        Workspace.COMPANION -> Icons.Default.Face
        Workspace.BUSINESS -> Icons.Default.Business
        Workspace.CAMERA -> Icons.Default.CameraAlt
        Workspace.VOICE -> Icons.Default.Mic
        Workspace.DATA_ANALYSIS -> Icons.Default.Analytics
        Workspace.SEARCH -> Icons.Default.Search
        Workspace.FRONTIER_LAB -> Icons.Default.Science
        Workspace.PERSONAL -> Icons.Default.Psychology
        Workspace.VIDEO -> Icons.Default.VideoLibrary
        Workspace.ORGANIZATION_STUDIO -> Icons.Default.Dashboard
        else -> Icons.Default.AutoAwesome
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Features", fontWeight = FontWeight.Bold)
                        Text("Explore the AI tools at your fingertips", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onHistory) { Icon(Icons.Default.History, "History") }
                    IconButton(onClick = onPlans) { Icon(Icons.Default.CreditCard, "Plans and credits") }
                }
            )
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    tonalElevation = 3.dp
                ) {
                    Row(
                        Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        BqLogo(58.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("AI Features", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Learning, safer decisions, better living, stronger businesses and more.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Text("Featured AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            item {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 155.dp),
                    modifier = Modifier.fillMaxWidth().height(((primary.size + 1) / 2 * 150).dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = false
                ) {
                    gridItems(primary) { w ->
                        Card(
                            onClick = { onWorkspace(w) },
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(13.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(iconFor(w), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Text(w.title, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(w.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        }
                    }
                }
            }

            if (more.isNotEmpty()) {
                item { Text("More AI workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        more.forEach { w ->
                            Card(
                                onClick = { onWorkspace(w) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(15.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(iconFor(w), contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(w.title, fontWeight = FontWeight.Bold)
                                        Text(w.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            }
                        }
                    }
                }
            }

            if (advancedFeatures.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Advanced AI capabilities", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Browse registered capabilities. Actual availability depends on server configuration, plan access and provider support; this directory does not promise every capability is live.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(advancedFeatures, key = { it.id }) { feature ->
                    Card(
                        onClick = { onAdvancedFeature(feature) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(feature.title, fontWeight = FontWeight.SemiBold)
                                Text(feature.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = "Explore ${feature.title}")
                        }
                    }
                }
            }

            item {
                Card(
                    onClick = onPlans,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.CloudDone, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("Live feature catalogue", fontWeight = FontWeight.SemiBold)
                            Text("Open the live BQ Sqwad AI catalogue for plans, limits and availability.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = "Open live feature catalogue")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminOnlyWorkspace(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin Control Center") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null)
                }
            )
        }
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp)
        ) {
            AdminControlCenter()
        }
    }
}

@Composable
private fun WorkspaceScreen(
    w: Workspace,
    email: String,
    adminAuthorized: Boolean,
    onBack: () -> Unit,
    onLearnMore: () -> Unit,
    onPrivacy: () -> Unit,
    onSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenPlans: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (w == Workspace.BUSINESS) "Business AI" else w.title) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onLearnMore) {
                        Icon(Icons.Default.Info, contentDescription = "Learn more about ${w.title}")
                    }
                    IconButton(onClick = onPrivacy) {
                        Icon(Icons.Default.Lock, contentDescription = "${w.title} privacy")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "${w.title} settings")
                    }
                }
            )
        }
    ) { p ->
        if (w == Workspace.ADMIN) {
            if (!adminAuthorized) {
                AccessDenied()
                return@Scaffold
            }
            var allowed by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                allowed = runCatching { AdminAdvancedClient().list(); true }.getOrDefault(false)
            }
            when (allowed) {
                null -> Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                true -> Column(Modifier.padding(p).padding(16.dp).fillMaxSize()) {
                    AdminControlCenter()
                }
                false -> Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                    AccessDenied()
                }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(p)
                .then(if (w == Workspace.CAMERA) Modifier.fillMaxSize() else Modifier.padding(16.dp).fillMaxSize())
        ) {
            when (w) {
                Workspace.VIDEO -> VideoGenerationWorkspace()
                Workspace.SEARCH -> SearchWorkspace(onOpenSettings = onOpenAppSettings)
                Workspace.TRUTH_GUARD -> CompleteTruthGuardWorkspace(onOpenPlans = onOpenPlans, onOpenSettings = onOpenAppSettings)
                Workspace.FRONTIER_LAB -> FrontierLabWorkspace(onOpenPlans = onOpenPlans, onOpenSettings = onOpenAppSettings)
                Workspace.DATA_ANALYSIS -> DataAnalysisWorkspace()
                Workspace.CAMERA -> CameraWorkspace()
                Workspace.VOICE -> VoiceWorkspace()
                Workspace.PERSONAL -> PersonalIntelligenceWorkspace()
                Workspace.LIFE -> LifeMattersWorkspace(onOpenPlans = onOpenPlans)
                Workspace.COMPANION -> CompanionWorkspace(onOpenPlans = onOpenPlans)
                Workspace.LEARNING -> CompleteLearningStudioWorkspace(onOpenPlans = onOpenPlans)
                Workspace.BUSINESS -> BusinessWorkspaceScreen(onOpenPlans = onOpenPlans)
                Workspace.ORGANIZATION_STUDIO -> OrganizationStudioWorkspace()
                Workspace.ADMIN -> AdminControlCenter()
                Workspace.CHAT -> AiTaskWorkspace("Main Chat", "General AI conversation.")
            }
        }
    }
}

@Composable
private fun AdvancedFeatureScreen(feature: AppFeature, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val entry = NativeFeatureRegistry.find(feature.id)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(feature.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(feature.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(feature.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Requested capability: " + feature.capability,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Availability depends on server configuration, account access and any required external connection. A feature listing alone does not mean an external service is connected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "This feature uses the shared BQ Sqwad AI runtime, central credit controls and server-side availability. It is part of the native app feature registry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (entry?.webSearch == true) {
                SurfaceCard("Web Search", "Web evidence is available to this capability through the shared search service.")
            }
            if (entry?.credits != false) {
                SurfaceCard("Credits", "Usage is charged through the shared backend credit system. Pricing and limits follow the published BQ Sqwad AI catalog.")
            }
            AiTaskWorkspace(feature.title, feature.description, feature.capability, false, feature.webSearch, onOpenSettings = onOpenAppSettings)
        }
    }
}

@Composable
private fun FeatureSettingsScreen(workspace: Workspace, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val key = workspace.name.lowercase()
    val defaults = remember(workspace) {
        mapOf(
            "web_search" to (workspace == Workspace.SEARCH || workspace == Workspace.TRUTH_GUARD || workspace == Workspace.FRONTIER_LAB),
            "memory" to (workspace == Workspace.PERSONAL || workspace == Workspace.COMPANION),
            "notifications" to true,
            "history" to true,
            "auto_run" to false,
            "streaming" to true,
            "voice_responses" to (workspace == Workspace.VOICE || workspace == Workspace.COMPANION),
            "save_files" to true,
            "personalization" to (workspace == Workspace.PERSONAL || workspace == Workspace.COMPANION || workspace == Workspace.LIFE),
            "background_jobs" to (workspace == Workspace.VIDEO || workspace == Workspace.ORGANIZATION_STUDIO)
        )
    }
    var values by remember(key) { mutableStateOf(defaults) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun saveValue(setting: String, value: Boolean) {
        values = values + (setting to value)
        scope.launch {
            saving = true
            runCatching {
                WorkspaceApiClient().updateFeatureSettings(
                    key,
                    org.json.JSONObject().put(setting, value)
                )
            }.onFailure {
                error = it.message ?: "Setting could not be saved."
            }
            saving = false
        }
    }

    LaunchedEffect(key) {
        loading = true
        runCatching {
            WorkspaceApiClient().featureSettings()
        }.onSuccess { body ->
            val stored = body.optJSONObject("settings")?.optJSONObject(key)
            if (stored != null) {
                val next = defaults.toMutableMap()
                stored.keys().forEach { setting ->
                    if (stored.opt(setting) is Boolean) next[setting] = stored.optBoolean(setting)
                }
                values = next
            }
        }.onFailure {
            error = it.message ?: "Feature settings could not be loaded."
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workspace.title + " Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            )
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(workspace.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(workspace.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (loading) "Loading your saved settings…" else "Settings are saved to your account and loaded on your other signed-in devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            item { Text("AI behavior", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { FeatureSettingSwitch("Streaming responses", "Show supported AI responses as they arrive instead of waiting for the complete result.", values["streaming"] == true) { saveValue("streaming", it) } }
            item { FeatureSettingSwitch("Web Search", "Allow this workspace to use configured web-search evidence when supported.", values["web_search"] == true) { saveValue("web_search", it) } }
            item { FeatureSettingSwitch("Memory", "Use saved memory where this workspace supports it.", values["memory"] == true) { saveValue("memory", it) } }
            item { FeatureSettingSwitch("Personalization", "Use your saved preferences and personalization controls where supported.", values["personalization"] == true) { saveValue("personalization", it) } }
            item { Text("Data & history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { FeatureSettingSwitch("Save history", "Save completed workspace conversations to your account history.", values["history"] != false) { saveValue("history", it) } }
            item { FeatureSettingSwitch("Save generated files", "Keep supported generated files available in your account history or workspace.", values["save_files"] != false) { saveValue("save_files", it) } }
            item { Text("Notifications & automation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { FeatureSettingSwitch("Notifications", "Allow this workspace to surface supported job, plan and activity notifications.", values["notifications"] != false) { saveValue("notifications", it) } }
            item { FeatureSettingSwitch("Automatic runs", "Allow supported scheduled or background actions for this workspace.", values["auto_run"] == true) { saveValue("auto_run", it) } }
            item { FeatureSettingSwitch("Background jobs", "Allow supported long-running jobs to continue through the server job system.", values["background_jobs"] == true) { saveValue("background_jobs", it) } }
            if (workspace == Workspace.VOICE || workspace == Workspace.COMPANION) {
                item { Text("Voice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                item { FeatureSettingSwitch("Spoken responses", "Allow supported voice experiences to speak AI responses.", values["voice_responses"] == true) { saveValue("voice_responses", it) } }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item {
                Text(
                    "Camera, microphone, screen capture and other sensitive Android permissions are never silently enabled by these settings. Android permission prompts remain user-controlled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FeatureSettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun AiTaskWorkspace(
    title: String,
    description: String,
    capability: String = "chat",
    showMemory: Boolean = false,
    allowWebSearch: Boolean = false,
    onOpenSettings: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsPrefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    val webSearchEnabled = remember { settingsPrefs.getBoolean("web_search_enabled", true) }
    var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf("") }
    var memoryEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var useWebSearch by remember { mutableStateOf(allowWebSearch && settingsPrefs.getBoolean("web_search_enabled", true)) }
    var streamingResult by remember { mutableStateOf("") }

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

    val quickPrompts = when (title) {
        "TruthGuard AI" -> listOf("Check this claim", "Compare the strongest sources", "Find missing context")
        "Learning Studio" -> listOf("Teach me this topic", "Quiz me", "Build a study plan")
        "Life Matters" -> listOf("Help me plan today", "Organize my priorities", "Help me think through a decision")
        "Personal Intelligence" -> listOf("Review my goals", "Organize my plans", "Summarize what matters")
        "Frontier Lab" -> listOf("Explore this idea", "Design a prototype", "Analyze the trade-offs")
        else -> listOf("Explain this clearly", "Create a step-by-step plan", "Summarize the key points")
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(description, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text("Quick actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
            items(quickPrompts) { quick ->
                AssistChip(onClick = { prompt = quick }, label = { Text(quick) })
            }
        }
        OutlinedTextField(
            prompt,
            { prompt = it; error = null },
            label = { Text("What do you want to do?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = busy || prompt.isNotBlank(), onClick = {
                if (busy) {
                    activeJob?.cancel()
                    return@Button
                }
                if (useWebSearch && !webSearchEnabled) {
                    error = "Turn on Web Search in Settings before using web evidence."
                    return@Button
                }
                busy = true
                error = null
                result = ""
                streamingResult = ""
                activeJob = scope.launch {
                    try {
                        val requestText = if (useWebSearch) {
                            val search = WorkspaceApiClient().search(prompt.trim(), false)
                            val evidence = search.sources.joinToString("\n\n") { source ->
                                "SOURCE: " + source.title + "\nURL: " + source.url + "\n" + (source.snippet ?: "")
                            }
                            "Workspace: " + title + "\nUser request: " + prompt + "\n\nWEB SEARCH EVIDENCE:\n" + evidence
                        } else {
                            "Workspace: " + title + "\nUser request: " + prompt
                        }
                        val reply = ChatApiClient().streamMessage(requestText, capability, onDelta = { delta ->
                            streamingResult += delta
                        })
                        result = reply.text
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) {
                            result = streamingResult
                            streamingResult = ""
                            error = if (result.isNotBlank()) "Stopped. Partial output is shown above." else null
                        } else {
                            error = safeUserFacingIssue(t, "BQ Sqwad AI could not complete this request. Please try again.")
                        }
                    } finally {
                        busy = false
                        activeJob = null
                    }
                }
            }) { Text(if (busy) "Stop" else "Run with BQ Sqwad AI") }

            if (allowWebSearch && webSearchEnabled) {
                OutlinedButton(onClick = { useWebSearch = !useWebSearch }, enabled = !busy) {
                    Text(if (useWebSearch) "Web Search on" else "Web Search off")
                }
            } else if (allowWebSearch && !webSearchEnabled) {
                OutlinedButton(onClick = onOpenSettings, enabled = !busy) { Text("Enable Web Search in Settings") }
            }

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
        if (streamingResult.isNotBlank()) SurfaceCard("Live result", streamingResult)
        if (result.isNotBlank()) SurfaceCard("Result", result)
        SurfaceCard("Live catalog", "Availability, limits and access are controlled by the secure BQ Sqwad AI service.")
    }
}

@Composable
private fun DataAnalysisWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var dataText by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var isCsvDataset by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var analysisJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var rows by remember { mutableIntStateOf(0) }
    var columns by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { ChatAttachmentReader.read(context, uri) }.onSuccess { attachment ->
                val text = attachment.text.orEmpty()
                scope.launch(Dispatchers.Main) {
                    dataText = text
                    fileName = attachment.name
                    isCsvDataset = attachment.name.substringAfterLast('.', "").equals("csv", ignoreCase = true) || attachment.mimeType.equals("text/csv", ignoreCase = true) || attachment.mimeType.equals("application/csv", ignoreCase = true)
                    if (isCsvDataset) {
                        val dimensions = com.bqsqwad.ai.core.detectCsvDimensions(text)
                        rows = dimensions.dataRows
                        columns = dimensions.columns
                    } else {
                        rows = text.lineSequence().count { it.isNotBlank() }
                        columns = 0
                    }
                    error = null
                }
            }.onFailure { t -> scope.launch(Dispatchers.Main) { error = safeUserFacingIssue(t, "Could not read the data file.") } }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Data Analyst", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Analyze CSV, JSON and supported text-based datasets, then generate an AI explanation or report.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (fileName.isBlank()) "No dataset loaded" else fileName, fontWeight = FontWeight.SemiBold)
                        if (fileName.isNotBlank()) Text(
                            if (isCsvDataset) rows.toString() + " data rows • " + columns + " columns detected"
                            else rows.toString() + " non-empty lines • " + dataText.length + " characters",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/json", "application/csv", "text/csv")) }, enabled = !busy) { Text("Load data") }
                }
                OutlinedTextField(prompt, { prompt = it }, label = { Text("What should I analyze?") }, placeholder = { Text("Summarize trends, compare columns, find anomalies, or create a report…") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(enabled = busy || (dataText.isNotBlank() && prompt.isNotBlank()), onClick = {
                    if (busy) {
                        analysisJob?.cancel()
                        return@Button
                    }
                    busy = true; error = null; result = ""; streaming = ""
                    analysisJob = scope.launch(Dispatchers.IO) {
                        try {
                            val bounded = dataText.take(120000)
                            val dimensions = if (isCsvDataset) "ROWS: " + rows + "\nCOLUMNS: " + columns else "NON_EMPTY_LINES: " + rows
                            val request = "DATASET: " + fileName + "\n" + dimensions + "\nUSER REQUEST: " + prompt.trim() + "\n\nDATA:\n" + bounded
                            val reply = ChatApiClient().streamMessage(request, "data_analysis_workspace", onDelta = { delta -> scope.launch(Dispatchers.Main) { streaming += delta } })
                            scope.launch(Dispatchers.Main) { result = reply.text; streaming = ""; busy = false; analysisJob = null }
                        } catch (t: Throwable) {
                            scope.launch(Dispatchers.Main) {
                                if (t is kotlinx.coroutines.CancellationException) {
                                    result = streaming
                                    streaming = ""
                                    error = if (result.isNotBlank()) "Analysis stopped. Partial output is shown below." else null
                                } else {
                                    error = safeUserFacingIssue(t, "Data analysis failed. Please try again.")
                                }
                                busy = false
                                analysisJob = null
                            }
                        }
                    }
                }) { Text(if (busy) "Stop analysis" else "Analyze dataset") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (streaming.isNotBlank()) SurfaceCard("Live analysis", streaming)
        if (result.isNotBlank()) SurfaceCard("AI report", result)
        if (dataText.isNotBlank()) SurfaceCard("Dataset preview", dataText.lineSequence().take(8).joinToString("\n"))
        SurfaceCard("Credits", "Data analysis uses the shared backend credit system and the current service pricing configuration.")
    }
}

@Composable
private fun SearchWorkspace(onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsPrefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    var webSearchEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("web_search_enabled", true)) }
    var query by remember { mutableStateOf("") }
    var deep by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<SearchSource>() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Web Search", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Find current sources, review the answer, and open source links directly.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search") })
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = deep, onCheckedChange = { deep = it }, enabled = webSearchEnabled); Text("Deep Research") }
        if (!webSearchEnabled) {
            SurfaceCard("Web Search is off", "Turn it on in Settings to search the web or run Deep Research.")
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open Settings") }
        }
        Button(enabled = query.isNotBlank() && !busy && webSearchEnabled, onClick = {
            val submittedQuery = query.trim()
            busy = true
            error = null
            answer = null
            results.clear()
            scope.launch {
                try {
                    val reply = WorkspaceApiClient().search(submittedQuery, deep)
                    answer = reply.answer
                    results.addAll(reply.sources)
                } catch (t: Throwable) {
                    error = safeUserFacingIssue(t, "Search could not be completed. Please try again.")
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Searching…" else "Search") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        answer?.let { SurfaceCard(if (deep) "Deep Research Answer" else "Answer", it) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

private const val LIVE_MEDIA_CONSENT_VERSION = "2026-10-11"

@Composable
fun CameraWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsPrefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    val cameraEnhanceEnabled = remember { settingsPrefs.getBoolean("camera_enhance", true) }
    val frameCaptureIntervalMs = if (cameraEnhanceEnabled) 1800L else 2800L
    val aiFrameIntervalMs = if (cameraEnhanceEnabled) 1600L else 2600L
    var granted by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var active by remember { mutableStateOf(false) }
    var previewEnabled by remember { mutableStateOf(false) }
    var fullScreenPreview by remember { mutableStateOf(false) }
    var fullScreenPreviewReady by remember { mutableStateOf(false) }
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
    var streamEndpoint by remember { mutableStateOf("") }
    var streamOnline by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var tiktokHandoffNotice by remember { mutableStateOf<String?>(null) }
    var liveTermsAccepted by remember {
        val consentPrefs = context.getSharedPreferences("bq_live_consent", android.content.Context.MODE_PRIVATE)
        mutableStateOf(
            consentPrefs.getBoolean("accepted", false) &&
                consentPrefs.getString("policyVersion", "") == LIVE_MEDIA_CONSENT_VERSION
        )
    }
    var showLiveTerms by remember { mutableStateOf(false) }
    var pendingLiveAction by remember { mutableStateOf("camera") }
    var selectedVoice by remember { mutableStateOf("marin") }
    var publishedCameraModels by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedCameraModelId by remember { mutableStateOf("") }
    var cameraModelExpanded by remember { mutableStateOf(false) }
    var flashlightOn by remember { mutableStateOf(false) }
    val cameraLog = remember { mutableStateListOf<String>() }
    val cameraLogState = rememberLazyListState()
    fun reportCameraDiagnostic(code: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { WorkspaceApiClient().reportIssue("/live-camera", code) }
        }
    }
    fun cameraDiagnosticCode(message: String): String {
        val lower = message.lowercase()
        return when {
            lower.contains("flashlight") || lower.contains("torch") -> "flashlight_unavailable"
            lower.contains("permission") -> "camera_permission_missing"
            lower.contains("busy") || lower.contains("in use") -> "camera_busy"
            lower.contains("no camera") || lower.contains("unavailable") || lower.contains("not available") -> "camera_unavailable"
            lower.contains("timeout") -> "live_camera_timeout"
            else -> "live_camera_runtime_error"
        }
    }
    val liveVoices = listOf("marin" to "Aurora", "cedar" to "Cedar", "alloy" to "Clarity", "ash" to "Ember", "ballad" to "Velvet", "coral" to "Coral", "echo" to "Echo", "sage" to "Sage", "shimmer" to "Shimmer", "verse" to "Verse")
    val livePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        granted = cameraOk
        if (cameraOk && liveTermsAccepted) {
            // Reflect the runtime permission before composing NativeCameraPreview.
            // Otherwise the initial false state can keep the preview branch hidden even
            // after Android has granted camera access.
            previewEnabled = true
            if (micOk) {
                runCatching {
                    context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                        .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                        .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                        .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens)
                        .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice)
                        .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, selectedCameraModelId.takeIf { it.isNotBlank() }))
                }.onSuccess {
                    active = true
                    streamError = null
                }.onFailure {
                    active = false
                    streamError = "Android could not start the live camera service. Keep BQ Sqwad AI open, check Camera and Microphone permissions, then retry."
                }
            } else {
                active = false
                streamError = "Camera preview is active. Allow microphone access to start AI voice conversation."
            }
        } else {
            streamError = "Camera permission is required to show the live preview."
            scope.launch(Dispatchers.IO) {
                runCatching {
                    WorkspaceApiClient().reportIssue("/live-camera", if (cameraOk) "microphone_permission_missing" else "camera_permission_missing")
                }
            }
        }
    }

    val startLiveCamera = {
        if (!liveTermsAccepted) {
            pendingLiveAction = "camera"
            showLiveTerms = true
        } else {
            livePermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    val toggleFlashlight: () -> Unit = {
        runCatching {
            val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = manager.cameraIdList.firstOrNull { cameraId ->
                val cc = manager.getCameraCharacteristics(cameraId)
                cc.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == lens
            } ?: error("No camera is available for the flashlight.")
            val hasFlash = manager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) error("This camera does not have a flashlight. Switch to the rear camera.")
            // Normal preview toggles torch in the active Camera2 capture session.
            // During RTMP streaming the broadcaster owns Camera2, so use system torch.
            if (streamOnline) manager.setTorchMode(id, !flashlightOn)
            flashlightOn = !flashlightOn
            streamError = null
        }.onFailure {
            streamError = it.message?.takeIf { message ->
                message.contains("does not have a flashlight") || message.contains("No camera")
            } ?: "The flashlight could not be switched on. Try the rear camera and check that another app is not using the light."
        }
    }

    val streamPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (cameraOk && micOk && streamEndpoint.trim().isNotBlank()) {
            // The StreamPack RTMP broadcaster owns Camera2 while streaming.
            // Do not keep the separate Compose Camera2 preview open at the same time.
            // A shared StreamPack preview surface is needed to show preview during broadcast.
            previewEnabled = false
            fullScreenPreview = false
            streamOnline = true
            streamError = null
            scope.launch {
                androidx.compose.runtime.withFrameNanos { }
                runCatching {
                    context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                        .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                        .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                        .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens)
                        .putExtra(LiveMediaForegroundService.EXTRA_LIVE_ENDPOINT, streamEndpoint.trim())
                        .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice)
                        .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, selectedCameraModelId.takeIf { it.isNotBlank() }))
                }.onSuccess {
                    // Re-enable the preview container only after the service has had a
                    // moment to claim Camera2; streamOnline switches it to SourcePreview.
                    kotlinx.coroutines.delay(350)
                    previewEnabled = true
                }.onFailure {
                    streamOnline = false
                    previewEnabled = true
                    streamError = "Android could not start the custom live stream. Check Camera and Microphone permissions and retry."
                }
            }
        } else {
            streamError = "Camera and microphone permission are required for online streaming."
            scope.launch(Dispatchers.IO) {
                runCatching {
                    WorkspaceApiClient().reportIssue("/live-camera", if (!cameraOk) "camera_permission_missing" else "microphone_permission_missing")
                }
            }
        }
    }

    val screenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            screenAnswer = "Screen sharing was cancelled. Nothing was captured."
            return@rememberLauncherForActivityResult
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            screenAnswer = "Allow microphone access before starting AI screen conversation."
            return@rememberLauncherForActivityResult
        }
        // Release any idle camera preview before the screen-share service starts.
        // The screen session does not need to own the camera.
        previewEnabled = false
        fullScreenPreview = false
        fullScreenPreviewReady = false
        active = false
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
                    .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice)
                    .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, selectedCameraModelId.takeIf { it.isNotBlank() })
            )
            screenActive = true
            screenAnswer = "Android screen sharing is active. BQ Sqwad AI is receiving screen frames through the foreground service."
        }.onFailure { t ->
            context.stopService(Intent(context, LiveMediaForegroundService::class.java))
            screenActive = false
            screenAnswer = t.message ?: "Could not start screen sharing."
        }
    }

    val screenMicPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { grantedMic ->
        if (grantedMic || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            screenLauncher.launch(
                context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                    .createScreenCaptureIntent()
            )
        } else {
            screenAnswer = "Microphone access is required for live screen conversation. You can allow it in Android Settings."
            scope.launch(Dispatchers.IO) {
                runCatching { WorkspaceApiClient().reportIssue("/screen-share", "microphone_permission_missing") }
            }
        }
    }
    val launchScreenWithMic = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            screenLauncher.launch(
                context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                    .createScreenCaptureIntent()
            )
        } else {
            screenMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val liveUiState by LiveMediaForegroundService.uiState.collectAsState()
    val streamingPreview by OrganizationLiveStreamer.activeStreamer.collectAsState()
    LaunchedEffect(Unit) {
        runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }
            .onSuccess { array ->
                publishedCameraModels = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .filter { it.optString("feature") == "live-camera" && it.optBoolean("active") && it.optBoolean("published") }
                if (selectedCameraModelId.isNotBlank() && publishedCameraModels.none { it.optString("id") == selectedCameraModelId }) {
                    selectedCameraModelId = ""
                }
            }
    }
    LaunchedEffect(liveUiState.log.size) {
        if (liveUiState.log.isNotEmpty()) cameraLogState.animateScrollToItem((liveUiState.log.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(liveUiState) {
        liveServiceActive = liveUiState.active
        liveServiceMode = liveUiState.mode
        liveAnswer = liveUiState.answer
        streamOnline = liveUiState.onlineBroadcast
        if (liveServiceMode == LiveMediaForegroundService.MODE_SCREEN) {
            screenActive = liveServiceActive
            if (liveAnswer.isNotBlank()) screenAnswer = liveAnswer
        } else if (liveServiceMode == LiveMediaForegroundService.MODE_CAMERA) {
            active = liveServiceActive
            if (liveAnswer.isNotBlank()) answer = liveAnswer
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI Live Camera", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = toggleFlashlight, enabled = granted) { Text(if (flashlightOn) "Flashlight On" else "Flashlight") }
            Box {
                OutlinedButton(onClick = {
                    cameraModelExpanded = true
                    scope.launch {
                        runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }
                            .onSuccess { array ->
                                publishedCameraModels = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                                    .filter { it.optString("feature") == "live-camera" && it.optBoolean("active") && it.optBoolean("published") }
                            }
                    }
                }) {
                    val selectedName = publishedCameraModels.firstOrNull { it.optString("id") == selectedCameraModelId }?.optString("name")
                        ?: publishedCameraModels.firstOrNull { it.optBoolean("isDefault") }?.optString("name")
                        ?: "Server default"
                    Text("AI model: $selectedName")
                }
                DropdownMenu(expanded = cameraModelExpanded, onDismissRequest = { cameraModelExpanded = false }) {
                    DropdownMenuItem(text = { Text("Use server default") }, onClick = { selectedCameraModelId = ""; cameraModelExpanded = false })
                    publishedCameraModels.forEach { model ->
                        val perMinute = if (model.isNull("creditsPerMinute")) "server rate (default 1/min)" else "${model.optInt("creditsPerMinute")} credits/min"
                        DropdownMenuItem(
                            text = { Text(model.optString("name") + " · " + model.optInt("creditCost") + " credits to start · " + perMinute + if (model.optBoolean("isDefault")) " · Default" else "") },
                            onClick = { selectedCameraModelId = model.optString("id"); cameraModelExpanded = false }
                        )
                    }
                }
            }
            var voiceExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { voiceExpanded = true }) {
                    Text("Voice: " + (liveVoices.firstOrNull { it.first == selectedVoice }?.second ?: "Aurora"))
                }
                DropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                    liveVoices.forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { selectedVoice = id; voiceExpanded = false })
                    }
                }
            }
        }
        Text("Live camera viewer with secure frame analysis. Camera access stays visible while the session is active.")
        if (!granted) {
            Button(onClick = { permissionExplanation = true }) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Enable camera")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !screenActive || active,
                    onClick = {
                        if (active || previewEnabled) {
                            context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                            active = false
                            previewEnabled = false
                            fullScreenPreview = false
                        } else {
                            startLiveCamera()
                        }
                    }
                ) {
                    Text(
                        when {
                            active -> "End live camera"
                            screenActive -> "Stop screen sharing first"
                            previewEnabled -> "Stop preview"
                            else -> "Start live camera"
                        }
                    )
                }
                OutlinedButton(onClick = {
                    lens = if (lens == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                    if (active) {
                        context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                        active = false
                        scope.launch {
                            // Give Android time to release the previous AudioRecord/session
                            // before starting a fresh front/back-camera voice session.
                            kotlinx.coroutines.delay(250)
                            runCatching {
                                context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                                    .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                                    .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                                    .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens)
                                    .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice)
                                    .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, selectedCameraModelId.takeIf { it.isNotBlank() }))
                            }.onFailure {
                                streamError = "The camera switched, but the AI microphone session could not restart. Tap Retry to reconnect."
                            }
                        }
                    }
                }, enabled = active || previewEnabled) { Text("Flip") }
            }
            if (!screenActive && screenAnswer.isNotBlank()) {
                Text(screenAnswer, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (screenActive) {
                        context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                        screenActive = false
                    } else if (!liveTermsAccepted) {
                        pendingLiveAction = "screen"
                        showLiveTerms = true
                    } else {
                        launchScreenWithMic()
                    }
                }, enabled = !active || screenActive) {
                    Text(if (screenActive) "Stop screen" else "Share screen")
                }
            }
        }
        if (screenActive) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    BqLogo(64.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Screen mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("AI is listening and viewing your shared screen. Responses are voice-only.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Default.GraphicEq, contentDescription = "Voice active", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (screenAnalyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (granted && previewEnabled) {
            if (!fullScreenPreview) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).heightIn(min = 360.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    if (streamOnline) {
                        if (streamingPreview != null) {
                            SourcePreview(
                                videoSource = streamingPreview!!,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("Connecting to live stream preview…", color = Color.White,
                                modifier = Modifier.align(Alignment.Center))
                        }
                    } else if (fullScreenPreview) {
                        Text("Live preview is open in full screen.", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    } else {
                        NativeCameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            lensFacing = lens,
                            captureEveryMs = frameCaptureIntervalMs,
                            torchEnabled = flashlightOn,
                            onFrame = { bytes ->
                                val now = System.currentTimeMillis()
                                if (active && now - lastAnalysisAt >= aiFrameIntervalMs) {
                                    lastAnalysisAt = now
                                    LiveMediaForegroundService.submitVisualFrame(bytes)
                                }
                            },
                            onError = { message ->
                                streamError = message
                                reportCameraDiagnostic(cameraDiagnosticCode(message))
                            }
                        )
                    }
                    TextButton(
                        onClick = {
                            // First remove the inline AndroidView, then let Camera2 finish
                            // closing its session before the full-screen view opens.
                            fullScreenPreview = true
                            fullScreenPreviewReady = false
                            scope.launch {
                                kotlinx.coroutines.delay(300)
                                fullScreenPreviewReady = true
                            }
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    ) { Text("Full screen", color = Color.White) }
                    Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.62f)
                ) {
                    Text(
                        if (streamOnline) "● STREAMING ONLINE" else if (liveServiceActive) "● LIVE CAMERA" else "Connecting…",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                    }
                }
            }
            SurfaceCard("Live indicator", if (liveServiceActive)
                "● AI session connected • Camera preview remains visible independently of AI connection status."
                else "Camera preview is open. If the AI connection is unavailable, the preview stays visible while the connection error is shown.")
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LiveTv, contentDescription = null)
                        Text("One live destination at a time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("YouTube Mobile Live opens the official YouTube app. It cannot run at the same time as BQ Sqwad AI camera streaming because Android gives the camera to one app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !streamOnline && !screenActive,
                        onClick = {
                            val youtubeIntent = Intent("com.google.android.youtube.intent.action.CREATE_LIVE_STREAM")
                                .setPackage("com.google.android.youtube")
                                .putExtra(
                                    Intent.EXTRA_REFERRER,
                                    Uri.Builder().scheme("android-app").appendPath(context.packageName).build()
                                )
                                .putExtra(Intent.EXTRA_SUBJECT, "BQ Sqwad AI Live")
                            runCatching {
                                // Launch directly rather than relying on package visibility queries.
                                // Keep the foreground AI voice session running during app handoff.
                                context.startActivity(youtubeIntent)
                            }.onSuccess {
                                streamError = null
                            }.onFailure {
                                streamError = "YouTube Mobile Live could not be opened. Install or open the official YouTube app and try again."
                            }
                        }
                    ) {
                        Icon(Icons.Default.LiveTv, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open YouTube Live")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = liveServiceMode == LiveMediaForegroundService.MODE_CAMERA &&
                            liveUiState.status.lowercase() in setOf("connected", "listening", "speaking") && !screenActive,
                        onClick = {
                            tiktokHandoffNotice = null
                            val knownPackages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")
                            val packageLaunch = knownPackages.firstNotNullOfOrNull { packageName ->
                                runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
                            }
                            runCatching {
                                if (packageLaunch != null) {
                                    packageLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(packageLaunch)
                                } else {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/")))
                                }
                            }.onSuccess {
                                tiktokHandoffNotice = "TikTok is opening. BQ Sqwad AI's foreground voice session was not stopped. Start TikTok LIVE inside TikTok yourself; Android may give TikTok microphone/camera priority, so AI listening or camera analysis can pause."
                            }.onFailure {
                                tiktokHandoffNotice = "Could not open TikTok automatically. Open TikTok manually; keep the BQ Sqwad AI live-session notification running if you want to try background voice."
                            }
                        }
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Continue in TikTok")
                    }
                    tiktokHandoffNotice?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("TikTok starts its own LIVE session. BQ Sqwad AI does not control TikTok's Go LIVE button or inject an on-screen overlay; camera analysis pauses when TikTok owns the camera.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Stream online with AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("This optional advanced mode sends video directly to a custom RTMP/RTMPS endpoint. For TikTok or YouTube mobile-app LIVE, use the app handoff buttons instead; the AI voice session stays in its foreground service, but Android may give the microphone/camera to the streaming app. The endpoint is kept in memory and is never written to the app database.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = streamEndpoint,
                        onValueChange = { streamEndpoint = it; streamError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom RTMP/RTMPS endpoint (advanced)") },
                        singleLine = true,
                        enabled = !streamOnline
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = active && streamEndpoint.trim().isNotBlank(),
                            onClick = {
                                streamPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                            }
                        ) { Text(if (streamOnline) "Streaming online" else "Start online stream") }
                        if (streamOnline) {
                            OutlinedButton(onClick = {
                                context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                                streamOnline = false
                                scope.launch {
                                    kotlinx.coroutines.delay(250)
                                    previewEnabled = true
                                    active = false
                                }
                            }) { Text("Stop") }
                        }
                    }
                    streamError?.let { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                streamError = null
                                if (!granted) {
                                    permissionExplanation = true
                                } else if (streamEndpoint.trim().isNotBlank()) {
                                    streamPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                } else {
                                    context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                                    active = false
                                    previewEnabled = false
                                    fullScreenPreview = false
                                    scope.launch {
                                        kotlinx.coroutines.delay(250)
                                        startLiveCamera()
                                    }
                                }
                            }) { Text("Retry") }
                        }
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    BqLogo(64.dp)
                    Column(Modifier.weight(1f)) {
                        Text("AI Live Camera", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("AI sees the camera and responds by voice. No AI answer text is displayed.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Default.Mic, contentDescription = "Voice active", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SurfaceCard(
                    "Camera status",
                    if (!granted) "Camera permission is not available. Allow Camera and Microphone to start AI Live Camera."
                    else "Camera preview is stopped. Start or retry the live session."
                )
                streamError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (!granted) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)
                                        )
                                    )
                                }.onFailure {
                                    permissionExplanation = true
                                }
                            } else startLiveCamera()
                        }
                    ) { Text(if (!granted) "Open settings" else "Retry camera") }
                }
            }
        }
    }
    DeviceIntelligencePanel("AI Live Camera")

    if (fullScreenPreview && fullScreenPreviewReady && granted && previewEnabled) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                // Keep the inline preview unmounted until the dialog's Camera2
                // device has had time to close.
                fullScreenPreviewReady = false
                scope.launch {
                    kotlinx.coroutines.delay(250)
                    fullScreenPreview = false
                }
            },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (streamOnline) {
                    if (streamingPreview != null) {
                        SourcePreview(videoSource = streamingPreview!!, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("Connecting to live stream preview…", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    NativeCameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        lensFacing = lens,
                        captureEveryMs = frameCaptureIntervalMs,
                        torchEnabled = flashlightOn,
                        onFrame = { bytes ->
                            val now = System.currentTimeMillis()
                            if (active && now - lastAnalysisAt >= aiFrameIntervalMs) {
                                lastAnalysisAt = now
                                LiveMediaForegroundService.submitVisualFrame(bytes)
                            }
                        },
                        onError = { message ->
                            streamError = message
                            reportCameraDiagnostic(cameraDiagnosticCode(message))
                            fullScreenPreviewReady = false
                            scope.launch {
                                kotlinx.coroutines.delay(250)
                                fullScreenPreview = false
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
                        TextButton(onClick = toggleFlashlight) {
                            Icon(Icons.Default.FlashOn, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text(if (flashlightOn) "Flashlight on" else "Flashlight", color = Color.White)
                        }
                    }
                    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
                        TextButton(onClick = {
                            lens = if (lens == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK)
                                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                            else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                            flashlightOn = false
                        }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text("Switch camera", color = Color.White)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
                        TextButton(onClick = {
                            fullScreenPreviewReady = false
                            scope.launch {
                                kotlinx.coroutines.delay(250)
                                fullScreenPreview = false
                            }
                        }) { Text("Close", color = Color.White) }
                    }
                }
                val visibleAnswer = liveAnswer.ifBlank { liveUiState.status.takeIf { it.isNotBlank() && it != "stopped" }.orEmpty() }
                if (visibleAnswer.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(visibleAnswer.take(500), modifier = Modifier.padding(12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showLiveTerms) {
        AlertDialog(
            onDismissRequest = { showLiveTerms = false },
            title = { Text(if (pendingLiveAction == "screen") "Before you share your screen" else "Before you start Live Camera") },
            text = { Text("Before starting, please review this privacy notice. If you continue, BQ Sqwad AI will access only the camera, microphone, or screen-sharing capability needed for the feature you start. Live audio or selected camera/screen frames may be sent securely to BQ Sqwad AI's server and its configured AI provider to generate replies. Do not share passwords, payment details, private messages, confidential work, or other people's personal information unless you have permission. Tell anyone who may be recorded or shown that AI analysis is active and obtain their consent where required. Opening TikTok or another app does not give BQ Sqwad AI permission to control that app or bypass its privacy controls. Android may stop or limit camera/microphone access when another app uses it. You can stop the AI session at any time using the in-app control or persistent notification. Usage may consume the credits shown in the app. Continue only if you understand and agree.") },
            confirmButton = {
                TextButton(onClick = {
                    liveTermsAccepted = true
                    context.getSharedPreferences("bq_live_consent", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("accepted", true)
                        .putString("policyVersion", LIVE_MEDIA_CONSENT_VERSION)
                        .apply()
                    showLiveTerms = false
                    if (pendingLiveAction == "screen") {
                        pendingLiveAction = "camera"
                        launchScreenWithMic()
                    } else {
                        pendingLiveAction = "camera"
                        startLiveCamera()
                    }
                }) { Text("Accept & Continue") }
            },
            dismissButton = { TextButton(onClick = { showLiveTerms = false }) { Text("Cancel") } }
        )
    }

    if (permissionExplanation) {
        AlertDialog(
            onDismissRequest = { permissionExplanation = false },
            title = { Text("Camera and microphone access") },
            text = { Text("BQ Sqwad AI needs camera access for the live preview and microphone access for live voice replies. If Android has permanently denied a permission, open app settings and enable Camera and Microphone. A visible Android foreground-session notification remains while the AI session is active.") },
            confirmButton = {
                TextButton(onClick = { permissionExplanation = false; startLiveCamera() }) { Text("Grant permissions") }
            },
            dismissButton = {
                TextButton(onClick = {
                    permissionExplanation = false
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }.onFailure {
                        streamError = "Open Android Settings, choose Apps, then BQ Sqwad AI, and allow Camera and Microphone."
                    }
                }) { Text("Open settings") }
            }
        )
    }
}

@Composable
fun VoiceWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var publishedVoiceModels by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedVoiceModelId by remember { mutableStateOf("") }
    var voiceModelExpanded by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("stopped") }
    var startError by remember { mutableStateOf<String?>(null) }
    var permissionExplanation by remember { mutableStateOf(false) }

    fun startVoice() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionExplanation = true
            return
        }
        runCatching {
            context.startForegroundService(
                Intent(context, LiveMediaForegroundService::class.java)
                    .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_VOICE)
                    .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, selectedVoiceModelId.takeIf { it.isNotBlank() })
            )
        }.onSuccess {
            active = true
            state = "connecting"
            startError = null
        }.onFailure {
            active = false
            state = "error"
            startError = "Live Voice could not start. Check microphone permission and try again."
        }
    }

    fun stopVoice() {
        runCatching { context.stopService(Intent(context, LiveMediaForegroundService::class.java)) }
            .onSuccess { active = false; state = "stopped"; startError = null }
            .onFailure { startError = "Live Voice could not stop cleanly. Close the session again or restart the app." }
    }

    val liveUiState by LiveMediaForegroundService.uiState.collectAsState()
    LaunchedEffect(Unit) {
        runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }
            .onSuccess { array ->
                publishedVoiceModels = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .filter { it.optString("feature") == "live-voice" && it.optBoolean("active") && it.optBoolean("published") }
                if (selectedVoiceModelId.isNotBlank() && publishedVoiceModels.none { it.optString("id") == selectedVoiceModelId }) {
                    selectedVoiceModelId = ""
                }
            }
    }
    LaunchedEffect(liveUiState) {
        if (liveUiState.mode == LiveMediaForegroundService.MODE_VOICE) {
            active = liveUiState.active
            state = liveUiState.status.ifBlank { "stopped" }
        }
    }
    val idleTransition = rememberInfiniteTransition(label = "voiceIdle")
    val idlePulse by idleTransition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voiceIdlePulse"
    )
    val targetVoiceScale = if (active) 1f + (liveUiState.audioLevel * 0.42f) else idlePulse
    val voiceScale by animateFloatAsState(
        targetValue = targetVoiceScale,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "voiceScale"
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoice() }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .size((178 * voiceScale).dp.coerceIn(178.dp, 250.dp))
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.96f),
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.82f),
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    )
                )
                .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(112.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.20f),
                tonalElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = "AI voice assistant",
                        modifier = Modifier.size(58.dp),
                        tint = Color.White
                    )
                }
            }
        }

        Text(
            if (active) state.replaceFirstChar { it.uppercase() } else "Voice off",
            style = MaterialTheme.typography.titleMedium
        )
        startError?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }

        Box {
            OutlinedButton(onClick = {
                voiceModelExpanded = true
                scope.launch {
                    runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }
                        .onSuccess { array ->
                            publishedVoiceModels = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                                .filter { it.optString("feature") == "live-voice" && it.optBoolean("active") && it.optBoolean("published") }
                        }
                }
            }) {
                val selectedName = publishedVoiceModels.firstOrNull { it.optString("id") == selectedVoiceModelId }?.optString("name")
                    ?: publishedVoiceModels.firstOrNull { it.optBoolean("isDefault") }?.optString("name")
                    ?: "Server default"
                Text("AI model: " + selectedName.take(20))
            }
            DropdownMenu(expanded = voiceModelExpanded, onDismissRequest = { voiceModelExpanded = false }) {
                DropdownMenuItem(text = { Text("Use server default") }, onClick = { selectedVoiceModelId = ""; voiceModelExpanded = false })
                publishedVoiceModels.forEach { model ->
                    val perMinute = if (model.isNull("creditsPerMinute")) "server rate (default 1/min)" else "${model.optInt("creditsPerMinute")} credits/min"
                    DropdownMenuItem(
                        text = { Text(model.optString("name") + " · " + model.optInt("creditCost") + " credits to start · " + perMinute + if (model.optBoolean("isDefault")) " · Default" else "") },
                        onClick = { selectedVoiceModelId = model.optString("id"); voiceModelExpanded = false }
                    )
                }
            }
        }

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
                "Voice assistant is active. Speak naturally and BQ Sqwad AI will respond with audio.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (liveUiState.answer.isNotBlank()) {
            SurfaceCard(
                if (liveUiState.status == "error") "Voice error" else "Voice status",
                liveUiState.answer
            )
        }

        if (liveUiState.log.isNotEmpty()) {
            SurfaceCard(
                "Conversation transcript",
                liveUiState.log.takeLast(12).joinToString("\n\n")
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
fun DeviceIntelligencePanel(featureName: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var publishedDeviceModels by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedDeviceModelId by remember { mutableStateOf("") }
    var deviceModelExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }
            .onSuccess { array ->
                publishedDeviceModels = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .filter { it.optString("feature") == "device-commands" && it.optBoolean("active") && it.optBoolean("published") }
                if (selectedDeviceModelId.isNotBlank() && publishedDeviceModels.none { it.optString("id") == selectedDeviceModelId }) selectedDeviceModelId = ""
            }
    }

    SurfaceCard(
        "Device Intelligence",
        "Use $featureName to control supported Android device actions. Main Chat does not expose this control panel."
    )

    Box {
        val selectedName = publishedDeviceModels.firstOrNull { it.optString("id") == selectedDeviceModelId }?.optString("name")
            ?: publishedDeviceModels.firstOrNull { it.optBoolean("isDefault") }?.optString("name")
            ?: "Server default"
        OutlinedButton(onClick = {
            deviceModelExpanded = true
            scope.launch {
                runCatching { WorkspaceApiClient().publishedAiModels().optJSONArray("models") ?: JSONArray() }
                    .onSuccess { array ->
                        publishedDeviceModels = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                            .filter { it.optString("feature") == "device-commands" && it.optBoolean("active") && it.optBoolean("published") }
                    }
            }
        }) { Text("AI model: " + selectedName.take(20)) }
        DropdownMenu(expanded = deviceModelExpanded, onDismissRequest = { deviceModelExpanded = false }) {
            DropdownMenuItem(text = { Text("Use server default") }, onClick = { selectedDeviceModelId = ""; deviceModelExpanded = false })
            publishedDeviceModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.optString("name") + " · " + model.optInt("creditCost") + " credits/request" + if (model.optBoolean("isDefault")) " · Default" else "") },
                    onClick = { selectedDeviceModelId = model.optString("id"); deviceModelExpanded = false }
                )
            }
        }
    }
    OutlinedTextField(
        value = command,
        onValueChange = { command = it; status = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Ask the device assistant") },
        placeholder = { Text("Turn on the torch, raise volume, open Calendar…") },
        maxLines = 3
    )
    Button(
        enabled = !busy && command.isNotBlank(),
        onClick = {
            val requested = command.trim()
            busy = true
            status = null
            scope.launch(Dispatchers.IO) {
                runCatching {
                    DeviceIntelligenceClient().interpret(requested, selectedDeviceModelId.takeIf { it.isNotBlank() })
                }.onSuccess { result ->
                    val executor = DeviceCommandExecutor(context)
                    val failures = result.actions.mapNotNull { action ->
                        executor.execute(action).exceptionOrNull()?.message
                    }
                    scope.launch(Dispatchers.Main) {
                        busy = false
                        status = buildString {
                            append(result.message.ifBlank { "Device command processed." })
                            if (result.actions.isNotEmpty()) {
                                append(" Executed ")
                                append(result.actions.size)
                                append(" supported action(s).")
                            }
                            if (failures.isNotEmpty()) {
                                append(" Some actions could not be completed: ")
                                append(failures.joinToString("; "))
                            }
                            if (result.creditsCharged > 0) {
                                append(" Credits used: ")
                                append(result.creditsCharged)
                            }
                        }
                    }
                }.onFailure {
                    scope.launch(Dispatchers.Main) {
                        busy = false
                        status = it.message ?: "Device intelligence failed."
                    }
                }
            }
            command = ""
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (busy) "Processing device command…" else "Run device command")
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onOpenAccountProtection: () -> Unit, onOpenLegalCenter: () -> Unit, onOpenBranding: () -> Unit, onNotificationsChanged: (Boolean) -> Unit, notificationStatus: String?, onOpenWorkspace: (Workspace) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "system") ?: "system") }
    var accent by remember { mutableStateOf(prefs.getString("accent_palette", "signature") ?: "signature") }
    var trainingConsent by remember { mutableStateOf(prefs.getBoolean("training_consent", false)) }
    var chatHistory by remember { mutableStateOf(prefs.getBoolean("chat_history", true)) }
    var autoSave by remember { mutableStateOf(prefs.getBoolean("auto_save", true)) }
    var memory by remember { mutableStateOf(prefs.getBoolean("memory_enabled", true)) }
    var webSearch by remember { mutableStateOf(prefs.getBoolean("web_search_enabled", true)) }
    var voiceReplies by remember { mutableStateOf(prefs.getBoolean("voice_replies", true)) }
    var cameraEnhance by remember { mutableStateOf(prefs.getBoolean("camera_enhance", true)) }
    var companionMemory by remember { mutableStateOf(prefs.getBoolean("companion_memory", true)) }
    var truthGuardSources by remember { mutableStateOf(prefs.getBoolean("truthguard_sources", true)) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("notifications_enabled", true)) }
    var settingsNotice by remember { mutableStateOf<String?>(null) }
    var textScale by remember { mutableStateOf(prefs.getFloat("text_scale", 1.0f).coerceIn(0.85f, 1.4f)) }
    var consentStatus by remember { mutableStateOf<String?>(null) }
    var credits by remember { mutableStateOf<JSONObject?>(null) }
    var entitlements by remember { mutableStateOf(emptyList<JSONObject>()) }
    var loadingAccount by remember { mutableStateOf(true) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var scheduledDeletionAt by remember { mutableStateOf<String?>(null) }
    var cancellingDeletion by remember { mutableStateOf(false) }
    var deletionNotice by remember { mutableStateOf<String?>(null) }
    var playManagement by remember { mutableStateOf<JSONObject?>(null) }
    var selectedAiFeature by remember { mutableStateOf<Workspace?>(null) }

    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
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

    LaunchedEffect(Unit) {
        loadingAccount = true
        val api = WorkspaceApiClient()
        credits = runCatching { api.credits() }.getOrNull()
        playManagement = runCatching { api.playSubscriptionManagement() }.getOrNull()
        entitlements = runCatching {
            val a = api.entitlements().optJSONArray("items") ?: JSONArray()
            List(a.length()) { i -> a.getJSONObject(i) }
        }.getOrDefault(emptyList())
        runCatching { AccountDeletionClient().deletionStatus() }
            .onSuccess { status -> scheduledDeletionAt = status.optString("scheduledDeletionAt").takeIf { status.optBoolean("pending") && it.isNotBlank() && it != "null" } }
        loadingAccount = false
    }

    val modes = listOf("system", "light", "dark", "amoled", "midnight", "ocean", "aurora", "sunset")
    val accents = listOf("signature", "blue", "indigo", "violet", "teal", "emerald", "rose", "amber", "graphite")
    val adminUnlimited = credits?.optBoolean("adminUnlimited", false) == true
    val balance = credits?.optDouble("available", 0.0) ?: 0.0
    val balanceLabel = if (adminUnlimited) "Unlimited" else String.format(java.util.Locale.US, "%.0f", balance)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings", fontWeight = FontWeight.Bold)
                        Text("Customize your BQ Sqwad AI experience", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            scheduledDeletionAt?.let { deadline ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Account deletion scheduled", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("Your account is scheduled for deletion on $deadline. Sign back in before this date to cancel deletion.", color = MaterialTheme.colorScheme.onErrorContainer)
                            Button(
                                enabled = !cancellingDeletion,
                                onClick = {
                                    cancellingDeletion = true
                                    scope.launch {
                                        runCatching { AccountDeletionClient().cancelDeletion() }
                                            .onSuccess {
                                                scheduledDeletionAt = null
                                                deletionNotice = "Account deletion cancelled. Your account remains active."
                                            }
                                            .onFailure { deletionNotice = it.message ?: "Could not cancel scheduled deletion." }
                                        cancellingDeletion = false
                                    }
                                }
                            ) { Text(if (cancellingDeletion) "Cancelling…" else "Cancel account deletion") }
                        }
                    }
                }
            }
            deletionNotice?.let { notice ->
                item { Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    tonalElevation = 3.dp
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (FirebaseAuth.getInstance().currentUser?.displayName?.trim()?.firstOrNull()
                                        ?: FirebaseAuth.getInstance().currentUser?.email?.trim()?.firstOrNull()
                                        ?: "U").toString().uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Your account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(FirebaseAuth.getInstance().currentUser?.email ?: "Signed in", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BqLogo(26.dp)
                                        Text("BQ Sqwad Credits", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text(if (loadingAccount) "…" else balanceLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text("Available shared balance", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("Plans", style = MaterialTheme.typography.labelLarge)
                                    Text(entitlements.size.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text("Verified access", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            item { SettingsSectionTitle("Account & security", "Identity, protection and device access") }
            item {
                OutlinedButton(
                    onClick = {
                        FirebaseAuth.getInstance().signOut()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Log out")
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeatureCard("Account protection", "Password, Google sign-in, passkeys and device protection.", onOpenAccountProtection)
                    FeatureCard("Brand & App Logo", "Choose one logo for all shared BQ Sqwad AI logo placements, or restore the original.", onOpenBranding)
                    FeatureCard("Privacy & Legal Center", "Consent, privacy topics, Terms and data controls.", onOpenLegalCenter)
                    FeatureCard("Android permissions", "Review camera, microphone, notifications and other OS permissions.") {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                    }
                }
            }

            item { SettingsSectionTitle("Plans, credits & billing", "Your server-authoritative usage and Google Play subscriptions") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                BqLogo(38.dp)
                                Column(Modifier.weight(1f)) {
                                    Text("BQ Sqwad Credits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("One shared balance for eligible AI features", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(if (adminUnlimited) "Unlimited" else String.format(java.util.Locale.US, "%.0f", balance), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Your balance is managed securely by BQ Sqwad AI.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    playManagement?.takeIf { it.optBoolean("hasSubscription", false) }?.let { billing ->
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Google Play billing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(if (billing.optBoolean("autoRenewEnabled", false)) "Auto-renewal is on" else "Auto-renewal is off", fontWeight = FontWeight.Bold)
                                Text("Google Play controls renewal, payment methods and subscription changes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(billing.optString("managementUrl")))) }, Modifier.weight(1f)) { Text("Manage") }
                                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(billing.optString("paymentMethodManagementUrl")))) }, Modifier.weight(1f)) { Text("Payment") }
                                }
                            }
                        }
                    }
                }
            }

            item { SettingsSectionTitle("Appearance", "Theme, color and text size") }
            item {
                SurfaceCard("Theme", "Choose how BQ Sqwad AI looks across the native app.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(modes) { mode ->
                        FilterChip(selected = themeMode == mode, onClick = { saveTheme(mode) }, label = { Text(mode.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
            item {
                SurfaceCard("Accent", "Choose the interface accent while keeping the BQ identity.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(accents) { name ->
                        FilterChip(selected = accent == name, onClick = { saveAccent(name) }, label = { Text(name.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Text size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Adjust text across the app from smaller to larger.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = textScale, onValueChange = { value ->
                        textScale = value
                        prefs.edit().putFloat("text_scale", value).apply()
                    }, valueRange = 0.85f..1.4f, steps = 10)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Smaller", style = MaterialTheme.typography.labelSmall)
                        Text((textScale * 100).toInt().toString() + "%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("Larger", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { SettingsSectionTitle("Chat & intelligence", "Control how the assistant works for you") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggleCard("Conversation history", "Save conversations for History.", chatHistory) { chatHistory = it; putBoolean("chat_history", it) }
                    SettingsToggleCard("Automatic saving", "Save completed conversations and supported attachments.", autoSave) { autoSave = it; putBoolean("auto_save", it) }
                    SettingsToggleCard("Personal memory", "Use the memory system you control.", memory) {
                        memory = it; putBoolean("memory_enabled", it)
                        scope.launch {
                            runCatching { WorkspaceApiClient().setMemoryEnabled(it) }
                                .onSuccess { settingsNotice = null }
                                .onFailure { settingsNotice = "Personal memory could not be updated on the server. Try again." }
                        }
                    }
                    SettingsToggleCard("Web Search", "Allow supported AI features to use server-controlled web search.", webSearch) { webSearch = it; putBoolean("web_search_enabled", it) }
                }
            }

            item { SettingsSectionTitle("Live AI", "Voice, camera and Companion controls") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggleCard("Read-aloud replies", "Show spoken playback controls for text responses.", voiceReplies) { voiceReplies = it; putBoolean("voice_replies", it) }
                    SettingsToggleCard("Camera enhancement", "Analyze camera frames more frequently for a more responsive live AI view.", cameraEnhance) { cameraEnhance = it; putBoolean("camera_enhance", it) }
                    SettingsToggleCard("Companion memory", "Allow Companion to use its configured memory controls.", companionMemory) {
                        companionMemory = it
                        putBoolean("companion_memory", it)
                        scope.launch {
                            runCatching { WorkspaceApiClient().setCompanionMemoryEnabled(it) }
                                .onSuccess { settingsNotice = null }
                                .onFailure { settingsNotice = "Companion memory could not be updated on the server. Try again." }
                        }
                    }
                }
            }

            item { SettingsSectionTitle("AI features", "Feature-specific controls stay inside their workspaces") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggleCard("TruthGuard evidence sources", "Show source-backed evidence and reliability details.", truthGuardSources) {
                        truthGuardSources = it
                        putBoolean("truthguard_sources", it)
                        scope.launch {
                            runCatching { WorkspaceApiClient().saveTruthGuardSettings(JSONObject().put("showSources", it)) }
                                .onSuccess { settingsNotice = null }
                                .onFailure { settingsNotice = "TruthGuard source preferences could not be synced. Open TruthGuard settings and save again." }
                        }
                    }
                    FeatureCard(Workspace.LEARNING.title, Workspace.LEARNING.description) { selectedAiFeature = Workspace.LEARNING }
                    FeatureCard(Workspace.LIFE.title, Workspace.LIFE.description) { selectedAiFeature = Workspace.LIFE }
                    FeatureCard(Workspace.BUSINESS.title, Workspace.BUSINESS.description) { selectedAiFeature = Workspace.BUSINESS }
                    FeatureCard(Workspace.ORGANIZATION_STUDIO.title, Workspace.ORGANIZATION_STUDIO.description) { selectedAiFeature = Workspace.ORGANIZATION_STUDIO }
                    FeatureCard(Workspace.DATA_ANALYSIS.title, Workspace.DATA_ANALYSIS.description) { selectedAiFeature = Workspace.DATA_ANALYSIS }
                    FeatureCard(Workspace.FRONTIER_LAB.title, Workspace.FRONTIER_LAB.description) { selectedAiFeature = Workspace.FRONTIER_LAB }
                }
            }

            item { SettingsSectionTitle("Notifications & feedback", "Stay informed without losing control") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggleCard("Notifications", "Receive supported plan, generation, business and security notifications.", notifications) {
                        notifications = it
                        putBoolean("notifications_enabled", it)
                        onNotificationsChanged(it)
                    }
                    notificationStatus?.let { Text(it, color = if (it.startsWith("Notification preferences could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    Text("Notification permission is controlled by Android. Sound and vibration follow your device settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    settingsNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }

            item { SettingsSectionTitle("Privacy & data", "Your consent and account data") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SurfaceCard("AI training consent", if (trainingConsent) "Optional training consent is on." else "Optional training consent is off.")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = trainingConsent, onCheckedChange = { enabled ->
                            trainingConsent = enabled
                            prefs.edit().putBoolean("training_consent", enabled).apply()
                            consentStatus = "Saving consent…"
                            scope.launch {
                                runCatching { WorkspaceApiClient().updateTrainingConsent(enabled) }
                                    .onSuccess { consentStatus = "Training consent updated." }
                                    .onFailure { consentStatus = "Could not update consent. Try again." }
                            }
                        })
                        Spacer(Modifier.width(8.dp))
                        Text("Allow non-private training activity to improve the service.", Modifier.weight(1f))
                    }
                    consentStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FeatureCard("Privacy Policy", "Read the complete BQ Sqwad AI Privacy Policy.") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bqsqwadai.com/privacy-policy"))) }
                    FeatureCard("Terms & Conditions", "Read the complete BQ Sqwad AI Terms & Conditions.") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bqsqwadai.com/terms"))) }
                    FeatureCard("Delete account and data", "Permanently delete the account and associated app data.") { showDeleteAccount = true; deleteError = null }
                }
            }

            item { SettingsSectionTitle("About", "BQ Sqwad AI") }
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    tonalElevation = 2.dp
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("BQ Sqwad AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("The AI That Grows With You")
                        Text("BQ Sqwad Technologies", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                }
            }
        }
    }

    if (showDeleteAccount) {
        AlertDialog(
            onDismissRequest = { if (!deletingAccount) showDeleteAccount = false },
            title = { Text("Delete account and data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your account and associated app data will be scheduled for deletion after a 30-day grace period. You can cancel by signing back in before the scheduled date.")
                    Text("Some records may be retained when required by applicable financial, tax, fraud-prevention or security obligations.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !deletingAccount, onClick = {
                    deletingAccount = true
                    scope.launch {
                        runCatching { AccountDeletionClient().deleteAccount() }
                            .onSuccess { result ->
                                val deadline = result.optString("scheduledDeletionAt").ifBlank { "in 30 days" }
                                android.widget.Toast.makeText(context, "Account deletion scheduled for $deadline. Sign back in before then to cancel.", android.widget.Toast.LENGTH_LONG).show()
                                FirebaseAuth.getInstance().signOut()
                                showDeleteAccount = false
                                onBack()
                            }
                            .onFailure { deleteError = it.message ?: "Account deletion failed." }
                        deletingAccount = false
                    }
                }) { Text(if (deletingAccount) "Scheduling…" else "Schedule deletion") }
            },
            dismissButton = { TextButton(enabled = !deletingAccount, onClick = { showDeleteAccount = false }) { Text("Cancel") } }
        )
    }
    selectedAiFeature?.let { feature ->
        AlertDialog(
            onDismissRequest = { selectedAiFeature = null },
            title = { Text(feature.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(featureLongDescription(feature))
                    Text("How it works", fontWeight = FontWeight.SemiBold)
                    Text(featureHowItWorks(feature), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    selectedAiFeature = null
                    onOpenWorkspace(feature)
                }) { Text("Try it") }
            },
            dismissButton = {
                TextButton(onClick = { selectedAiFeature = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsToggleCard(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable private fun FeatureCard(title: String, body: String, onClick: (() -> Unit)? = null) {
    if (onClick != null) {
        BqNavigationCard(title = title, body = body, onClick = onClick)
    } else {
        BqCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable private fun AccessDenied() { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("This area is not available for your account.", fontWeight = FontWeight.Bold) } }

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


@Composable
private fun WallpaperBackground(index: Int) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val brush = if (dark) {
        when (index) {
            1 -> Brush.linearGradient(listOf(Color(0xFF071A2B), Color(0xFF123D5A)))
            2 -> Brush.linearGradient(listOf(Color(0xFF120B2B), Color(0xFF263A73)))
            3 -> Brush.linearGradient(listOf(Color(0xFF061E1A), Color(0xFF164B3D)))
            4 -> Brush.linearGradient(listOf(Color(0xFF1D1022), Color(0xFF4C244E)))
            else -> Brush.linearGradient(listOf(Color(0xFF05070D), Color(0xFF10243A)))
        }
    } else {
        when (index) {
            1 -> Brush.linearGradient(listOf(colors.background, colors.primaryContainer))
            2 -> Brush.linearGradient(listOf(colors.background, colors.secondaryContainer))
            3 -> Brush.linearGradient(listOf(colors.background, colors.surfaceVariant))
            4 -> Brush.linearGradient(listOf(colors.background, colors.secondaryContainer))
            else -> Brush.linearGradient(listOf(colors.background, colors.surface))
        }
    }
    Box(Modifier.fillMaxSize().background(brush))
}

@Composable private fun FloatingLogo(modifier: Modifier) {
    // Use the single shared brand mark without a glowing or faded logo variant.
    BqLogo(76.dp, modifier)
}