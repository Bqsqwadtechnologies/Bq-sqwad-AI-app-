@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bqsqwad.ai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.os.Bundle
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

private fun safeUserFacingIssue(throwable: Throwable, fallback: String): String {
    val message = throwable.message?.trim().orEmpty()
    if (message.isBlank()) return fallback
    val technical = Regex("https?://|\\bHTTP\\s*\\d{3}\\b|route_not_migrated|UnknownHostException|SocketTimeoutException|java\\.|com\\.bqsqwad|CLEARTEXT|Exception:|provider_http|not_configured|client_secret_missing|realtime_session_failed|firebase|firestore|stack trace|internal server|\\bstatus code\\b|\\b(?:401|403|404|500|501|502|503)\\b", RegexOption.IGNORE_CASE)
    return if (technical.containsMatchIn(message)) {
        "BQ Sqwad AI is temporarily unavailable. The BQ Sqwad AI team is working on it. Please try again shortly."
    } else message.take(240)
}

@Composable
fun BQSqwadApp(modifier: Modifier = Modifier) = AuthGate(modifier)

@Composable
private fun AuthGate(modifier: Modifier) {
    // Guest browsing remains available, but protected actions open the real Firebase auth flow.
    // MainShell observes FirebaseAuth state so a successful sign-in immediately updates the UI.
    MainShell(modifier)
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
private fun MainShell(modifier: Modifier) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    var firebaseUser by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseUser = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    val email = firebaseUser?.email.orEmpty()
    var workspace by remember { mutableStateOf<Workspace?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var accountProtection by remember { mutableStateOf(false) }
    var legalCenter by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var featureInfo by remember { mutableStateOf<Workspace?>(null) }
    var featurePrivacy by remember { mutableStateOf<Workspace?>(null) }
    var featureHub by remember { mutableStateOf(false) }
    var featureSettings by remember { mutableStateOf<Workspace?>(null) }
    var advancedFeature by remember { mutableStateOf<AppFeature?>(null) }
    var adminAuthorized by remember { mutableStateOf(false) }
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
        // Keep startup work parallel: chat stays visible while admin status and push
        // registration load independently, so incident alerts are registered sooner.
        launch {
            adminAuthorized = runCatching {
                WorkspaceApiClient().adminAccess().optBoolean("allowed")
            }.getOrDefault(false)
        }
        launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val token = Tasks.await(FirebaseMessaging.getInstance().token)
                    if (token.isNotBlank()) WorkspaceApiClient().registerNotificationToken(token)
                }
            }
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
            if (adminAuthorized) workspace = selected else paywallWorkspace = selected
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
            advancedFeature != null -> AdvancedFeatureScreen(advancedFeature!!) { advancedFeature = null }
            plans -> ProductCatalogScreen { plans = false }
            accountProtection -> AccountProtectionScreen { accountProtection = false }
            featureInfo != null -> FeatureInfoScreen(featureInfo!!) { featureInfo = null }
            featurePrivacy != null -> LegalCenterScreen(initialTopic = featurePrivacy!!) { featurePrivacy = null }
            legalCenter -> LegalCenterScreen { legalCenter = false }
            settings -> SettingsScreen(
                onBack = { settings = false },
                onOpenAccountProtection = { accountProtection = true },
                onOpenLegalCenter = { legalCenter = true }
            )
            history -> HistoryScreen { history = false }
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
                onPrivacy = { featurePrivacy = Workspace.CHAT }
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

private fun isImageGenerationRequest(text: String): Boolean {
    val q = text.trim().lowercase()
    if (q.isBlank()) return false
    val explicit = listOf(
        "generate image", "generate an image", "generate a picture",
        "create image", "create an image", "create a picture",
        "make image", "make an image", "make a picture",
        "draw an image", "draw a picture", "draw me an image",
        "show me an image of", "show me a picture of"
    )
    return explicit.any(q::startsWith)
}

private data class ChatMessage(val mine: Boolean, val text: String, val id: String = UUID.randomUUID().toString(), val prompt: String? = null)

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
private fun MainChatScreen(modifier: Modifier, openHub: () -> Unit, openFeatures: () -> Unit, openSettings: () -> Unit, openPlans: () -> Unit, openHistory: () -> Unit, openPlugins: () -> Unit, onLearnMore: () -> Unit, onPrivacy: () -> Unit) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
    var selectedAdvanced by remember { mutableStateOf<AppFeature?>(null) }
    var privateChat by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready") }
    var creditNotice by remember { mutableStateOf<String?>(null) }
    var creditsExhausted by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }

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
            .mapNotNull { key -> snapshot.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } }
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
        val recognizer = speechRecognizer
        if (recognizer == null) {
            micError = "Voice input is not available on this device."
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to BQ Sqwad AI")
            }
            runCatching { recognizer.startListening(intent); isListening = true; micError = null }
                .onFailure { isListening = false; micError = it.message ?: "Could not start voice input." }
        }
    }
    val speechPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSpeechRecognition()
        else micError = "Allow microphone access to use voice input."
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
        val imageRequest = text.isNotBlank() && attachments.isEmpty() && isImageGenerationRequest(text)
        if (imageRequest) {
            val promptText = text.trim()
            message = ""
            messages.add(ChatMessage(true, promptText))
            val aiIndex = messages.size
            messages.add(ChatMessage(false, "Generating image…"))
            busy = true
            statusText = "Generating image…"
            error = null
            streamJob = scope.launch(Dispatchers.IO) {
                try {
                    val generated = GenerationApiClient().generateImage(promptText)
                    scope.launch(Dispatchers.Main) {
                        messages[aiIndex] = messages[aiIndex].copy(text = "Image generated. Open the generated image: " + generated.url)
                        busy = false
                        statusText = "Ready"
                        streamJob = null
                    }
                } catch (t: Throwable) {
                    scope.launch(Dispatchers.Main) {
                        if (messages.getOrNull(aiIndex)?.text == "Generating image…") messages.removeAt(aiIndex)
                        error = safeUserFacingIssue(t, "BQ Sqwad AI could not complete the image request. The team is working on it.")
                        busy = false
                        statusText = "Request failed. You can try again."
                        streamJob = null
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
        statusText = "Connecting to BQ Sqwad AI…"
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                val reply = if (imageParts.isNotEmpty()) {
                    val image = imageParts.first()
                    ChatApiClient().analyzeCameraFrame(actualPrompt, image.imageBase64!!, image.mimeType)
                } else {
                    ChatApiClient().streamMessage(actualPrompt, capability = activeCapability, system = activeSystem, onDelta = { delta ->
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
                    if (!privateChat) {
                        val snapshot = messages.map { HistoryMessage(it.mine, it.text, it.id, it.prompt) }
                        runCatching {
                            historyId = HistoryApiClient().save(historyId, text.ifBlank { attachmentNames.ifBlank { "New chat" } }, snapshot)
                        }.onFailure { error = "The response was generated, but chat history could not be saved." }
                    }
                }
            } catch (t: Throwable) {
                scope.launch(Dispatchers.Main) {
                    val current = messages.getOrNull(aiIndex)
                    if (current != null && current.text.isBlank()) messages.removeAt(aiIndex)
                    attachments = selectedAttachments
                    if (t !is kotlinx.coroutines.CancellationException) {
                        val detail = t.message.orEmpty().lowercase()
                        if (detail.contains("insufficient credit") || detail.contains("credit balance is too low") || detail.contains("run out of") || detail.contains("out of credits")) {
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
                        onClick = { menuOpen = true }
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
            val logoPulseTransition = rememberInfiniteTransition(label = "bqPulse")
            val logoPulse by logoPulseTransition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bqPulseValue"
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (messages.isEmpty()) {
                    item {
                        val floatTransition = rememberInfiniteTransition(label = "bqFloatingLogo")
                        val floatOffset by floatTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = -8f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1400, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "bqFloatingLogoOffset"
                        )
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
                                    .offset(y = floatOffset.dp)
                                    .clickable(onClick = openFeatures)
                                    .padding(horizontal = 18.dp)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f + (logoPulse * 0.45f)),
                                            CircleShape
                                        ),
                                    shape = CircleShape,
                                    color = Color.Transparent,
                                    shadowElevation = 22.dp
                                ) {
                                    FloatingLogo(Modifier.fillMaxSize())
                                }
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
                            BqGeneratingIndicator(modifier = Modifier.padding(vertical = 4.dp))
                        } else {
                            Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) { if (m.mine) Text(m.text, Modifier.padding(14.dp)) else MarkdownText(m.text, Modifier.padding(14.dp)) }
                        }
                        if (!m.mine) Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            BqLogo(22.dp)
                            IconButton(onClick = { clipboard.setText(AnnotatedString(m.text)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy response")
                            }
                            IconButton(onClick = { shareText(context, m.text) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share response")
                            }
                            IconButton(onClick = { speakTextWhenReady(context, m.text) { error = it } }) {
                                Icon(Icons.Default.VolumeUp, contentDescription = "Read response aloud")
                            }
                            IconButton(onClick = { message = m.prompt ?: m.text }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Resend prompt")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { WorkspaceApiClient().reportMessage(m.text, "report", m.id, "report") }
                                        .onSuccess { error = "Report submitted for review." }
                                        .onFailure { error = safeUserFacingIssue(it, "Your report could not be submitted. Please try again shortly.") }
                                }
                            }) {
                                Icon(Icons.Default.Flag, contentDescription = "Report response")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { WorkspaceApiClient().reportMessage(m.text, "response", m.id, "up") }
                                        .onSuccess { error = "Thanks for the feedback." }
                                        .onFailure { error = safeUserFacingIssue(it, "Your feedback could not be submitted. Please try again shortly.") }
                                }
                            }) {
                                Icon(Icons.Default.ThumbUp, contentDescription = "Helpful response")
                            }
                            IconButton(onClick = { feedbackTarget = m }) {
                                Icon(Icons.Default.ThumbDown, contentDescription = "Not helpful")
                            }
                            if (m.prompt != null) {
                                IconButton(onClick = {
                                    message = m.prompt
                                    val text = m.prompt
                                    if (FirebaseAuth.getInstance().currentUser != null && !busy) {
                                        sendAuthenticatedMessage(text)
                                    }
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Regenerate response")
                                }
                            }
                        }
                    }
                }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
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
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
    "accessibility_ai_controls", "model_routing", "provider_failover", "ai_orchestration"
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
                Workspace.SEARCH -> SearchWorkspace()
                Workspace.TRUTH_GUARD -> CompleteTruthGuardWorkspace(onOpenPlans = onOpenPlans)
                Workspace.FRONTIER_LAB -> FrontierLabWorkspace(onOpenPlans = onOpenPlans)
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
private fun AdvancedFeatureScreen(feature: AppFeature, onBack: () -> Unit) {
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
                        "Connected capability: " + feature.capability,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
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
                SurfaceCard("Credits", "Usage is charged through the shared backend credit system. Pricing and limits remain admin-configurable.")
            }
            AiTaskWorkspace(feature.title, feature.description, feature.capability, false, feature.webSearch)
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
    allowWebSearch: Boolean = false
) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf("") }
    var memoryEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var useWebSearch by remember { mutableStateOf(allowWebSearch) }
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
            Button(enabled = prompt.isNotBlank() && !busy, onClick = {
                busy = true
                error = null
                result = ""
                streamingResult = ""
                scope.launch {
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
                        error = t.message ?: "Request failed"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Working…" else "Run with BQ Sqwad AI") }

            if (allowWebSearch) {
                OutlinedButton(onClick = { useWebSearch = !useWebSearch }, enabled = !busy) {
                    Text(if (useWebSearch) "Web Search on" else "Web Search off")
                }
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
    var result by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rows by remember { mutableIntStateOf(0) }
    var columns by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { ChatAttachmentReader.read(context, uri) }.onSuccess { attachment ->
                val text = attachment.text.orEmpty()
                scope.launch(Dispatchers.Main) {
                    dataText = text
                    fileName = attachment.name
                    val lines = text.lines().filter { it.isNotBlank() }
                    rows = (lines.size - 1).coerceAtLeast(0)
                    columns = lines.firstOrNull()?.split(',')?.size ?: 0
                    error = null
                }
            }.onFailure { t -> scope.launch(Dispatchers.Main) { error = t.message ?: "Could not read the data file." } }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Data Analyst", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Analyze CSV, JSON and supported text-based datasets, then generate an AI explanation or report.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(if (fileName.isBlank()) "No dataset loaded" else fileName, fontWeight = FontWeight.SemiBold); if (fileName.isNotBlank()) Text(rows.toString() + " rows • " + columns + " columns detected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/json", "application/csv", "text/csv")) }, enabled = !busy) { Text("Load data") }
                }
                OutlinedTextField(prompt, { prompt = it }, label = { Text("What should I analyze?") }, placeholder = { Text("Summarize trends, compare columns, find anomalies, or create a report…") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(enabled = dataText.isNotBlank() && prompt.isNotBlank() && !busy, onClick = {
                    busy = true; error = null; result = ""; streaming = ""
                    scope.launch(Dispatchers.IO) {
                        try {
                            val bounded = dataText.take(120000)
                            val request = "DATASET: " + fileName + "\\nROWS: " + rows + "\\nCOLUMNS: " + columns + "\\nUSER REQUEST: " + prompt.trim() + "\\n\\nDATA:\\n" + bounded
                            val reply = ChatApiClient().streamMessage(request, "data_analysis_workspace", onDelta = { delta -> scope.launch(Dispatchers.Main) { streaming += delta } })
                            scope.launch(Dispatchers.Main) { result = reply.text; busy = false }
                        } catch (t: Throwable) { scope.launch(Dispatchers.Main) { error = t.message ?: "Data analysis failed."; busy = false } }
                    }
                }) { Text(if (busy) "Analyzing…" else "Analyze dataset") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (streaming.isNotBlank()) SurfaceCard("Live analysis", streaming)
        if (result.isNotBlank()) SurfaceCard("AI report", result)
        if (dataText.isNotBlank()) SurfaceCard("Dataset preview", dataText.lineSequence().take(8).joinToString("\\n"))
        SurfaceCard("Credits", "Data analysis uses the shared backend credit system and the current service pricing configuration.")
    }
}

@Composable
private fun SearchWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var deep by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<SearchSource>() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Web Search", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search") })
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(deep, { deep = it }); Text("Deep Research") }
        Button(enabled = query.isNotBlank(), onClick = {
            error = null
            scope.launch { try { val reply = WorkspaceApiClient().search(query.trim(), deep); answer = reply.answer; results.clear(); results.addAll(reply.sources) } catch (t: Throwable) { error = t.message ?: "Search failed" } }
        }) { Text("Search") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        answer?.let { SurfaceCard(if (deep) "Deep Research Answer" else "Answer", it) }
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
fun CameraWorkspace() {
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
    var streamEndpoint by remember { mutableStateOf("") }
    var streamOnline by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var liveTermsAccepted by remember { mutableStateOf(context.getSharedPreferences("bq_live_consent", android.content.Context.MODE_PRIVATE).getBoolean("accepted", false)) }
    var showLiveTerms by remember { mutableStateOf(false) }
    var selectedVoice by remember { mutableStateOf("marin") }
    var flashlightOn by remember { mutableStateOf(false) }
    val cameraLog = remember { mutableStateListOf<String>() }
    val cameraLogState = rememberLazyListState()
    val liveVoices = listOf("marin" to "Aurora", "cedar" to "Cedar", "alloy" to "Clarity", "ash" to "Ember", "ballad" to "Velvet", "coral" to "Coral", "echo" to "Echo", "sage" to "Sage", "shimmer" to "Shimmer", "verse" to "Verse")
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) {
            context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens)
                .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice))
            active = true
        }
    }
    val livePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (cameraOk && micOk && liveTermsAccepted) {
            context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens)
                .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice))
            active = true
        }
    }

    val startLiveCamera = {
        if (!liveTermsAccepted) showLiveTerms = true
        else livePermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    val toggleFlashlight: () -> Unit = {
        runCatching {
            val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = manager.cameraIdList.firstOrNull { cameraId ->
                val cc = manager.getCameraCharacteristics(cameraId)
                cc.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == lens
            } ?: manager.cameraIdList.first()
            manager.setTorchMode(id, !flashlightOn)
            flashlightOn = !flashlightOn
        }
    }

    val streamPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (cameraOk && micOk && streamEndpoint.trim().isNotBlank()) {
            context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens)
                .putExtra(LiveMediaForegroundService.EXTRA_LIVE_ENDPOINT, streamEndpoint.trim())
                .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice))
            streamOnline = true
            streamError = null
        } else {
            streamError = "Camera and microphone permission are required for online streaming."
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
                    .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice)
            )
            screenActive = true
            screenAnswer = "Android screen sharing is active. BQ Sqwad AI is receiving screen frames through the foreground service."
        }.onFailure { t ->
            context.stopService(Intent(context, LiveMediaForegroundService::class.java))
            screenActive = false
            screenAnswer = t.message ?: "Could not start screen sharing."
        }
    }

    val liveUiState by LiveMediaForegroundService.uiState.collectAsState()
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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI Live Camera", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = toggleFlashlight, enabled = granted) { Text(if (flashlightOn) "Flashlight On" else "Flashlight") }
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
                Button(onClick = {
                    if (active) {
                        context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                        active = false
                    } else {
                        startLiveCamera()
                    }
                }) { Text(if (active) "End live camera" else "Start live camera") }
                OutlinedButton(onClick = {
                    lens = if (lens == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                    if (active) {
                        context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                        context.startForegroundService(Intent(context, LiveMediaForegroundService::class.java)
                            .putExtra(LiveMediaForegroundService.EXTRA_MODE, LiveMediaForegroundService.MODE_CAMERA)
                            .putExtra(LiveMediaForegroundService.EXTRA_PROMPT, prompt)
                            .putExtra(LiveMediaForegroundService.EXTRA_LENS, lens)
                            .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice))
                    }
                }, enabled = active) { Text("Flip") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (screenActive) {
                        context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                        screenActive = false
                    } else if (!liveTermsAccepted) {
                        showLiveTerms = true
                    } else {
                        screenLauncher.launch(
                            context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                                .createScreenCaptureIntent()
                        )
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
                    Image(
                        painter = painterResource(R.drawable.bq_sqwad_logo),
                        contentDescription = "BQ Sqwad AI",
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Screen mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("AI is listening and viewing your shared screen. Responses are voice-only.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Default.GraphicEq, contentDescription = "Voice active", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (screenAnalyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (granted && active) {
            val streamingPreview by OrganizationLiveStreamer.activeStreamer.collectAsState()
            Box(
                Modifier.fillMaxWidth().weight(1f).heightIn(min = 360.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                if (streamOnline && streamingPreview != null) {
                    SourcePreview(
                        videoSource = streamingPreview!!,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    NativeCameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        lensFacing = lens,
                        captureEveryMs = 1800L,
                        onFrame = { bytes ->
                            val now = System.currentTimeMillis()
                            if (active && now - lastAnalysisAt >= 1600L) {
                                lastAnalysisAt = now
                                LiveMediaForegroundService.submitVisualFrame(bytes)
                            }
                        }
                    )
                }
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
            SurfaceCard("Live indicator", "● Camera active • Android foreground service is running and BQ Sqwad AI is receiving sampled camera frames.")
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
                            val supported = context.packageManager.queryIntentActivities(
                                youtubeIntent,
                                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                            ).isNotEmpty()
                            if (supported) {
                                context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                                context.startActivity(youtubeIntent)
                            } else {
                                streamError = "YouTube Mobile Live is not available on this device."
                            }
                        }
                    ) {
                        Icon(Icons.Default.LiveTv, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open YouTube Live")
                    }
                    Text("Stream online with AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Broadcast this camera session to one RTMP/RTMPS destination at a time. TikTok client credentials are used for TikTok account/API features; live broadcasting still requires a supported TikTok LIVE ingest endpoint and stream credential. The stream endpoint is kept in memory and is never written to the app database.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = streamEndpoint,
                        onValueChange = { streamEndpoint = it; streamError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("YouTube / TikTok LIVE ingest / custom RTMP endpoint") },
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
                            }) { Text("Stop") }
                        }
                    }
                    streamError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
                    Image(
                        painter = painterResource(R.drawable.bq_sqwad_logo),
                        contentDescription = "BQ Sqwad AI",
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.weight(1f)) {
                        Text("AI Live Camera", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("AI sees the camera and responds by voice. No AI answer text is displayed.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Default.Mic, contentDescription = "Voice active", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            SurfaceCard("Camera status", "Camera preview is stopped. Start the live session after granting permission.")
        }
    }
    DeviceIntelligencePanel("AI Live Camera")

    if (showLiveTerms) {
        AlertDialog(
            onDismissRequest = { showLiveTerms = false },
            title = { Text("Before you start Live Camera") },
            text = { Text("By continuing, you agree that BQ Sqwad AI may use your camera, microphone, or shared screen only for the Live Camera session. Screen sharing can capture information visible on your device, so avoid displaying private or confidential information. You are responsible for content you choose to stream or share. You can stop the session at any time. Live Camera usage may consume BQ Sqwad credits according to the displayed rate.") },
            confirmButton = {
                TextButton(onClick = {
                    liveTermsAccepted = true
                    context.getSharedPreferences("bq_live_consent", android.content.Context.MODE_PRIVATE).edit().putBoolean("accepted", true).apply()
                    showLiveTerms = false
                    startLiveCamera()
                }) { Text("Accept & Continue") }
            },
            dismissButton = { TextButton(onClick = { showLiveTerms = false }) { Text("Cancel") } }
        )
    }

    if (permissionExplanation) {
        AlertDialog(
            onDismissRequest = { permissionExplanation = false },
            title = { Text("Camera access") },
            text = { Text("BQ Sqwad AI needs camera access only while you use Live Camera. A visible Android live-session indicator stays on while the camera is active.") },
            confirmButton = {
                TextButton(onClick = { permissionExplanation = false; startLiveCamera() }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { permissionExplanation = false }) { Text("Not now") } }
        )
    }
}

@Composable
fun VoiceWorkspace() {
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

    val liveUiState by LiveMediaForegroundService.uiState.collectAsState()
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

    SurfaceCard(
        "Device Intelligence",
        "Use $featureName to control supported Android device actions. Main Chat does not expose this control panel."
    )
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
                    DeviceIntelligenceClient().interpret(requested)
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
            val manager = PlayBillingClient(activity, onPurchaseState = { state ->
                status = when (state.status) {
                    "success" -> state.message
                    "pending" -> state.message
                    "verifying" -> state.message
                    else -> state.message
                }
            })
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
private fun SettingsScreen(onBack: () -> Unit, onOpenAccountProtection: () -> Unit, onOpenLegalCenter: () -> Unit) {
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
    var sound by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
    var vibration by remember { mutableStateOf(prefs.getBoolean("vibration_enabled", true)) }
    var reduceMotion by remember { mutableStateOf(prefs.getBoolean("reduce_motion", false)) }
    var compactUi by remember { mutableStateOf(prefs.getBoolean("compact_ui", false)) }
    var consentStatus by remember { mutableStateOf<String?>(null) }
    var credits by remember { mutableStateOf<JSONObject?>(null) }
    var entitlements by remember { mutableStateOf(emptyList<JSONObject>()) }
    var loadingAccount by remember { mutableStateOf(true) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var playManagement by remember { mutableStateOf<JSONObject?>(null) }

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

            item { SettingsSectionTitle("Appearance", "Theme, color and interface density") }
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { SettingsToggleCard("Compact UI", "Tighter spacing", compactUi) { compactUi = it; putBoolean("compact_ui", it) } }
                    Box(Modifier.weight(1f)) { SettingsToggleCard("Reduce motion", "Fewer animations", reduceMotion) { reduceMotion = it; putBoolean("reduce_motion", it) } }
                }
            }

            item { SettingsSectionTitle("Chat & intelligence", "Control how the assistant works for you") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggleCard("Conversation history", "Save conversations for History.", chatHistory) { chatHistory = it; putBoolean("chat_history", it) }
                    SettingsToggleCard("Automatic saving", "Save completed conversations and supported attachments.", autoSave) { autoSave = it; putBoolean("auto_save", it) }
                    SettingsToggleCard("Personal memory", "Use the memory system you control.", memory) {
                        memory = it; putBoolean("memory_enabled", it)
                        scope.launch { runCatching { WorkspaceApiClient().setMemoryEnabled(it) } }
                    }
                    SettingsToggleCard("Web Search", "Allow supported AI features to use server-controlled web search.", webSearch) { webSearch = it; putBoolean("web_search_enabled", it) }
                }
            }

            item { SettingsSectionTitle("Live AI", "Voice, camera and Companion controls") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggleCard("Live Voice replies", "Enable spoken replies where supported.", voiceReplies) { voiceReplies = it; putBoolean("voice_replies", it) }
                    SettingsToggleCard("Camera enhancement", "Use available visual processing enhancements.", cameraEnhance) { cameraEnhance = it; putBoolean("camera_enhance", it) }
                    SettingsToggleCard("Companion memory", "Allow Companion to use its configured memory controls.", companionMemory) { companionMemory = it; putBoolean("companion_memory", it) }
                }
            }

            item { SettingsSectionTitle("AI features", "Feature-specific controls stay inside their workspaces") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggleCard("TruthGuard evidence sources", "Show source-backed evidence and reliability details.", truthGuardSources) { truthGuardSources = it; putBoolean("truthguard_sources", it) }
                    FeatureCard("Learning Studio", "Learning behavior, courses and tutoring controls live in its workspace.")
                    FeatureCard("Life Matters", "Life guidance, routines and saved insights live in its workspace.")
                    FeatureCard("Business Workspace", "Assistants, channels, inbox, leads, catalog and integrations.")
                    FeatureCard("Organization Studio", "Organization knowledge, memory, production and team controls.")
                    FeatureCard("Data Analyst", "Analysis preferences and saved analysis controls.")
                    FeatureCard("Frontier Lab", "Advanced capabilities and provider-controlled experiments.")
                }
            }

            item { SettingsSectionTitle("Notifications & feedback", "Stay informed without losing control") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggleCard("Notifications", "Plan, generation, business and security notifications.", notifications) { notifications = it; putBoolean("notifications_enabled", it) }
                    SettingsToggleCard("Sound", "Use app sounds where supported.", sound) { sound = it; putBoolean("sound_enabled", it) }
                    SettingsToggleCard("Vibration", "Use haptic feedback where supported.", vibration) { vibration = it; putBoolean("vibration_enabled", it) }
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
                    Text("This permanently removes your account and associated app data. This action cannot be undone.")
                    Text("Some records may be retained when required by applicable financial, tax, fraud-prevention or security obligations.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !deletingAccount, onClick = {
                    deletingAccount = true
                    scope.launch {
                        runCatching { AccountDeletionClient().deleteAccount() }
                            .onSuccess { FirebaseAuth.getInstance().signOut(); showDeleteAccount = false; onBack() }
                            .onFailure { deleteError = it.message ?: "Account deletion failed." }
                        deletingAccount = false
                    }
                }) { Text(if (deletingAccount) "Deleting…" else "Delete") }
            },
            dismissButton = { TextButton(enabled = !deletingAccount, onClick = { showDeleteAccount = false }) { Text("Cancel") } }
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
    Box(modifier.clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF7EA7FF), Color(0xFF1749D1), Color(0xFF061A63), Color.Transparent)))) { LogoBubble(Modifier.fillMaxSize().padding(8.dp)) }
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