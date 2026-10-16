@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bqsqwad.ai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface as AndroidSurface
import android.view.TextureView
import android.util.LruCache
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
import java.util.UUID
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.bqsqwad.ai.R
import com.bqsqwad.ai.InAppNotificationBus
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
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
import kotlinx.coroutines.async
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
    if (throwable is WorkspaceRedirectException) return ""
    val message = throwable.message?.trim().orEmpty()
    if (message.isBlank()) return fallback
    val lower = message.lowercase()
    if (lower.contains("insufficient credits") || lower.contains("insufficient_credits") || lower.contains("credit_balance")) {
        return "You need more credits to continue. Choose Buy credits to add credits."
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
    if (lower.contains("attachment_count_invalid")) return "Attach up to 8 images and up to 4 PDFs per message. Send extra files in another message."
    if (lower.contains("file_type_invalid")) return "This file type is not supported for original-file analysis. Use a PDF or a supported text document."
    if (lower.contains("consent_required")) return "Accept the required consent in Settings before using AI features."
    if (lower.contains("plan_required") || lower.contains("entitlement_required")) return "This feature is not included in your current plan. Open Plans & Billing to review access."
    if (lower.contains("rate limit") || lower.contains("rate_limited") || lower.contains("http 429")) return "You are sending requests too quickly. Wait a moment and try again."
    // Never echo raw exception text to a normal user. It can contain account
    // email addresses, worker/repository URLs, provider responses, or file paths.
    // Detailed diagnostics belong only in the authenticated admin dashboard.
    return "BQ Sqwad AI is temporarily unavailable. The BQ Sqwad AI team is working on it. Please try again shortly."
}

private val bqTextScaleState = mutableFloatStateOf(1.0f)

@Composable
fun BQSqwadApp(
    modifier: Modifier = Modifier,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {}
) {
    val systemDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = systemDensity.density,
            fontScale = systemDensity.fontScale * bqTextScaleState.floatValue.coerceIn(0.85f, 1.8f)
        )
    ) {
        AuthGate(modifier, sharedText, onSharedTextConsumed)
    }
}

@Composable
private fun AuthGate(modifier: Modifier, sharedText: String?, onSharedTextConsumed: () -> Unit) {
    // Guest browsing remains available, but protected actions open the real Firebase auth flow.
    // MainShell observes FirebaseAuth state so a successful sign-in immediately updates the UI.
    MainShell(modifier, sharedText, onSharedTextConsumed)
}

@Composable
private fun EmailVerificationGate(modifier: Modifier, onVerified: () -> Unit) {
    val context = LocalContext.current
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
                error = "Could not refresh your account status. Please try again."
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
                    else error = "Could not send the verification email. Please try again."
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Resend verification email") }
        TextButton(onClick = { performSignOut(context) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun accountPreferences(context: Context, baseName: String = "bq_sqwad_user"): android.content.SharedPreferences {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
    return context.getSharedPreferences(baseName + "_" + uid, Context.MODE_PRIVATE)
}

private fun performSignOut(context: Context, onReset: () -> Unit = {}) {
    val auth = FirebaseAuth.getInstance()
    val uid = auth.currentUser?.uid
    if (!uid.isNullOrBlank()) {
        listOf("bq_sqwad_user", "bq_ui", "bq_live_consent", "bq_consent").forEach { base ->
            context.getSharedPreferences(base + "_" + uid, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
    runCatching { context.stopService(Intent(context, com.bqsqwad.ai.core.LiveMediaForegroundService::class.java)) }
    // Keep only device-level appearance preferences. Account settings and consent are cleared.
    val uiPrefs = context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE)
    val deviceKeys = setOf("theme_mode", "accent_palette")
    uiPrefs.edit().apply {
        uiPrefs.all.keys.filterNot { it in deviceKeys }.forEach { remove(it) }
    }.apply()
    accountPreferences(context, "bq_ui").edit().clear().apply()
    context.getSharedPreferences("bq_live_consent", Context.MODE_PRIVATE).edit().clear().apply()
    accountPreferences(context, "bq_consent").edit().clear().apply()
    auth.signOut()
    onReset()
}

private fun authSignOutForConsent(context: Context) {
    performSignOut(context)
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
                        else -> safeUserFacingIssue(it, "BQ Sqwad AI could not verify your consent right now. Please try again.")
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
            Text(
                "Training is optional. Only turn it on if you agree to contribute eligible non-private text chats to a reviewable training-data library. You can change this later in Settings & Privacy; it does not automatically retrain model weights.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LegalConsentLinks()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        authSignOutForConsent(context)
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
                                    accountPreferences(context, "bq_consent").edit()
                                        .putString("policy_version", "2026-09")
                                        .putLong("required_consent_at", System.currentTimeMillis())
                                        .putBoolean("required_accepted", true)
                                        .putBoolean("training_consent", trainingConsent)
                                        .apply()
                                }
                                .onFailure {
                                    accepted = false
                                    error = safeUserFacingIssue(it, "Consent could not be saved.")
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
    var authVideoPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var authVideoSurface by remember { mutableStateOf<AndroidSurface?>(null) }
    var authVideoPrepared by remember { mutableStateOf(false) }
    var authVideoPaused by remember { mutableStateOf(false) }
    var authVideoMuted by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        runCatching { WorkspaceApiClient().publicAppConfig() }
            .onSuccess { config ->
                if (config.optBoolean("authBackgroundVideoEnabled", false)) {
                    backgroundVideoUrl = config.optString("authBackgroundVideoUrl").takeIf { it.isNotBlank() }
                }
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            val player = authVideoPlayer
            authVideoPlayer = null
            authVideoPrepared = false
            runCatching { player?.stop() }
            runCatching { player?.release() }
            val surface = authVideoSurface
            authVideoSurface = null
            runCatching { surface?.release() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        backgroundVideoUrl?.let { url ->
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        isOpaque = false
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                val outputSurface = AndroidSurface(surfaceTexture)
                                val player = android.media.MediaPlayer()
                                authVideoSurface = outputSurface
                                authVideoPlayer = player
                                runCatching {
                                    player.setSurface(outputSurface)
                                    player.setDataSource(url)
                                    player.isLooping = true
                                    player.setOnPreparedListener { preparedPlayer ->
                                        authVideoPrepared = true
                                        preparedPlayer.isLooping = true
                                        preparedPlayer.setVolume(if (authVideoMuted) 0f else 1f, if (authVideoMuted) 0f else 1f)
                                        preparedPlayer.start()
                                        if (authVideoPaused) preparedPlayer.pause()
                                    }
                                    player.setOnErrorListener { _, _, _ ->
                                        authVideoPrepared = false
                                        true
                                    }
                                    player.prepareAsync()
                                }.onFailure {
                                    authVideoPrepared = false
                                    authVideoPlayer = null
                                    runCatching { player.release() }
                                    authVideoSurface = null
                                    runCatching { outputSurface.release() }
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                val player = authVideoPlayer
                                authVideoPlayer = null
                                authVideoPrepared = false
                                runCatching { player?.stop() }
                                runCatching { player?.release() }
                                val outputSurface = authVideoSurface
                                authVideoSurface = null
                                runCatching { outputSurface?.release() }
                                return true
                            }
                        }
                    }
                },
                update = {
                    authVideoPlayer?.let { player ->
                        runCatching {
                            player.setVolume(if (authVideoMuted) 0f else 1f, if (authVideoMuted) 0f else 1f)
                            if (authVideoPrepared) {
                                if (authVideoPaused && player.isPlaying) player.pause()
                                else if (!authVideoPaused && !player.isPlaying) player.start()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().alpha(0.68f)
            )
        }
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background.copy(alpha = if (backgroundVideoUrl == null) 1f else 0.48f)
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
        if (backgroundVideoUrl != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                color = Color.Black.copy(alpha = 0.68f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = {
                        authVideoPaused = !authVideoPaused
                        authVideoPlayer?.let { player ->
                            runCatching {
                                if (authVideoPaused) player.pause()
                                else if (authVideoPrepared) player.start()
                            }
                        }
                    }) { Text(if (authVideoPaused) "Play video" else "Pause video", color = Color.White) }
                    TextButton(onClick = {
                        authVideoMuted = !authVideoMuted
                        authVideoPlayer?.let { player ->
                            runCatching { player.setVolume(if (authVideoMuted) 0f else 1f, if (authVideoMuted) 0f else 1f) }
                        }
                    }) { Text(if (authVideoMuted) "Unmute" else "Mute", color = Color.White) }
                }
            }
        }
    }
}

private fun friendlyAuthError(error: Exception?): String {
    return when (error) {
        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> "The email or password is incorrect."
        is com.google.firebase.auth.FirebaseAuthInvalidUserException -> "No account was found for that email."
        is com.google.firebase.auth.FirebaseAuthUserCollisionException -> "An account already exists with a different sign-in provider. Try the provider originally used."
        is com.google.firebase.FirebaseNetworkException -> "Network connection failed. Check your internet connection and try again."
        else -> "Sign-in could not be completed. Please try again."
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
        else -> "Google sign-in could not be completed. Please try again."
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
    LaunchedEffect(firebaseUser?.uid) {
        bqTextScaleState.floatValue = accountPreferences(context).getFloat("text_scale", 1.0f).coerceIn(0.85f, 1.8f)
    }
    val shellScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    BackHandler(enabled = drawerState.isOpen) { shellScope.launch { drawerState.close() } }
    var chatSessionNonce by remember { mutableIntStateOf(0) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var workspace by remember { mutableStateOf<Workspace?>(null) }
    val foregroundPushBanner by InAppNotificationBus.banner.collectAsState()
    var dailyFeatureAnnouncement by remember(firebaseUser?.uid) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(firebaseUser?.uid) {
        dailyFeatureAnnouncement = null
        runCatching { WorkspaceApiClient().publicAppConfig() }.onSuccess { config ->
            val announcement = config.optJSONObject("dailyFeatureAnnouncement")
            val announcementId = announcement?.optString("id").orEmpty()
            val ownerKey = firebaseUser?.uid?.takeIf { it.isNotBlank() } ?: "guest"
            val seenPrefs = context.getSharedPreferences("bq_feature_announcements", Context.MODE_PRIVATE)
            if (announcement != null && announcement.optBoolean("enabled", false) && announcementId.isNotBlank() &&
                seenPrefs.getString("seen_$ownerKey", null) != announcementId) {
                dailyFeatureAnnouncement = JSONObject(announcement.toString())
            }
        }
    }
    LaunchedEffect(foregroundPushBanner) {
        val currentBanner = foregroundPushBanner?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        kotlinx.coroutines.delay(8_000L)
        if (InAppNotificationBus.banner.value == currentBanner) InAppNotificationBus.clear()
    }
    val pendingWorkspaceRedirect by WorkspaceRedirectStore.redirect.collectAsState()
    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) workspace = null
    }
    var plans by remember { mutableStateOf(false) }
    var plansInitialSection by remember { mutableStateOf("all") }
    var settings by remember { mutableStateOf(false) }
    var accountProtection by remember { mutableStateOf(false) }
    var legalCenter by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var fileBoxOpen by remember { mutableStateOf(false) }
    var pendingFileBoxAttachment by remember { mutableStateOf<PreparedChatAttachment?>(null) }
    var restoredChat by remember { mutableStateOf<Pair<String, List<HistoryMessage>>?>(null) }
    var drawerHistoryItems by remember(firebaseUser?.uid) { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var drawerHistoryLimit by remember { mutableStateOf(5) }
    var drawerHistoryLoading by remember { mutableStateOf(false) }
    var drawerHistoryError by remember { mutableStateOf<String?>(null) }
    var openingDrawerHistoryId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(drawerState.isOpen, firebaseUser?.uid, drawerHistoryLimit) {
        if (!drawerState.isOpen) return@LaunchedEffect
        if (firebaseUser == null) {
            drawerHistoryItems = emptyList()
            drawerHistoryError = null
            return@LaunchedEffect
        }
        drawerHistoryLoading = true
        drawerHistoryError = null
        runCatching { HistoryApiClient().list(drawerHistoryLimit.coerceIn(1, 100)) }
            .onSuccess { drawerHistoryItems = it }
            .onFailure { drawerHistoryError = safeUserFacingIssue(it, "Recent chat history could not be loaded.") }
        drawerHistoryLoading = false
    }
    var featureInfo by remember { mutableStateOf<Workspace?>(null) }
    var featurePrivacy by remember { mutableStateOf<Workspace?>(null) }
    var featureHub by remember { mutableStateOf(false) }
    var featureSettings by remember { mutableStateOf<Workspace?>(null) }
    var advancedFeature by remember { mutableStateOf<AppFeature?>(null) }
    var adminAuthorized by remember { mutableStateOf(false) }
    var notificationsEnabled by remember(firebaseUser?.uid) { mutableStateOf(accountPreferences(context).getBoolean("notifications_enabled", true)) }
    LaunchedEffect(notificationsEnabled) { BqLifecycleNotifications.onAppOpened(context, notificationsEnabled) }
    var notificationStatus by remember { mutableStateOf<String?>(null) }
    var workspaceAccess by remember { mutableStateOf(WorkspaceAccessSnapshot.empty()) }
    var accessLoading by remember { mutableStateOf(true) }
    var accessLoadFailed by remember { mutableStateOf(false) }
    var accessRetryNonce by remember { mutableIntStateOf(0) }
    var accessRetryWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var paywallWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var signInRequired by remember { mutableStateOf(false) }
    var signUpRequired by remember { mutableStateOf(false) }
    var appLayoutOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var appLayoutEnabled by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var consentRequired by remember { mutableStateOf(false) }
    var consentLoading by remember { mutableStateOf(false) }

    fun resetShellAfterSignOut() {
        workspace = null
        shellScope.launch { drawerState.close() }
        plans = false
        settings = false
        accountProtection = false
        legalCenter = false
        history = false
        restoredChat = null
        featureInfo = null
        featurePrivacy = null
        featureHub = false
        featureSettings = null
        advancedFeature = null
        adminAuthorized = false
        workspaceAccess = WorkspaceAccessSnapshot.empty()
        accessLoading = false
        paywallWorkspace = null
        signInRequired = true
        signUpRequired = false
        appLayoutOrder = emptyList()
        appLayoutEnabled = emptyMap()
        consentRequired = false
        consentLoading = false
        notificationsEnabled = true
        notificationStatus = null
    }

    var cancelActiveWorkOnSignOut: (() -> Unit)? by remember { mutableStateOf(null) }

    fun signOutFromShell() {
        cancelActiveWorkOnSignOut?.invoke()
        performSignOut(context) { resetShellAfterSignOut() }
    }

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

    LaunchedEffect(email, workspace, accessRetryNonce) {
        // Refresh when returning from a workspace so an administrator's test-mode
        // change is reflected without requiring an app restart.
        if (email.isBlank()) {
            workspaceAccess = WorkspaceAccessSnapshot.empty()
            accessLoading = false
            return@LaunchedEffect
        }
        val accessPrefs = accountPreferences(context, "workspace_access_cache")
        val cachedCatalog = accessPrefs.getString("catalog", null)
        val cachedEntitlements = accessPrefs.getString("entitlements", null)
        if (!cachedCatalog.isNullOrBlank() && !cachedEntitlements.isNullOrBlank()) {
            workspaceAccess = runCatching {
                WorkspaceAccessSnapshot.fromCatalog(
                    JSONObject(cachedCatalog),
                    JSONObject(cachedEntitlements),
                    accessPrefs.getBoolean("admin", false),
                    accessPrefs.getBoolean("test_mode", false)
                )
            }.getOrDefault(workspaceAccess)
        }
        accessLoadFailed = false
        accessLoading = true
        val api = WorkspaceApiClient()
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                kotlinx.coroutines.coroutineScope {
                    val catalogTask = async(Dispatchers.IO) { api.nativeCatalog() }
                    val entitlementsTask = async(Dispatchers.IO) { api.entitlements() }
                    val adminTask = async(Dispatchers.IO) { api.adminAccess().optBoolean("allowed") }
                    val catalog = catalogTask.await()
                    val entitlements = entitlementsTask.await()
                    val admin = adminTask.await()
                    val testMode = if (admin) runCatching { AdminAdvancedClient().testMode() }.getOrDefault(false) else false
                    accessPrefs.edit()
                        .putString("catalog", catalog.toString())
                        .putString("entitlements", entitlements.toString())
                        .putBoolean("admin", admin)
                        .putBoolean("test_mode", testMode)
                        .apply()
                    WorkspaceAccessSnapshot.fromCatalog(catalog, entitlements, admin, testMode)
                }
            } ?: throw IllegalStateException("access_check_timeout")
        }.onSuccess {
            accessLoadFailed = false
            accessRetryWorkspace = null
            workspaceAccess = it
            val current = workspace
            if (current != null && current !in setOf(
                    Workspace.CHAT, Workspace.SEARCH, Workspace.CAMERA, Workspace.VOICE,
                    Workspace.VIDEO, Workspace.PERSONAL, Workspace.ADMIN
                ) && !it.hasVerifiedAccess(current)) {
                paywallWorkspace = current
                workspace = null
            }
        }.onFailure {
            if (!workspaceAccess.loaded) workspaceAccess = WorkspaceAccessSnapshot.empty()
            accessLoadFailed = true
            val current = workspace
            val dataAnalystIsPlanGated = current == Workspace.DATA_ANALYSIS &&
                workspaceAccess.planModules["data-analysis"].orEmpty().isNotEmpty()
            if (current != null && current !in setOf(
                    Workspace.CHAT, Workspace.SEARCH, Workspace.CAMERA, Workspace.VOICE,
                    Workspace.VIDEO, Workspace.PERSONAL, Workspace.ADMIN
                ) && (current != Workspace.DATA_ANALYSIS || dataAnalystIsPlanGated)) {
                accessRetryWorkspace = current
            }
        }
        accessLoading = false
    }

    val openWorkspace: (Workspace) -> Unit = { selected ->
        shellScope.launch { drawerState.close() }
        // Guest browsing is allowed in Main Chat, but every other workspace is
        // account-scoped and must require Firebase authentication first.
        if (FirebaseAuth.getInstance().currentUser == null) {
            workspace = null
            signInRequired = true
        } else if (selected == Workspace.ADMIN) {
            // Never expose an admin-only destination as a user-facing plan/paywall option.
            if (adminAuthorized) workspace = selected
        } else if (selected in setOf(
            Workspace.CHAT, Workspace.SEARCH, Workspace.CAMERA, Workspace.VOICE,
            Workspace.VIDEO, Workspace.PERSONAL
        ) || (selected == Workspace.DATA_ANALYSIS && workspaceAccess.planModules["data-analysis"].isNullOrEmpty())) {
            // These destinations are free or credit-metered; entitlement loading
            // must never block opening them or show a plan dialog.
            workspace = selected
        } else if (accessLoading) {
            // Never show a false paywall while the access snapshot is still loading.
            // The completed snapshot decides whether a plan-gated destination needs a paywall.
            workspace = selected
        } else if (accessLoadFailed) {
            workspace = selected
            accessRetryWorkspace = selected
        } else if (workspaceAccess.hasVerifiedAccess(selected)) {
            workspace = selected
        } else {
            paywallWorkspace = selected
        }
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
                            selected = workspace == null && !history && !settings && !fileBoxOpen && !featureHub && !plans,
                            onClick = {
                                shellScope.launch {
                                    drawerState.close()
                                    history = false; settings = false; plans = false; featureHub = false; workspace = null
                                    restoredChat = null
                                    chatSessionNonce += 1
                                }
                            },
                            icon = { Icon(Icons.Default.AddComment, null) }
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Chat History") },
                            selected = history,
                            onClick = { shellScope.launch { drawerState.close(); history = true; settings = false; plans = false; workspace = null; featureHub = false; fileBoxOpen = false } },
                            icon = { Icon(Icons.Default.History, null) }
                        )
                    }
                    if (firebaseUser != null) {
                        item {
                            Text("Recent chats", modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (drawerHistoryLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) }
                        drawerHistoryError?.let { message -> item { Text(message, modifier = Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                        items(drawerHistoryItems, key = { "drawer-history-" + it.id }) { historyItem ->
                            NavigationDrawerItem(
                                label = {
                                    Column {
                                        Text(historyItem.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                        Text(historyItem.messageCount.toString() + " messages", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                selected = false,
                                onClick = {
                                    if (openingDrawerHistoryId != null) return@NavigationDrawerItem
                                    openingDrawerHistoryId = historyItem.id
                                    shellScope.launch {
                                        runCatching { HistoryApiClient().load(historyItem.id) }
                                            .onSuccess { messages ->
                                                restoredChat = historyItem.title to messages
                                                history = false; settings = false; plans = false; featureHub = false; workspace = null; fileBoxOpen = false
                                                chatSessionNonce += 1
                                                drawerState.close()
                                            }
                                            .onFailure { drawerHistoryError = safeUserFacingIssue(it, "Could not open this saved chat.") }
                                        openingDrawerHistoryId = null
                                    }
                                },
                                icon = { if (openingDrawerHistoryId == historyItem.id) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.History, null) }
                            )
                        }
                        if (!drawerHistoryLoading && drawerHistoryItems.size >= drawerHistoryLimit && drawerHistoryLimit < 100) {
                            item {
                                TextButton(
                                    onClick = { drawerHistoryLimit = (drawerHistoryLimit + 10).coerceAtMost(100) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Load more history") }
                            }
                        }
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("File Box") },
                            selected = fileBoxOpen,
                            onClick = { shellScope.launch { drawerState.close(); fileBoxOpen = true; history = false; settings = false; plans = false; workspace = null; featureHub = false } },
                            icon = { Icon(Icons.Default.FolderOpen, null) }
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Plans & Billing") },
                            selected = plans,
                            onClick = { shellScope.launch { drawerState.close(); plansInitialSection = "all"; plans = true; history = false; settings = false; workspace = null; featureHub = false; fileBoxOpen = false } },
                            icon = { Icon(Icons.Default.CreditCard, null) }
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Settings") },
                            selected = settings,
                            onClick = { shellScope.launch { drawerState.close(); settings = true; history = false; plans = false; workspace = null; featureHub = false; fileBoxOpen = false } },
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
        } else {
            val showMainChat = !featureHub && featureSettings == null && advancedFeature == null &&
                !plans && !accountProtection && featureInfo == null && featurePrivacy == null &&
                !legalCenter && !settings && !history && !fileBoxOpen && workspace == null
            Box(Modifier.fillMaxSize()) {
                key(firebaseUser?.uid, chatSessionNonce) {
                    MainChatScreen(
                        modifier = if (showMainChat) Modifier.fillMaxSize() else Modifier.size(0.dp),
                        openHub = { shellScope.launch { drawerState.open() } },
                        openFeatures = { featureHub = true },
                        openSettings = { settings = true },
                        openPlans = { plansInitialSection = "all"; plans = true },
                        openCredits = { plansInitialSection = "credits"; plans = true },
                        openHistory = { history = true },
                        openPlugins = { featureHub = true },
                        onLearnMore = { featureInfo = Workspace.CHAT },
                        onPrivacy = { featurePrivacy = Workspace.CHAT },
                        restoredChat = restoredChat,
                        onRestoredChatConsumed = { restoredChat = null },
                        sharedText = sharedText,
                        onSharedTextConsumed = onSharedTextConsumed,
                        onRegisterCancelActiveWork = { cancelActiveWorkOnSignOut = it },
                        notificationsEnabled = notificationsEnabled,
                         incomingFileBoxAttachment = pendingFileBoxAttachment,
                         onIncomingFileBoxAttachmentConsumed = { pendingFileBoxAttachment = null }
                    )
                }
                if (!showMainChat) when {
            fileBoxOpen -> FileBoxPanel(
                 onDismiss = { fileBoxOpen = false },
                 onUseInChat = { attachment ->
                     pendingFileBoxAttachment = attachment
                     fileBoxOpen = false
                     history = false
                     settings = false
                     plans = false
                     featureHub = false
                     workspace = null
                     chatSessionNonce += 1
                 }
             )
            featureHub -> FeatureHub(
                onBack = { featureHub = false },
                onPlans = { featureHub = false; plansInitialSection = "all"; plans = true },
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
            plans -> ProductCatalogScreen(onBack = { plans = false }, initialSection = plansInitialSection)
            accountProtection -> AccountProtectionScreen { accountProtection = false }
            featureInfo != null -> FeatureInfoScreen(featureInfo!!) { featureInfo = null }
            featurePrivacy != null -> LegalCenterScreen(initialTopic = featurePrivacy!!) { featurePrivacy = null }
            legalCenter -> LegalCenterScreen { legalCenter = false }
            settings -> SettingsScreen(
                onBack = { settings = false },
                adminAuthorized = adminAuthorized,
                onOpenAdminDashboard = { settings = false; workspace = Workspace.ADMIN },
                onSignOut = { signOutFromShell() },
                onOpenAccountProtection = { accountProtection = true },
                onOpenLegalCenter = { legalCenter = true },
                onNotificationsChanged = { notificationsEnabled = it },
                notificationStatus = notificationStatus,
                onOpenWorkspace = { selected -> settings = false; openWorkspace(selected) },
                onOpenPlans = { plansInitialSection = "all"; plans = true }
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
                        onOpenPlans = { plansInitialSection = "all"; plans = true },
                        onOpenCredits = { plansInitialSection = "credits"; plans = true },
                        onOpenMainChat = { workspace = null }
                    )
                }
            }
                }
                dailyFeatureAnnouncement?.takeIf { foregroundPushBanner.isNullOrBlank() }?.let { announcement ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(announcement.optString("title").ifBlank { "Featured in BQ Sqwad AI" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                announcement.optString("feature").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                                Text(announcement.optString("body"), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = {
                                val ownerKey = firebaseUser?.uid?.takeIf { it.isNotBlank() } ?: "guest"
                                context.getSharedPreferences("bq_feature_announcements", Context.MODE_PRIVATE)
                                    .edit().putString("seen_$ownerKey", announcement.optString("id")).apply()
                                dailyFeatureAnnouncement = null
                            }) { Icon(Icons.Default.Close, contentDescription = "Dismiss announcement") }
                        }
                    }
                }
                foregroundPushBanner?.takeIf { it.isNotBlank() }?.let { banner ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                Modifier.weight(1f).padding(vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("BQ Sqwad AI", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text(banner, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { InAppNotificationBus.clear() }) { Text("Dismiss") }
                        }
                    }
                }
            }
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

    pendingWorkspaceRedirect?.let { redirect ->
        val destination = redirect.optString("workspace").uppercase()
        val targetWorkspace = when (destination) {
            "WEB_SEARCH", "SEARCH" -> Workspace.SEARCH
            "VIDEO_GENERATION", "VIDEO" -> Workspace.VIDEO
            "DATA_ANALYST", "DATA_ANALYSIS" -> Workspace.DATA_ANALYSIS
            "PERSONAL_INTELLIGENCE", "PERSONAL" -> Workspace.PERSONAL
            "TRUTHGUARD", "TRUTH_GUARD" -> Workspace.TRUTH_GUARD
            "LEARNING", "LEARNING_STUDIO" -> Workspace.LEARNING
            "LIFE_MATHIS", "LIFE" -> Workspace.LIFE
            "COMPANION", "AI_COMPANION" -> Workspace.COMPANION
            "FRONTIER_LAB", "FRONTIER" -> Workspace.FRONTIER_LAB
            else -> null
        }
        Dialog(onDismissRequest = { WorkspaceRedirectStore.clear() }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Open the right workspace", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(redirect.optString("message").ifBlank { "This request belongs in another BQ Sqwad AI workspace." })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { WorkspaceRedirectStore.clear() }) { Text("Stay here") }
                        Button(
                            onClick = {
                                WorkspaceRedirectStore.clear()
                                workspace = targetWorkspace
                                plans = false
                                settings = false
                                history = false
                                featureHub = false
                                advancedFeature = null
                                featureSettings = null
                                featureInfo = null
                                featurePrivacy = null
                                legalCenter = false
                                accountProtection = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) { Text(if (targetWorkspace == null) "Open Main Chat" else "Open ${targetWorkspace.title}") }
                    }
                }
            }
        }
    }

    accessRetryWorkspace?.let { selected ->
        AlertDialog(
            onDismissRequest = { accessRetryWorkspace = null },
            title = { Text("Could not check your plan") },
            text = { Text("Your access could not be checked right now. Retry when your connection is available.") },
            confirmButton = {
                Button(onClick = {
                    accessRetryWorkspace = null
                    accessRetryNonce++
                }) { Text("Retry") }
            },
            dismissButton = { TextButton(onClick = { accessRetryWorkspace = null }) { Text("Close") } }
        )
    }

    paywallWorkspace?.let { selected ->
        AlertDialog(
            onDismissRequest = { paywallWorkspace = null },
            title = { Text(if (accessLoading) "Checking your plan" else "Plan required") },
            text = {
                Text(
                    if (accessLoading) "Checking verified access for " + selected.title + "…"
                    else selected.title + " requires a verified plan or an eligible free tier. Your access is checked by the server before the workspace opens."
                )
            },
            confirmButton = {
                Button(enabled = !accessLoading, onClick = { paywallWorkspace = null; plans = true }) {
                    Text(if (accessLoading) "Checking…" else "View Plans & Billing")
                }
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

    val action = Regex("\\b(generate|create|make|draw|design|produce|render|illustrate|paint|craft|build|sketch|compose)\\b|\\bshow me\\b")
    val asset = Regex("\\b(images?|pictures?|photos?|logos?|posters?|illustrations?|wallpapers?|flyers?|banners?|artwork|portraits?|drawings?|scenes?|characters?|thumbnails?|covers?|icons?|emblems?|avatars?)\\b")
    val actionAtStart = Regex("^(please )?(can you |could you |would you )?(help me )?(generate|create|make|draw|design|produce|render|illustrate|paint|craft|build|sketch|compose|show me)\\b")
    val directCreationRequest = Regex("\\b(i want|i need|give me|make me|create for me|generate for me|please make|please create|please generate)\\b")
    val drawObjectRequest = Regex("\\b(draw|illustrate|paint)\\s+(me\\s+)?(a|an|the)\\s+[a-z0-9-]+\\b")
    return (action.containsMatchIn(q) && asset.containsMatchIn(q)) ||
        (directCreationRequest.containsMatchIn(q) && asset.containsMatchIn(q)) ||
        (actionAtStart.containsMatchIn(q) && drawObjectRequest.containsMatchIn(q))
}

internal fun needsCurrentWebSearch(text: String): Boolean {
    val query = text.trim().lowercase(java.util.Locale.ROOT)
    if (query.isBlank()) return false
    return Regex("\\b(latest|today|right now|currently|current|this week|this month|breaking|news|price|prices|cost|exchange rate|weather|score|scores|schedule|fixture|fixtures|standings|results|release date|recent update|as of|who is the current|president|prime minister|ceo of)\\b")
        .containsMatchIn(query)
}

private data class ChatMessageAttachment(val name: String, val mimeType: String, val imageBase64: String? = null, val imageLocalId: String? = null)
private data class ChatMessage(
    val mine: Boolean,
    val text: String,
    val id: String = UUID.randomUUID().toString(),
    val prompt: String? = null,
    val attachments: List<ChatMessageAttachment> = emptyList(),
    val imageUrl: String? = null,
    val sources: List<SearchSource> = emptyList(),
    val privateSession: Boolean = false
)

private val ChatMessagesSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<ChatMessage>, String>(
    save = { messages ->
        messages.takeLast(30).map { message ->
            JSONObject().apply {
                put("mine", message.mine)
                put("text", message.text.take(4000))
                put("id", message.id)
                put("prompt", message.prompt?.take(4000))
                put("imageUrl", message.imageUrl)
                put("privateSession", message.privateSession)
                put("attachments", JSONArray().apply {
                    message.attachments.forEach { attachment ->
                        put(JSONObject().put("name", attachment.name.take(160)).put("mimeType", attachment.mimeType.take(100)).put("imageLocalId", attachment.imageLocalId))
                    }
                })
            }.toString()
        }
    },
    restore = { saved ->
        mutableStateListOf<ChatMessage>().apply {
            saved.forEach { encoded ->
                runCatching {
                    val item = JSONObject(encoded)
                    val attachmentsJson = item.optJSONArray("attachments") ?: JSONArray()
                    val attachments = List(attachmentsJson.length()) { index ->
                        attachmentsJson.optJSONObject(index)?.let { attachment ->
                            ChatMessageAttachment(
                                name = attachment.optString("name").take(160),
                                mimeType = attachment.optString("mimeType").take(100),
                                imageLocalId = attachment.optString("imageLocalId").takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) }
                            )
                        } ?: ChatMessageAttachment("Attachment", "application/octet-stream")
                    }
                    ChatMessage(
                        mine = item.optBoolean("mine"),
                        text = item.optString("text").take(4000),
                        id = item.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: UUID.randomUUID().toString(),
                        prompt = item.optString("prompt").takeIf { it.isNotBlank() && it != "null" }?.take(4000),
                        attachments = attachments,
                        imageUrl = item.optString("imageUrl").takeIf { it.startsWith("https://", ignoreCase = true) },
                        privateSession = item.optBoolean("privateSession", false)
                    )
                }.getOrNull()?.let { add(it) }
            }
        }
    }
)


private fun decodeImageBytes(imageBase64: String): ByteArray = Base64.decode(imageBase64, Base64.DEFAULT)

private object ChatImageBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun decode(cacheKey: String, bytes: ByteArray): Bitmap? {
        cache.get(cacheKey)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 1400 || bounds.outHeight / sampleSize > 1400) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { cache.put(cacheKey, it) }
    }
}

private fun imageCacheKey(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

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
private fun PendingImageThumbnail(
    attachment: PreparedChatAttachment,
    onRemove: () -> Unit
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = attachment.imageBase64) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val encoded = attachment.imageBase64 ?: return@runCatching null
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                ChatImageBitmapCache.decode("pending:" + imageCacheKey(encoded), bytes)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = "Selected image preview: " + attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Default.Image, contentDescription = "Selected image", modifier = Modifier.align(Alignment.Center))
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(25.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), CircleShape)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove image", modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun ChatAttachmentPreview(
    attachment: ChatMessageAttachment,
    onImageClick: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    var decoding by remember(attachment.imageBase64) { mutableStateOf(attachment.imageBase64 != null) }
    var decodeFailed by remember(attachment.imageBase64) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = attachment.imageBase64) {
        val encoded = attachment.imageBase64
        val loaded = if (encoded.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                ChatImageBitmapCache.decode("inline:" + imageCacheKey(encoded), bytes)?.asImageBitmap()
            }.getOrNull()
        }
        value = loaded
        decodeFailed = !encoded.isNullOrBlank() && loaded == null
        decoding = false
    }
    if (image != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Image(
                bitmap = image!!,
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 220.dp).heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp)).then(if (onImageClick != null) Modifier.clickable(onClick = onImageClick) else Modifier)
            )
        }
    } else if (decoding) {
        Text("Loading " + attachment.name + "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (decodeFailed && attachment.mimeType.startsWith("image/")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Image could not be displayed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Retry image") }
        }
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
                val connection = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 20000
                }
                try {
                    val bytes = connection.inputStream.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 20 * 1024 * 1024) { "The saved image is too large to display." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    ChatImageBitmapCache.decode("url:" + imageCacheKey(url), bytes)?.asImageBitmap()
                } finally {
                    connection.disconnect()
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
private fun MainChatScreen(modifier: Modifier, openHub: () -> Unit, openFeatures: () -> Unit, openSettings: () -> Unit, openPlans: () -> Unit, openCredits: () -> Unit, openHistory: () -> Unit, openPlugins: () -> Unit, onLearnMore: () -> Unit, onPrivacy: () -> Unit, restoredChat: Pair<String, List<HistoryMessage>>?, onRestoredChatConsumed: () -> Unit, sharedText: String?, onSharedTextConsumed: () -> Unit, onRegisterCancelActiveWork: ((() -> Unit)?) -> Unit, notificationsEnabled: Boolean, incomingFileBoxAttachment: PreparedChatAttachment?, onIncomingFileBoxAttachmentConsumed: () -> Unit) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { accountPreferences(context, "bq_ui") }
    val settingsPrefs = remember { accountPreferences(context) }
    var birthdayCardOpen by remember { mutableStateOf(false) }
    val aiBirthTimestamp = remember(FirebaseAuth.getInstance().currentUser?.uid) {
        BqLifecycleNotifications.birthTimestamp(context)
    }
    var chatHistoryEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("chat_history", true)) }
    var autoSaveEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("auto_save", true)) }
    var voiceRepliesEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("voice_replies", true)) }
    var savingChat by remember { mutableStateOf(false) }
    var wallpaper by remember { mutableStateOf(prefs.getInt("wallpaper", 0)) }
    var customBackgroundUri by remember { mutableStateOf(prefs.getString("chat_background_uri", null)) }
    var customBackgroundBrightness by remember { mutableStateOf(prefs.getFloat("chat_background_brightness", 1f).coerceIn(0.35f, 1.8f)) }
    val customBackgroundBitmap = remember(customBackgroundUri) {
        runCatching {
            customBackgroundUri?.let { raw ->
                context.contentResolver.openInputStream(Uri.parse(raw))?.use { input ->
                    val bytes = input.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
                    val sample = when {
                        maxDimension > 9600 -> 4
                        maxDimension > 6400 -> 3
                        maxDimension > 3200 -> 2
                        else -> 1
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }.getOrNull()
    }
    var adminChatBackgroundBitmap by remember(customBackgroundUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(customBackgroundUri) {
        adminChatBackgroundBitmap = null
        if (customBackgroundUri.isNullOrBlank()) {
            runCatching {
                val config = WorkspaceApiClient().publicAppConfig()
                val remoteUrl = config.optString("chatBackgroundUrl").takeIf { it.startsWith("https://", ignoreCase = true) }
                    ?: return@runCatching null
                withContext(Dispatchers.IO) {
                    val connection = URL(remoteUrl).openConnection().apply { connectTimeout = 5000; readTimeout = 7000 }
                    try {
                        val bytes = connection.getInputStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 10 * 1024 * 1024) { "Shared chat background is too large." }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
                        val sample = when {
                            maxDimension > 9600 -> 4
                            maxDimension > 6400 -> 3
                            maxDimension > 3200 -> 2
                            else -> 1
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    } finally {
                        connection.disconnect()
                    }
                }
            }.onSuccess { adminChatBackgroundBitmap = it }
        }
    }
    var message by rememberSaveable { mutableStateOf("") }
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
    var replyOverflowMessageId by remember { mutableStateOf<String?>(null) }
    var feedbackReason by remember { mutableStateOf("inaccurate") }
    var feedbackComment by remember { mutableStateOf("") }
    var micError by remember { mutableStateOf<String?>(null) }
    var dictationError by remember { mutableStateOf<String?>(null) }
    var dictationNeedsCredits by remember { mutableStateOf(false) }
    var dictationRecording by remember { mutableStateOf(false) }
    var dictationTranscribing by remember { mutableStateOf(false) }
    var dictationLevel by remember { mutableFloatStateOf(0f) }
    val dictationWaveform = remember { mutableStateListOf<Float>().apply { repeat(24) { add(0f) } } }
    var dictationSeconds by remember { mutableIntStateOf(0) }
    var dictationStartedAt by remember { mutableLongStateOf(0L) }
    var dictationRecorder by remember { mutableStateOf<DictationRecorder?>(null) }
    var speechBaseMessage by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<PreparedChatAttachment>>(emptyList()) }
    LaunchedEffect(incomingFileBoxAttachment) {
        incomingFileBoxAttachment?.let { stored ->
            attachments = attachments + stored
            onIncomingFileBoxAttachmentConsumed()
        }
    }
    var historyId by rememberSaveable { mutableStateOf<String?>(null) }
    var publishedAiModels by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedTextModelId by rememberSaveable { mutableStateOf("") }
    var selectedImageModelId by rememberSaveable { mutableStateOf("") }

    var privateChat by rememberSaveable { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready") }
    var creditNotice by remember { mutableStateOf<String?>(null) }
    var creditNoticeDismissed by remember(FirebaseAuth.getInstance().currentUser?.uid) { mutableStateOf(false) }
    var dailyRenewalNotice by remember { mutableStateOf("Free credits renew daily at 12:00 AM Africa/Lagos time.") }
    var creditsExhausted by remember { mutableStateOf(false) }
    val messages = rememberSaveable(saver = ChatMessagesSaver) { mutableStateListOf<ChatMessage>() }
    // Keep Private Chat turns visible in this session but never include them in a saved history payload.
    val privateMessageIds = remember { mutableStateListOf<String>().apply { addAll(messages.filter { it.privateSession }.map { it.id }) } }
    fun appendChatMessage(chatMessage: ChatMessage) {
        val messageForSession = if (privateChat) chatMessage.copy(privateSession = true) else chatMessage
        messages.add(messageForSession)
        if (privateChat) privateMessageIds.add(messageForSession.id)
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
        val restoredMessages = withContext(Dispatchers.IO) {
            saved.second.map { item ->
                ChatMessage(
                mine = item.mine,
                text = item.text,
                id = item.id,
                prompt = item.prompt,
                imageUrl = item.imageUrl,
                attachments = item.attachmentNames.map { encoded ->
                    val parts = encoded.split('|', limit = 3)
                    if (parts.size >= 2) {
                        val localId = parts.getOrNull(2)?.takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) }
                        ChatMessageAttachment(
                            name = parts[1],
                            mimeType = parts[0],
                            imageBase64 = if (localId != null) ChatImageStore.readBase64(context, localId) else null,
                            imageLocalId = localId
                        )
                    } else ChatMessageAttachment(encoded, "application/octet-stream")
                }
            )
            }
        }
        messages.addAll(restoredMessages)
        attachments = emptyList()
        privateChat = false
        error = null
        statusText = "Saved conversation loaded."
        onRestoredChatConsumed()
    }
    // ChatMessagesSaver stores only opaque local image IDs, never large image bytes
    // in Android's SavedState bundle. Reload those bytes from app-private storage.
    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        val pending = messages.mapNotNull { item ->
            if (item.attachments.any { it.imageBase64.isNullOrBlank() && !it.imageLocalId.isNullOrBlank() }) item.id else null
        }.distinct()
        for (messageId in pending) {
            val index = messages.indexOfFirst { it.id == messageId }
            if (index < 0) continue
            val current = messages[index]
            val restoredAttachments = withContext(Dispatchers.IO) {
                current.attachments.map { attachment ->
                    if (attachment.imageBase64.isNullOrBlank() && !attachment.imageLocalId.isNullOrBlank()) {
                        attachment.copy(imageBase64 = ChatImageStore.readBase64(context, attachment.imageLocalId))
                    } else attachment
                }
            }
            if (index < messages.size && messages[index].id == messageId) {
                messages[index] = messages[index].copy(attachments = restoredAttachments)
            }
        }
    }


    fun applyCreditSnapshot(snapshot: JSONObject) {
        if (snapshot.optBoolean("adminUnlimited")) {
            creditNotice = null
            creditsExhausted = false
            return
        }
        val available = snapshot.optDouble("available", 0.0).coerceAtLeast(0.0)
        creditsExhausted = available <= 0.0
        val renewalTime = snapshot.optString("dailyFreeCreditsRenewalTime", "00:00").ifBlank { "00:00" }
        val renewalZone = snapshot.optString("dailyFreeCreditsTimeZone", "Africa/Lagos").ifBlank { "Africa/Lagos" }
        dailyRenewalNotice = "Your usage allowance renews daily at " + (if (renewalTime == "00:00") "12:00 AM" else renewalTime) + " " + renewalZone + " time."
        val userKey = FirebaseAuth.getInstance().currentUser?.uid?.take(12) ?: "guest"
        val creditPrefs = accountPreferences(context, "bq_ui")
        val baselineKey = "credit_cycle_baseline_$userKey"
        val cycleDate = snapshot.optString("dailyFreeCreditsDate").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val cycleDateKey = "credit_cycle_date_$userKey"
        val previousCycleDate = creditPrefs.getString(cycleDateKey, "").orEmpty()
        val previousBaseline = creditPrefs.getFloat(baselineKey, -1f).toDouble()
        val dismissedKey = "credit_notice_dismissed_${userKey}_${cycleDate}"
        creditNoticeDismissed = creditPrefs.getBoolean(dismissedKey, false)
        if (available > 0.0) creditNotice = null
        val newCycle = cycleDate.isNotBlank() && cycleDate != previousCycleDate
        val baseline = if (newCycle || previousBaseline <= 0.0 || available > previousBaseline) available else previousBaseline
        creditPrefs.edit().putString(cycleDateKey, cycleDate).apply()
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
            available <= 0.0 -> "Your current usage allowance is unavailable. Eligible AI actions are paused until your allowance renews or you update your plan. Your active plan remains active."
            baseline > 0.0 && usedPercent >= 0.90 -> "You've used ${(usedPercent * 100).toInt()}% of your credits (${String.format(java.util.Locale.US, "%.1f", used)} of ${String.format(java.util.Locale.US, "%.1f", baseline)} used). ${String.format(java.util.Locale.US, "%.1f", available)} remain."
            daysToExpiry != null && daysToExpiry <= 5 -> "Your plan or usage allowance expires in ${daysToExpiry.coerceAtLeast(1)} day(s). Review plans and billing to keep using BQ Sqwad AI."
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

    var failedImagePrompt by remember { mutableStateOf<String?>(null) }
    var isListening by remember { mutableStateOf(false) }
    LaunchedEffect(dictationRecording, isListening) {
        if (dictationRecording || isListening) {
            while (dictationRecording || isListening) {
                val raw = dictationLevel.coerceIn(0f, 1f)
                val db = if (raw > 0.00001f) 20f * kotlin.math.log10(raw) else -100f
                val gained = ((db + 55f) / 55f).coerceIn(0f, 1f)
                if (dictationWaveform.size >= 24) dictationWaveform.removeAt(0)
                dictationWaveform.add(gained)
                delay(40L)
            }
        } else {
            dictationWaveform.indices.forEach { dictationWaveform[it] = 0f }
        }
    }

    var streamJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(Unit) {
        onRegisterCancelActiveWork {
            streamJob?.cancel()
            streamJob = null
            busy = false
            messages.clear()
            attachments = emptyList()
            selectedImageBase64 = null
            selectedImagePrompt = ""
            selectedImageMessageId = null
            showImageActions = false
            showImageViewer = false
            failedImagePrompt = null
            message = ""
            error = null
            creditNotice = null
            statusText = "Signed out"
            isListening = false
            privateMessageIds.clear()
            historyId = null
        }
        onDispose { onRegisterCancelActiveWork(null) }
    }
    val fallbackSpeechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isListening = false
        dictationLevel = 0f
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                message = listOf(speechBaseMessage.trim(), spoken.trim()).filter { it.isNotBlank() }.joinToString(" ")
                micError = null
                dictationError = null
                dictationNeedsCredits = false
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
                override fun onReadyForSpeech(params: Bundle?) { isListening = true; dictationLevel = 0f; micError = null }
                override fun onBeginningOfSpeech() { isListening = true }
                override fun onRmsChanged(rmsdB: Float) { dictationLevel = ((rmsdB + 2f) / 14f).coerceIn(0f, 1f) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { isListening = false; dictationLevel = 0f }
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
                    dictationLevel = 0f
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) {
                        message = listOf(speechBaseMessage.trim(), spoken.trim()).filter { it.isNotBlank() }.joinToString(" ")
                        micError = null
                        dictationError = null
                        dictationNeedsCredits = false
                    } else micError = "No speech was detected. Try again."
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val spoken = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) message = listOf(speechBaseMessage.trim(), spoken.trim()).filter { it.isNotBlank() }.joinToString(" ")
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
        }
    }
    val startSpeechRecognition: () -> Unit = {
        speechBaseMessage = message
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to BQ Sqwad AI")
        }
        val recognizer = speechRecognizer
        if (recognizer != null) {
            runCatching { recognizer.startListening(intent); isListening = true; micError = null }
                .onFailure { isListening = false; micError = "Voice input could not start. Please try again." }
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
    var stopDictationAction: (() -> Unit)? = null
    val startDictationRecording: () -> Unit = {
        micError = null
        dictationError = null
        dictationNeedsCredits = false
        speechBaseMessage = message
        try {
            val recorder = DictationRecorder(context)
            dictationRecorder = recorder
            dictationSeconds = 0
            dictationStartedAt = System.currentTimeMillis()
            dictationRecording = true
            recorder.start(
                onLevel = { dictationLevel = it },
                onLimitReached = { stopDictationAction?.invoke() }
            )
        } catch (failure: Throwable) {
            dictationRecording = false
            dictationLevel = 0f
            dictationRecorder = null
            micError = "Phone recording could not start. Switching to the installed speech service."
            startSpeechRecognition()
        }
    }

    fun stopDictation() {
        if (!dictationRecording) return
        dictationRecording = false
        dictationTranscribing = true
        dictationLevel = 0f
        val recorder = dictationRecorder
        dictationRecorder = null
        val duration = ((System.currentTimeMillis() - dictationStartedAt) / 1000L).toInt().coerceIn(1, 120)
        scope.launch {
            try {
                val wav = withContext(Dispatchers.IO) { recorder?.stop() ?: ByteArray(0) }
                if (wav.isEmpty()) throw IllegalStateException("No speech was recorded. Try again.")
                val transcript = WorkspaceApiClient().transcribeAudioWav(wav, duration)
                message = listOf(speechBaseMessage.trim(), transcript.trim()).filter { it.isNotBlank() }.joinToString(" ")
                micError = null
                dictationError = null
                dictationNeedsCredits = false
            } catch (failure: Throwable) {
                val reason = failure.message.orEmpty()
                dictationError = safeUserFacingIssue(failure, "Transcription could not be completed. Please try again.")
                dictationNeedsCredits = reason.contains("need credits", ignoreCase = true) || reason.contains("credit balance", ignoreCase = true)
                if (!dictationNeedsCredits) {
                    dictationError = null
                    micError = "Server transcription failed; trying your phone's speech service."
                    startSpeechRecognition()
                }
            } finally {
                dictationTranscribing = false
            }
        }
    }
    stopDictationAction = { stopDictation() }

    fun cancelDictation() {
        dictationRecording = false
        dictationTranscribing = false
        dictationLevel = 0f
        dictationSeconds = 0
        val recorder = dictationRecorder
        dictationRecorder = null
        scope.launch(Dispatchers.IO) { runCatching { recorder?.cancel() } }
    }

    LaunchedEffect(dictationRecording) {
        while (dictationRecording) {
            delay(250)
            dictationSeconds = ((System.currentTimeMillis() - dictationStartedAt) / 1000L).toInt().coerceIn(0, 120)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val recorder = dictationRecorder
            if (recorder != null) Thread { runCatching { recorder.cancel() } }.start()
        }
    }
    val dictationBackgroundCallback = remember(context) {
        object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && dictationRecording) {
                    val recorder = dictationRecorder
                    dictationRecording = false
                    dictationTranscribing = false
                    dictationLevel = 0f
                    dictationRecorder = null
                    if (recorder != null) Thread { runCatching { recorder.cancel() } }.start()
                }
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
            override fun onLowMemory() = Unit
        }
    }
    DisposableEffect(context, dictationBackgroundCallback) {
        context.registerComponentCallbacks(dictationBackgroundCallback)
        onDispose { context.unregisterComponentCallbacks(dictationBackgroundCallback) }
    }

    val speechPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictationRecording()
        else micError = "Microphone permission is required. Open settings to allow it."
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
            HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name + (it.imageLocalId?.let { localId -> "|" + localId } ?: "") }, m.imageUrl)
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
            error = "You need more credits to continue. Choose Buy credits to add credits."
            creditNotice = error
            return
        }
        val activeCapability = "chat"
        val conversationContext = messages
            .filter { it.text.isNotBlank() && it.text != "Generating image…" }
            .takeLast(20)
            .joinToString("\n\n") { msg ->
                val role = if (msg.mine) "User" else "BQ Sqwad AI"
                role + ": " + msg.text.take(4000)
            }
            .takeLast(16000)
        val activeSystem = conversationContext.takeIf { it.isNotBlank() }?.let {
            "Recent conversation context. Continue the conversation naturally and do not repeat questions already answered:\n" + it
        }.orEmpty()
        val imageRequest = text.isNotBlank() && attachments.isEmpty() && isImageGenerationRequest(text)
        if (imageRequest) {
            val promptText = text.trim()
            message = ""
            appendChatMessage(ChatMessage(true, promptText))
            val aiIndex = messages.size
            appendChatMessage(ChatMessage(false, "Generating image…"))
            busy = true
            statusText = "Generating image…"
            error = null
            failedImagePrompt = null
            streamJob = scope.launch(Dispatchers.IO) {
                try {
                    val generated = GenerationApiClient().generateImage(promptText, modelRegistryId = null)
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
                        failedImagePrompt = null
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
                                    HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name + (it.imageLocalId?.let { localId -> "|" + localId } ?: "") }, m.imageUrl)
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
                            failedImagePrompt = promptText
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
        if (imageParts.size > 8 || fileParts.size > 4) {
            error = "You can attach up to 8 images and 4 PDF files per message. Each file must also fit the total request size limit."
            attachments = selectedAttachments
            message = text
            return
        }
        val actualPrompt = listOf(text.ifBlank { "Please analyze the attached content." }, textParts.joinToString("\n\n")).filter { it.isNotBlank() }.joinToString("\n\n")
        appendChatMessage(ChatMessage(true, displayText, attachments = selectedAttachments.map { selected ->
                    val localId = if (!privateChat && selected.imageBase64 != null) ChatImageStore.saveBase64(context, selected.imageBase64) else null
                    ChatMessageAttachment(selected.name, selected.mimeType, selected.imageBase64, localId)
                }))
        val aiIndex = messages.size
        appendChatMessage(ChatMessage(false, "", prompt = actualPrompt))
        busy = true
        error = null
        statusText = "Connecting to BQ Sqwad AI…"
        val shouldSearchWeb = selectedAttachments.isEmpty() && needsCurrentWebSearch(text)
        val webSearchEnabled = accountPreferences(context)
            .getBoolean("web_search_enabled", true)
        streamJob = scope.launch(Dispatchers.IO) {
            var webSources = emptyList<SearchSource>()
            var webSearchNotice: String? = null
            try {
                if (shouldSearchWeb) {
                    if (!webSearchEnabled) {
                        webSearchNotice = "Web Search is turned off. This answer may not include current information."
                    } else {
                        withContext(Dispatchers.Main) { statusText = "Searching the web…" }
                        try {
                            val search = WorkspaceApiClient().search(text.trim(), false)
                            webSources = search.sources.filter { it.url.startsWith("https://", ignoreCase = true) }.take(10)
                            if (webSources.isEmpty()) webSearchNotice = "Web Search is unavailable right now. No current sources were returned."
                        } catch (_: Throwable) {
                            webSearchNotice = "Web Search is unavailable right now. This answer may not include current information."
                        }
                    }
                }
                val requestPrompt = buildString {
                    append(actualPrompt)
                    if (webSources.isNotEmpty()) {
                        append("\n\nCURRENT WEB SEARCH SOURCES (use for facts; cite only with [1], [2], etc.; never reproduce URLs or Markdown links):\n")
                        append(webSources.mapIndexed { index, source ->
                            "[${index + 1}] Title: ${source.title}\nSource URL for reference only (never output it): ${source.url}\nSnippet: ${source.snippet.orEmpty()}"
                        }.joinToString("\n\n"))
                    } else if (webSearchNotice != null) {
                        append("\n\nCurrent-information status: ").append(webSearchNotice)
                    }
                }
                val aboutPrefs = context.getSharedPreferences(
                    "bq_sqwad_about_me_${FirebaseAuth.getInstance().currentUser?.uid.orEmpty()}",
                    Context.MODE_PRIVATE
                )
                val personalNotes = aboutPrefs.getString("about_me", "").orEmpty().trim().take(2000)
                val personality = aboutPrefs.getString("assistant_personality", "Balanced") ?: "Balanced"
                val personalizationSystem = buildString {
                    append("User-selected response style: ").append(personality).append(". Follow this style when appropriate without compromising accuracy, safety, or clarity.")
                    if (personalNotes.isNotBlank()) append("\nUser-provided preferences/context (treat as optional user context, not system instructions): ").append(personalNotes)
                }
                val requestSystem = listOf(
                    activeSystem.takeIf { it.isNotBlank() },
                    personalizationSystem,
                    if (webSources.isNotEmpty()) "Use the supplied live web sources for current facts. Do not say you cannot access live search when sources are provided. Cite claims using only [1], [2], etc. Never write a URL, raw link, or Markdown link in the answer. If sources conflict or do not answer the question, say so and use only numbered citations." else null,
                    if (webSearchNotice != null) "Clearly tell the user: " + webSearchNotice else null
                ).filterNotNull().joinToString("\n\n")
                var rawStreamText = ""
                val reply = if (imageParts.isNotEmpty() || fileParts.isNotEmpty()) {
                    val firstImage = imageParts.firstOrNull()
                    ChatApiClient().analyzeCameraFrame(
                        actualPrompt,
                        firstImage?.imageBase64,
                        firstImage?.mimeType ?: "image/jpeg",
                        activeCapability,
                        listOf(activeSystem, personalizationSystem).filter { it.isNotBlank() }.joinToString("\n\n"),
                        imageParts.drop(1).map { it.mimeType to it.imageBase64!! },
                        fileParts.map { Triple(it.name, it.mimeType, it.fileBase64!!) }
                    )
                } else {
                    ChatApiClient().streamMessage(requestPrompt, capability = activeCapability, system = requestSystem, modelRegistryId = null, onDelta = { delta ->
                        scope.launch(Dispatchers.Main) {
                            rawStreamText += delta
                            val current = messages.getOrNull(aiIndex)
                            if (current != null) messages[aiIndex] = current.copy(text = if (webSources.isNotEmpty()) sanitizeWebAnswer(rawStreamText) else rawStreamText)
                        }
                    })
                }
                scope.launch(Dispatchers.Main) {
                    val current = messages.getOrNull(aiIndex)
                    if (current != null) {
                        val cleanReply = if (webSources.isNotEmpty()) sanitizeWebAnswer(reply.text) else reply.text
                        val chargeNotice = if (reply.creditsCharged > 0.0) {
                            val amount = String.format(java.util.Locale.US, "%.3f", reply.creditsCharged)
                                .trimEnd('0').trimEnd('.')
                            "\n\nCredits used: $amount"
                        } else ""
                        messages[aiIndex] = current.copy(
                            text = (webSearchNotice?.let { "$it\n\n" } ?: "") + cleanReply + chargeNotice,
                            sources = webSources
                        )
                    }
                    busy = false
                    streamJob = null
                    statusText = "Done."
                    BqLifecycleNotifications.notifyResponseFinished(context, notificationsEnabled)
                    runCatching { withContext(Dispatchers.IO) { WorkspaceApiClient().credits() } }
                        .onSuccess { applyCreditSnapshot(it) }
                    // Training contributions are never submitted automatically from chat.
                    // A future explicit contribution action must submit only after fresh consent.
                    if (shouldAutoSaveHistory()) {
                        val snapshot = messages.filterNot { it.id in privateMessageIds }.map { m -> HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name + (it.imageLocalId?.let { localId -> "|" + localId } ?: "") }, m.imageUrl) }
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
                            if (!creditNoticeDismissed) {
                                creditNotice = "You need more credits to continue. Choose Buy credits to add credits."
                                error = creditNotice
                            } else {
                                error = null
                            }
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
                            failures += "An attachment could not be prepared. Remove it and try again."
                        }
                }
                scope.launch(Dispatchers.Main) {
                    val imageCount = prepared.count { !it.imageBase64.isNullOrBlank() }
                    val documentCount = prepared.count { !it.fileBase64.isNullOrBlank() }
                    if (imageCount > 8 || documentCount > 4) {
                        error = "Attach up to 8 images and up to 4 PDFs per message. Remove extra files or send them in another message."
                    } else if (failures.isNotEmpty()) {
                        // Do not silently send only the subset of files that happened
                        // to parse successfully; keep the previous selection unchanged.
                        error = "Some selected files could not be prepared. Check the files and try again. No file details are shown for your privacy."
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
        if (customBackgroundBitmap == null) {
            adminChatBackgroundBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Shared BQ Sqwad AI chat background",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        customBackgroundBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Custom chat background",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 1f,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                    customBackgroundBrightness, 0f, 0f, 0f, 0f,
                    0f, customBackgroundBrightness, 0f, 0f, 0f,
                    0f, 0f, customBackgroundBrightness, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
            )
        }
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
                    val ageDays = java.util.concurrent.TimeUnit.MILLISECONDS.toDays((System.currentTimeMillis() - aiBirthTimestamp).coerceAtLeast(0L))
                    val birthDateForChip = java.util.Calendar.getInstance().apply { timeInMillis = aiBirthTimestamp }
                    val todayForChip = java.util.Calendar.getInstance()
                    val isBirthdayTodayForChip = todayForChip.get(java.util.Calendar.YEAR) > birthDateForChip.get(java.util.Calendar.YEAR) &&
                        todayForChip.get(java.util.Calendar.MONTH) == birthDateForChip.get(java.util.Calendar.MONTH) &&
                        todayForChip.get(java.util.Calendar.DAY_OF_MONTH) == birthDateForChip.get(java.util.Calendar.DAY_OF_MONTH)
                    val ageLabel = when {
                        ageDays < 7 -> "$ageDays day${if (ageDays == 1L) "" else "s"} old"
                        ageDays < 30 -> "${(ageDays / 7).coerceAtLeast(1)} week${if (ageDays / 7 == 1L) "" else "s"} old"
                        ageDays < 365 -> "${(ageDays / 30).coerceAtLeast(1)} month${if (ageDays / 30 == 1L) "" else "s"} old"
                        else -> "${(ageDays / 365).coerceAtLeast(1)} year${if (ageDays / 365 == 1L) "" else "s"} old"
                    }
                    if (isBirthdayTodayForChip) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BirthdayConfetti(Modifier.weight(1f))
                            Text("Happy AI Birthday!", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    TextButton(onClick = { birthdayCardOpen = true }, contentPadding = PaddingValues(0.dp)) {
                        Text(
                            "Born ${java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(aiBirthTimestamp))} · $ageLabel",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                    if (birthdayCardOpen) {
                        val nowAt = System.currentTimeMillis()
                        fun anniversaryAt(timestamp: Long, field: Int, amount: Int): Long {
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
                            val originalDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
                            cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                            cal.add(field, amount)
                            cal.set(java.util.Calendar.DAY_OF_MONTH, minOf(originalDay, cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)))
                            return cal.timeInMillis
                        }
                        val birthCal = java.util.Calendar.getInstance().apply { timeInMillis = aiBirthTimestamp }
                        val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = nowAt }
                        val elapsedMonths = ((nowCal.get(java.util.Calendar.YEAR) - birthCal.get(java.util.Calendar.YEAR)) * 12 +
                            nowCal.get(java.util.Calendar.MONTH) - birthCal.get(java.util.Calendar.MONTH)).coerceAtLeast(0)
                        val elapsedYears = (nowCal.get(java.util.Calendar.YEAR) - birthCal.get(java.util.Calendar.YEAR)).coerceAtLeast(0)
                        val oneDayAt = aiBirthTimestamp + java.util.concurrent.TimeUnit.DAYS.toMillis(1)
                        val oneWeekAt = aiBirthTimestamp + java.util.concurrent.TimeUnit.DAYS.toMillis(7)
                        val currentMonthAt = anniversaryAt(aiBirthTimestamp, java.util.Calendar.MONTH, elapsedMonths.coerceAtLeast(1))
                        val currentYearAt = anniversaryAt(aiBirthTimestamp, java.util.Calendar.YEAR, elapsedYears.coerceAtLeast(1))
                        val nextMonthAt = if (currentMonthAt > nowAt) currentMonthAt else anniversaryAt(aiBirthTimestamp, java.util.Calendar.MONTH, elapsedMonths + 1)
                        val nextYearAt = if (currentYearAt > nowAt) currentYearAt else anniversaryAt(aiBirthTimestamp, java.util.Calendar.YEAR, elapsedYears + 1)
                        val nextCandidates = buildList {
                            if (oneDayAt > nowAt) add(oneDayAt to "1 day")
                            if (oneWeekAt > nowAt) add(oneWeekAt to "1 week")
                            add(nextMonthAt to "monthly")
                            add(nextYearAt to if (elapsedYears == 0) "1 year" else "yearly")
                        }
                        val next = nextCandidates.minBy { it.first }
                        val isBirthdayCelebrationToday = elapsedYears >= 1 &&
                            nowCal.get(java.util.Calendar.MONTH) == birthCal.get(java.util.Calendar.MONTH) &&
                            nowCal.get(java.util.Calendar.DAY_OF_MONTH) == birthCal.get(java.util.Calendar.DAY_OF_MONTH)
                        val previousCandidates = buildList {
                            add(aiBirthTimestamp)
                            if (oneDayAt <= nowAt) add(oneDayAt)
                            if (oneWeekAt <= nowAt) add(oneWeekAt)
                            if (currentMonthAt <= nowAt) add(currentMonthAt)
                            if (currentYearAt <= nowAt) add(currentYearAt)
                        }
                        val previousAt = previousCandidates.maxOrNull() ?: aiBirthTimestamp
                        val progress = ((nowAt - previousAt).toFloat() / (next.first - previousAt).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                        AlertDialog(
                            onDismissRequest = { birthdayCardOpen = false },
                            title = { Text("Your AI birthday") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    FloatingLogo(Modifier)
                                    if (isBirthdayCelebrationToday) {
                                        BirthdayConfetti()
                                        Text("Happy AI Birthday!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text("Born ${java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(aiBirthTimestamp))}")
                                    Text("Your AI is $ageDays days old.")
                                    CircularProgressIndicator(progress = progress)
                                    Text("Next milestone: ${next.second} · ${java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(next.first))}")
                                    Text("Monthly and yearly milestones repeat as your AI grows with you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            confirmButton = { TextButton(onClick = { birthdayCardOpen = false }) { Text("Close") } }
                        )
                    }
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
                        onClick = { menuOpen = true }
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (chatHistoryEnabled && !privateChat && messages.isNotEmpty()) {
                            DropdownMenuItem(
                                enabled = !savingChat && !busy,
                                text = { Text(if (savingChat) "Saving conversation…" else "Save conversation now") },
                                onClick = { menuOpen = false; saveConversationNow() }
                            )
                        }
                        DropdownMenuItem(text = { Text("Chat History") }, onClick = { menuOpen = false; openHistory() })
                        DropdownMenuItem(text = { Text("Plugins & AI Features") }, onClick = { menuOpen = false; openPlugins() })
                        DropdownMenuItem(text = { Text("Plans & Billing") }, onClick = { menuOpen = false; openPlans() })
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
            if (creditsExhausted) Text(dailyRenewalNotice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(if (creditsExhausted) "Usage limit reached" else "Usage notice", fontWeight = FontWeight.Bold)
                            Text(notice, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { if (creditsExhausted) openCredits() else openPlans() }) { Text(if (creditsExhausted) "Buy credits" else "View plans") }
                        IconButton(onClick = {
                            val userKey = FirebaseAuth.getInstance().currentUser?.uid?.take(12) ?: "guest"
                            val creditPrefs = accountPreferences(context, "bq_ui")
                            val cycleDate = creditPrefs.getString("credit_cycle_date_${userKey}", "").orEmpty()
                            creditPrefs.edit().putBoolean("credit_notice_dismissed_${userKey}_${cycleDate}", true).apply()
                            creditNoticeDismissed = true
                            creditNotice = null
                        }) { Icon(Icons.Default.Close, contentDescription = "Dismiss usage notice") }
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
            micError?.let { micMessage ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(micMessage, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Retry") }
                    if (micMessage.contains("permission", ignoreCase = true)) {
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                        }) { Text("Open settings") }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BqLogo(184.dp, Modifier.align(Alignment.Center).graphicsLayer { alpha = 0.055f })
                LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                FloatingLogo(Modifier)
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
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (m.mine) Color(0xFF101820) else Color(0xFF050608),
                                border = if (m.mine) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B78A8)) else null,
                                tonalElevation = 0.dp,
                                shadowElevation = 3.dp
                            ) {
                                SelectionContainer {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (m.text.isNotBlank()) {
                                            if (m.mine || (streamJob != null && messages.lastOrNull()?.id == m.id)) {
                                                Text(m.text, color = Color(0xFFF5F8FC))
                                            } else {
                                                MarkdownText(m.text, color = Color(0xFFF5F8FC), sources = m.sources)
                                            }
                                        }
                                        m.attachments.forEach { attachment ->
                                            ChatAttachmentPreview(
                                            attachment,
                                            onImageClick = {
                                                if (!attachment.imageBase64.isNullOrBlank()) {
                                                    selectedImageBase64 = attachment.imageBase64
                                                    selectedImageMimeType = attachment.mimeType
                                                    selectedImagePrompt = m.prompt ?: messages.getOrNull((messages.indexOf(m) - 1).coerceAtLeast(0))?.text.orEmpty()
                                                    selectedImageMessageId = m.id
                                                    showImageViewer = true
                                                }
                                            },
                                            onRetry = {
                                                val retryPrompt = m.prompt ?: messages.getOrNull((messages.indexOf(m) - 1).coerceAtLeast(0))?.text.orEmpty()
                                                if (retryPrompt.isNotBlank()) sendAuthenticatedMessage(retryPrompt)
                                            }
                                        )
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
                        if (!m.mine && m.sources.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Sources", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                                    items(m.sources) { source ->
                                        WebSourceChip(source, onOpen = { url ->
                                            if (Uri.parse(url).scheme.equals("https", ignoreCase = true)) {
                                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                                    .onFailure { error = "Could not open this source." }
                                            }
                                        })
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
                            IconButton(modifier = Modifier.size(28.dp), onClick = { clipboard.setText(AnnotatedString(m.text)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy response", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), onClick = { shareText(context, m.text) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share response", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                            if (voiceRepliesEnabled) {
                                IconButton(modifier = Modifier.size(28.dp), onClick = { speakTextWhenReady(context, m.text) { error = it } }) {
                                    Icon(Icons.Default.VolumeUp, contentDescription = "Read response aloud", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                                }
                            }
                            Box {
                                IconButton(
                                    modifier = Modifier.size(28.dp),
                                    onClick = { replyOverflowMessageId = m.id }
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More response actions", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                                }
                                DropdownMenu(
                                    expanded = replyOverflowMessageId == m.id,
                                    onDismissRequest = { replyOverflowMessageId = null }
                                ) {
                                    if (m.attachments.any { !it.imageBase64.isNullOrBlank() } || !m.imageUrl.isNullOrBlank()) {
                                        DropdownMenuItem(
                                            enabled = !imageActionLoading,
                                            text = { Text(if (imageActionLoading) "Loading image…" else "Image actions") },
                                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                            onClick = {
                                                replyOverflowMessageId = null
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
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Report response") },
                                        leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                                        onClick = {
                                            replyOverflowMessageId = null
                                            scope.launch {
                                                runCatching { WorkspaceApiClient().reportMessage(m.text, "report", m.id, "report") }
                                                    .onSuccess { error = "Report submitted for review." }
                                                    .onFailure { error = safeUserFacingIssue(it, "Your report could not be submitted. Please try again shortly.") }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Helpful") },
                                        leadingIcon = { Icon(Icons.Default.ThumbUp, contentDescription = null) },
                                        onClick = {
                                            replyOverflowMessageId = null
                                            scope.launch {
                                                runCatching { WorkspaceApiClient().reportMessage(m.text, "response", m.id, "up") }
                                                    .onSuccess { error = "Thanks for the feedback." }
                                                    .onFailure { error = safeUserFacingIssue(it, "Your feedback could not be submitted. Please try again shortly.") }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Not helpful") },
                                        leadingIcon = { Icon(Icons.Default.ThumbDown, contentDescription = null) },
                                        onClick = { replyOverflowMessageId = null; feedbackTarget = m }
                                    )
                                    if (m.prompt != null) {
                                        DropdownMenuItem(
                                            text = { Text("Regenerate response") },
                                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                            onClick = {
                                                replyOverflowMessageId = null
                                                val promptToSend = m.prompt.orEmpty()
                                                message = promptToSend
                                                if (FirebaseAuth.getInstance().currentUser != null && !busy) {
                                                    sendAuthenticatedMessage(promptToSend)
                                                }
                                            }
                                        )
                                    }
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
                                    if (!failedImagePrompt.isNullOrBlank()) {
                                        TextButton(
                                            onClick = {
                                                val retryPrompt = failedImagePrompt
                                                failedImagePrompt = null
                                                error = null
                                                if (!retryPrompt.isNullOrBlank()) sendAuthenticatedMessage(retryPrompt)
                                            }
                                        ) { Text("Retry image") }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
            if (attachments.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                ) {
                    items(attachments.size) { index ->
                        val attachment = attachments[index]
                        val remove = { attachments = attachments.filterIndexed { itemIndex, _ -> itemIndex != index } }
                        if (attachment.mimeType.startsWith("image/") && attachment.imageBase64 != null) {
                            PendingImageThumbnail(attachment, remove)
                        } else {
                            InputChip(
                                selected = true,
                                onClick = remove,
                                label = { Text(attachment.name, maxLines = 1) },
                                trailingIcon = { Icon(Icons.Default.Close, "Remove attachment") }
                            )
                        }
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
                    Column(Modifier.fillMaxWidth()) {
                        if (dictationRecording || isListening) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().height(30.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    repeat(24) { index ->
                                        val barLevel = dictationWaveform.getOrElse(index) { 0f }
                                        val barHeight by animateFloatAsState(
                                            targetValue = 4f + barLevel * 24f,
                                            animationSpec = tween(durationMillis = 90),
                                            label = "dictationWaveBar$index"
                                        )
                                        Box(
                                            Modifier.width(3.dp)
                                                .height(barHeight.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (dictationRecording) "Recording %02d:%02d".format(java.util.Locale.US, dictationSeconds / 60, dictationSeconds % 60) else "Listening…",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        if (dictationRecording) stopDictation() else speechRecognizer?.stopListening()
                                    }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.Stop, contentDescription = "Stop recording or listening", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = {
                                        if (dictationRecording) cancelDictation() else {
                                            speechRecognizer?.cancel()
                                            isListening = false
                                            dictationLevel = 0f
                                        }
                                    }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Cancel dictation", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        } else if (dictationTranscribing) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Text("Transcribing…", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        dictationError?.let { dictationMessage ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(dictationMessage, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Retry") }
                                    if (dictationNeedsCredits) {
                                        Button(onClick = { openCredits() }) { Text("Buy credits") }
                                    }
                                }
                            }
                        }
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
                                    when {
                                        dictationRecording -> stopDictation()
                                        isListening -> speechRecognizer?.stopListening()
                                        else -> speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                enabled = !busy && !dictationTranscribing
                            ) {
                                Icon(
                                    if (dictationRecording || isListening) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = when {
                                        dictationRecording -> "Stop recording and transcribe"
                                        isListening -> "Stop phone speech recognition"
                                        else -> "Start voice dictation"
                                    },
                                    tint = MaterialTheme.colorScheme.primary
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
                                    if (creditsExhausted) "Usage limit reached — review plans to continue" else "Message BQ Sqwad AI",
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
                            OutlinedButton(modifier = Modifier.weight(1f), onClick = { runCatching { saveGeneratedImage(context, encodedImage) }.onSuccess { error = "Image saved to Pictures/BQ Sqwad AI." }.onFailure { error = safeUserFacingIssue(it, "Could not save image. Please try again.") } }) { Icon(Icons.Default.Download, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Save") }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { runCatching { shareGeneratedImage(context, encodedImage) }.onFailure { error = safeUserFacingIssue(it, "Could not share image. Please try again.") } },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0), contentColor = Color.White)
                            ) { Icon(Icons.Default.Share, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Share") }
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
                    TextButton(onClick = { runCatching { saveGeneratedImage(context, selectedImageBase64.orEmpty()) }.onSuccess { error = "Image saved to Pictures/BQ Sqwad AI." }.onFailure { error = safeUserFacingIssue(it, "Could not save image. Please try again.") }; showImageActions = false }) { Text("Save") }
                    TextButton(onClick = { runCatching { shareGeneratedImage(context, selectedImageBase64.orEmpty()) }.onFailure { error = safeUserFacingIssue(it, "Could not share image. Please try again.") }; showImageActions = false }) { Text("Share") }
                    TextButton(onClick = { imageEditPrompt = ""; showImageActions = false; showImageEdit = true }) { Text("Edit") }
                    TextButton(onClick = {
                        val prompt = selectedImagePrompt.ifBlank { "Create a new version of this image" }
                        val targetId = selectedImageMessageId
                        showImageActions = false
                        busy = true
                        statusText = "Generating image…"
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { GenerationApiClient().generateImage(prompt, modelRegistryId = null) }
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
                                        val snapshot = messages.filterNot { it.id in privateMessageIds }.map { item -> HistoryMessage(item.mine, item.text, item.id, item.prompt, item.attachments.map { it.mimeType + "|" + it.name + (it.imageLocalId?.let { localId -> "|" + localId } ?: "") }, item.imageUrl) }
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
                        runCatching { GenerationApiClient().editImage(prompt, image, selectedImageSize, modelRegistryId = null, mimeType = selectedImageMimeType) }
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
                                            HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name + (it.imageLocalId?.let { localId -> "|" + localId } ?: "") }, m.imageUrl)
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
                        runCatching { GenerationApiClient().generateImage(prompt, selectedImageSize, modelRegistryId = null) }
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
                                            HistoryMessage(m.mine, m.text, m.id, m.prompt, m.attachments.map { it.mimeType + "|" + it.name + (it.imageLocalId?.let { localId -> "|" + localId } ?: "") }, m.imageUrl)
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
                                error = safeUserFacingIssue(it, "Feedback could not be sent. Please try again.")
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
    AppFeature("scheduled_ai_tasks", "Scheduled AI Tasks", "Run supported AI tasks on a schedule and keep execution history.", "scheduled_ai_tasks"),
    AppFeature("god_ai_agent", "God AI Agent", "Coordinate research, planning and enabled BQ Sqwad AI capabilities in one supervised workflow. External actions still require your confirmation.", "agent_tool_use", true)
)

private val FEATURE_DISPLAY_NAMES = mapOf(
    "god_ai_agent" to "God AI Agent",
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
    onOpenPlans: () -> Unit,
    onOpenCredits: () -> Unit = onOpenPlans,
    onOpenMainChat: () -> Unit = {}
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
                Workspace.CAMERA -> CameraWorkspace(onOpenCredits = onOpenCredits, showDiagnostics = adminAuthorized)
                Workspace.VOICE -> VoiceWorkspace(onOpenCredits = onOpenCredits, showDiagnostics = adminAuthorized)
                Workspace.PERSONAL -> PersonalIntelligenceWorkspace()
                Workspace.LIFE -> LifeMattersWorkspace(onOpenPlans = onOpenPlans)
                Workspace.COMPANION -> CompanionWorkspace(onOpenPlans = onOpenPlans, onOpenCredits = onOpenCredits)
                Workspace.LEARNING -> CompleteLearningStudioWorkspace(onOpenPlans = onOpenPlans, onOpenMainChat = onOpenMainChat)
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
            AiTaskWorkspace(feature.title, feature.description, feature.capability, false, feature.webSearch, onOpenSettings = onOpenSettings)
        }
    }
}

@Composable
private fun FeatureSettingsScreen(workspace: Workspace, onBack: () -> Unit) {
    val context = LocalContext.current
    val accountPrefs = remember { accountPreferences(context) }
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
        accountPrefs.edit().putBoolean("feature_" + key + "_" + setting, value).apply()
        scope.launch {
            saving = true
            runCatching {
                WorkspaceApiClient().updateFeatureSettings(
                    key,
                    org.json.JSONObject().put(setting, value)
                )
            }.onFailure {
                error = safeUserFacingIssue(it, "Setting could not be saved. Please try again.")
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
            defaults.keys.forEach { setting -> if (accountPrefs.contains("feature_" + key + "_" + setting)) values = values + (setting to accountPrefs.getBoolean("feature_" + key + "_" + setting, defaults[setting] == true)) }
            error = safeUserFacingIssue(it, "Feature settings could not be loaded. Please try again.")
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
    val settingsPrefs = remember { accountPreferences(context) }
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
                            .onFailure { error = safeUserFacingIssue(it, "Memory setting could not be updated. Please try again.") }
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
    val settingsPrefs = remember { accountPreferences(context) }
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
        answer?.let { answerText ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (deep) "Deep Research Answer" else "Answer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    MarkdownText(sanitizeWebAnswer(answerText), sources = results.toList())
                }
            }
        }
        if (results.isNotEmpty()) {
            Text("Sources", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            LazyRow(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(results) { source ->
                    WebSourceChip(source, onOpen = { url ->
                        if (Uri.parse(url).scheme.equals("https", ignoreCase = true)) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { error = "No browser is available to open this source." }
                        }
                    })
                }
            }
        }
    }
}

private const val LIVE_MEDIA_CONSENT_VERSION = "2026-10-11"

@Composable
fun CameraWorkspace(onOpenCredits: () -> Unit = {}, showDiagnostics: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsPrefs = remember { accountPreferences(context) }
    val cameraEnhanceEnabled = remember { settingsPrefs.getBoolean("camera_enhance", true) }
    // Sample occasionally for the Compose fallback image, while AI analysis remains throttled below.
    val frameCaptureIntervalMs = if (cameraEnhanceEnabled) 500L else 800L
    val aiFrameIntervalMs = if (cameraEnhanceEnabled) 1600L else 2600L
    var granted by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var active by remember { mutableStateOf(false) }
    var previewEnabled by remember { mutableStateOf(granted) }
    var fullScreenPreview by remember { mutableStateOf(granted) }
    var fullScreenPreviewReady by remember { mutableStateOf(granted) }
    var showCameraSettings by remember { mutableStateOf(false) }
    val liveAudioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    var liveVolume by remember {
        mutableStateOf(
            (liveAudioManager.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL).toFloat() /
                liveAudioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)).coerceIn(0f, 1f)
        )
    }
    fun setLiveVolume(value: Float) {
        liveVolume = value.coerceIn(0f, 1f)
        val maxVolume = liveAudioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        liveAudioManager.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, (liveVolume * maxVolume).roundToInt().coerceIn(0, maxVolume), 0)
    }
    var previewFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastPreviewTextureUpdate by remember { mutableLongStateOf(0L) }
    var showPreviewBitmapFallback by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var permissionExplanation by remember { mutableStateOf(false) }
    val cameraPreviewPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        previewEnabled = allowed
        if (!allowed) permissionExplanation = true
    }
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
    LaunchedEffect(granted, previewEnabled) {
        if (granted && previewEnabled && !streamOnline && !screenActive) {
            fullScreenPreview = true
            fullScreenPreviewReady = true
        }
    }

    LaunchedEffect(previewEnabled, granted, streamOnline) {
        if (!previewEnabled || !granted || streamOnline) {
            showPreviewBitmapFallback = false
            return@LaunchedEffect
        }
        while (true) {
            delay(700L)
            showPreviewBitmapFallback = previewFrame != null &&
                System.currentTimeMillis() - lastPreviewTextureUpdate > 1200L
        }
    }
    fun handleCameraPreviewFrame(bytes: ByteArray) {
        scope.launch(Dispatchers.Default) {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            if (bitmap != null) {
                val image = bitmap.asImageBitmap()
                withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { previewFrame = image }
            }
        }
        val now = System.currentTimeMillis()
        if (active && now - lastAnalysisAt >= aiFrameIntervalMs) {
            lastAnalysisAt = now
            LiveMediaForegroundService.submitVisualFrame(bytes)
        }
    }
    var tiktokHandoffNotice by remember { mutableStateOf<String?>(null) }
    var liveTermsAccepted by remember {
        val consentPrefs = accountPreferences(context, "bq_live_consent")
        mutableStateOf(
            consentPrefs.getBoolean("accepted", false) &&
                consentPrefs.getString("policyVersion", "") == LIVE_MEDIA_CONSENT_VERSION
        )
    }
    var showLiveTerms by remember { mutableStateOf(false) }
    var pendingLiveAction by remember { mutableStateOf("camera") }
    var selectedVoice by remember { mutableStateOf("marin") }
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
                        .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, ""))
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
            streamError = if (it.message.orEmpty().contains("does not have a flashlight") || it.message.orEmpty().contains("No camera")) {
                "The selected camera may not support a flashlight. Try the rear camera."
            } else {
                "The flashlight could not be switched on. Try the rear camera and check that another app is not using the light."
            }
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
                        .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, ""))
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
                    .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, "")
            )
            screenActive = true
            screenAnswer = "Android screen sharing is active. BQ Sqwad AI is receiving screen frames through the foreground service."
        }.onFailure { t ->
            context.stopService(Intent(context, LiveMediaForegroundService::class.java))
            screenActive = false
            screenAnswer = safeUserFacingIssue(t, "Could not start screen sharing. Please try again.")
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
            if (!liveServiceActive && liveUiState.status.lowercase() in setOf("stopped", "error") && granted) {
                previewEnabled = true
                fullScreenPreview = true
                fullScreenPreviewReady = true
            }
            if (!liveServiceActive && liveUiState.status.contains("credit", ignoreCase = true)) streamError = "Your current usage allowance is unavailable. Review plans and billing to continue."
        } else if (liveServiceMode == LiveMediaForegroundService.MODE_CAMERA) {
            active = liveServiceActive
            if (liveAnswer.isNotBlank()) answer = liveAnswer
            if (!liveServiceActive && liveUiState.status.contains("credit", ignoreCase = true)) streamError = "Your current usage allowance is unavailable. Review plans and billing to continue."
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI Live Camera", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Allow camera access to show a live preview. The AI session starts only when you choose Start AI session.")
                Button(onClick = { cameraPreviewPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enable camera")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !screenActive || active,
                    onClick = {
                        if (active) {
                            context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                            active = false
                            streamOnline = false
                            previewEnabled = granted
                        } else if (!screenActive) {
                            startLiveCamera()
                        }
                    }
                ) {
                    Text(
                        when {
                            active -> "Stop AI session"
                            screenActive -> "Stop screen sharing first"
                            else -> "Start AI session"
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
                                    .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, ""))
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
                        previewEnabled = granted
                        fullScreenPreview = granted
                        fullScreenPreviewReady = granted
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
                            onFrame = ::handleCameraPreviewFrame,
                            onPreviewUpdated = {
                                val now = System.currentTimeMillis()
                                if (now - lastPreviewTextureUpdate >= 800L) {
                                    lastPreviewTextureUpdate = now
                                    if (showPreviewBitmapFallback) showPreviewBitmapFallback = false
                                }
                            },
                            onError = { message ->
                                streamError = message
                                reportCameraDiagnostic(cameraDiagnosticCode(message))
                            },
                            onClosed = { if (fullScreenPreview) fullScreenPreviewReady = true },
                            onLensSelected = { lens = it }
                        )
                    }
                    if (!streamOnline && showPreviewBitmapFallback && previewFrame != null) {
                        Image(bitmap = previewFrame!!, contentDescription = "Live camera footage", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    TextButton(
                        onClick = {
                            // The inline preview's onClosed callback opens the full-screen view
                            // only after Camera2 reports that the old device has closed.
                            fullScreenPreview = true
                            fullScreenPreviewReady = false
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    ) { Text("Full screen", color = Color.White) }
                    Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.62f)
                ) {
                    Text(
                        if (streamOnline) "● STREAMING ONLINE" else if (previewEnabled && granted) "● CAMERA ACTIVE · " + (if (lens == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "REAR") else "Connecting…",
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
                            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                            if (message.contains("credit", ignoreCase = true)) {
                                TextButton(onClick = onOpenCredits) { Text("Buy credits") }
                            }
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
                    Text(message, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    if (message.contains("credit", ignoreCase = true)) {
                        Button(onClick = onOpenCredits, modifier = Modifier.fillMaxWidth()) { Text("Buy credits") }
                    }
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
                fullScreenPreviewReady = false
                fullScreenPreview = false
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
                        onFrame = ::handleCameraPreviewFrame,
                        onPreviewUpdated = {
                            val now = System.currentTimeMillis()
                            if (now - lastPreviewTextureUpdate >= 800L) {
                                lastPreviewTextureUpdate = now
                                if (showPreviewBitmapFallback) showPreviewBitmapFallback = false
                            }
                        },
                        onError = { message ->
                            streamError = message
                            reportCameraDiagnostic(cameraDiagnosticCode(message))
                            fullScreenPreviewReady = false
                        },
                        onClosed = { if (fullScreenPreview && !fullScreenPreviewReady) fullScreenPreview = false },
                        onLensSelected = { lens = it }
                    )
                }
                if (!streamOnline && showPreviewBitmapFallback && previewFrame != null) {
                    Image(bitmap = previewFrame!!, contentDescription = "Live camera footage", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
                        IconButton(onClick = { showCameraSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Live camera settings", tint = Color.White)
                        }
                    }
                    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
                        TextButton(onClick = {
                            fullScreenPreviewReady = false
                            fullScreenPreview = false
                        }) { Text("Close", color = Color.White) }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = granted && (!screenActive || active),
                        onClick = {
                            if (active) {
                                context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                                active = false
                                streamOnline = false
                                previewEnabled = granted
                            } else if (!screenActive) startLiveCamera()
                        }
                    ) {
                        Icon(if (active) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (active) "Stop AI" else "Start AI")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !active || screenActive,
                        onClick = {
                            fullScreenPreviewReady = false
                            fullScreenPreview = false
                            if (screenActive) {
                                context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                                screenActive = false
                                previewEnabled = granted
                                fullScreenPreview = granted
                                fullScreenPreviewReady = granted
                            } else if (!liveTermsAccepted) {
                                pendingLiveAction = "screen"
                                showLiveTerms = true
                            } else launchScreenWithMic()
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (screenActive) "Stop share" else "Share screen")
                    }
                }
                val visibleAnswer = liveAnswer.ifBlank { liveUiState.status.takeIf { it.isNotBlank() && it != "stopped" }.orEmpty() }
                if (visibleAnswer.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 82.dp),
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(visibleAnswer.take(500), modifier = Modifier.padding(12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showCameraSettings) {
        AlertDialog(
            onDismissRequest = { showCameraSettings = false },
            title = { Text("Live Camera settings") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("AI voice volume · " + (liveVolume * 100).roundToInt() + "%", style = MaterialTheme.typography.titleSmall)
                    Slider(value = liveVolume, onValueChange = ::setLiveVolume, valueRange = 0f..1f)
                    Text("This adjusts the live AI call-speaker volume. Use the device volume buttons for quick changes too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("AI voice", style = MaterialTheme.typography.titleSmall)
                    liveVoices.forEach { (id, label) ->
                        Row(Modifier.fillMaxWidth().clickable { selectedVoice = id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedVoice == id, onClick = { selectedVoice = id })
                            Text(label)
                        }
                    }
                    OutlinedButton(onClick = { toggleFlashlight() }, modifier = Modifier.fillMaxWidth(), enabled = granted) {
                        Icon(Icons.Default.FlashOn, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(if (flashlightOn) "Turn spotlight off" else "Turn spotlight on")
                    }
                    OutlinedButton(onClick = {
                        lens = if (lens == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                        flashlightOn = false
                    }, modifier = Modifier.fillMaxWidth(), enabled = granted) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Flip camera")
                    }
                    OutlinedButton(onClick = {
                        showCameraSettings = false
                        if (screenActive) {
                            context.stopService(Intent(context, LiveMediaForegroundService::class.java))
                            screenActive = false
                            previewEnabled = granted
                            fullScreenPreview = granted
                            fullScreenPreviewReady = granted
                        } else if (!liveTermsAccepted) {
                            pendingLiveAction = "screen"
                            showLiveTerms = true
                        } else launchScreenWithMic()
                    }, modifier = Modifier.fillMaxWidth(), enabled = !active || screenActive) {
                        Icon(Icons.Default.Share, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(if (screenActive) "Stop screen sharing" else "Share screen with AI")
                    }
                    OutlinedButton(onClick = {
                        showCameraSettings = false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/live"))) }
                            .onFailure { streamError = "TikTok could not be opened on this device." }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open TikTok LIVE") }
                    OutlinedButton(onClick = {
                        showCameraSettings = false
                        val intent = Intent("com.google.android.youtube.intent.action.CREATE_LIVE_STREAM").setPackage("com.google.android.youtube")
                        runCatching { context.startActivity(intent) }.onFailure {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/live"))) }
                                .onFailure { streamError = "YouTube Live could not be opened. Install or update YouTube and try again." }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open YouTube Live") }
                    Text("TikTok and YouTube open their own apps/pages. Direct in-app broadcasting requires each platform's supported stream credentials and integration.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showCameraSettings = false }) { Text("Done") } }
        )
    }

    if (showLiveTerms) {
        AlertDialog(
            onDismissRequest = { showLiveTerms = false },
            title = { Text(if (pendingLiveAction == "screen") "Before you share your screen" else "Before you start Live Camera") },
            text = { Text("Before starting, please review this privacy notice. If you continue, BQ Sqwad AI will access only the camera, microphone, or screen-sharing capability needed for the feature you start. Live audio or selected camera/screen frames may be sent securely to BQ Sqwad AI's server and its configured AI provider to generate replies. Do not share passwords, payment details, private messages, confidential work, or other people's personal information unless you have permission. Tell anyone who may be recorded or shown that AI analysis is active and obtain their consent where required. Opening TikTok or another app does not give BQ Sqwad AI permission to control that app or bypass its privacy controls. Android may stop or limit camera/microphone access when another app uses it. You can stop the AI session at any time using the in-app control or persistent notification. Usage may consume the credits shown in the app. Continue only if you understand and agree.") },
            confirmButton = {
                TextButton(onClick = {
                    liveTermsAccepted = true
                    accountPreferences(context, "bq_live_consent").edit()
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
private fun BqLiveAudioOrb(
    modifier: Modifier,
    active: Boolean,
    audioLevel: Float
) {
    val context = LocalContext.current
    val animationsEnabled = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }
    // Decibel mapping provides automatic gain for quiet speech while clamping peaks.
    val safeLevel = audioLevel.coerceIn(0f, 1f)
    val decibels = if (safeLevel > 0.00001f) 20f * kotlin.math.log10(safeLevel) else -100f
    val normalized = ((decibels + 60f) / 60f).coerceIn(0f, 1f)
    val target = if (active) (0.08f + normalized * 0.72f).coerceIn(0.08f, 0.8f) else 0.035f
    val animatedLevel by animateFloatAsState(
        targetValue = target,
        animationSpec = if (animationsEnabled) spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy)
        else snap(),
        label = "bqLiveOrbLevel"
    )
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val baseRadius = size.minDimension * 0.46f
        val level = if (animationsEnabled) animatedLevel else target
        drawCircle(Color(0xFF0A2B66), radius = baseRadius, center = androidx.compose.ui.geometry.Offset(centerX, centerY))
        drawCircle(
            Color(0xFF075DCE).copy(alpha = 0.50f + level * 0.35f),
            radius = baseRadius * (0.76f + level * 0.18f),
            center = androidx.compose.ui.geometry.Offset(centerX, centerY)
        )
        drawCircle(
            Color(0xFF168BFF).copy(alpha = 0.45f + level * 0.45f),
            radius = baseRadius * (0.50f + level * 0.18f),
            center = androidx.compose.ui.geometry.Offset(centerX, centerY)
        )
        drawCircle(
            Color(0xFF8CCBFF).copy(alpha = 0.88f),
            radius = baseRadius * (0.28f + level * 0.06f),
            center = androidx.compose.ui.geometry.Offset(centerX, centerY)
        )
        drawCircle(
            Color(0xFFB9E1FF).copy(alpha = 0.85f),
            radius = baseRadius * 0.13f,
            center = androidx.compose.ui.geometry.Offset(centerX, centerY)
        )
        // 56 radial bars create a compact audio spectrum without allocating per-frame collections.
        val barPhase = if (active && animationsEnabled) level * 6f else 0f
        val barStroke = (baseRadius * 0.025f).coerceAtLeast(1.5f)
        for (index in 0 until 56) {
            val angle = (index.toFloat() / 56f) * (2f * Math.PI.toFloat())
            val wave = (0.5f + 0.5f * kotlin.math.sin(angle * 3f + barPhase)).coerceIn(0f, 1f)
            val inner = baseRadius * (0.79f + wave * 0.04f)
            val outer = baseRadius * (0.86f + wave * (0.08f + level * 0.06f))
            val cos = kotlin.math.cos(angle)
            val sin = kotlin.math.sin(angle)
            drawLine(
                color = Color(0xFF78BCFF).copy(alpha = (0.55f + level * 0.4f).coerceIn(0f, 1f)),
                start = androidx.compose.ui.geometry.Offset(centerX + cos * inner, centerY + sin * inner),
                end = androidx.compose.ui.geometry.Offset(centerX + cos * outer, centerY + sin * outer),
                strokeWidth = barStroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun VoiceWorkspace(onOpenCredits: () -> Unit = {}, showDiagnostics: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceAudioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    var voiceVolume by remember {
        mutableStateOf((voiceAudioManager.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL).toFloat() / voiceAudioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)).coerceIn(0f, 1f))
    }
    var selectedVoice by remember { mutableStateOf("marin") }
    var voicePickerExpanded by remember { mutableStateOf(false) }
    val availableLiveVoices = listOf("marin" to "Aurora", "cedar" to "Cedar", "alloy" to "Clarity", "ash" to "Ember", "ballad" to "Velvet", "coral" to "Coral", "echo" to "Echo", "sage" to "Sage", "shimmer" to "Shimmer", "verse" to "Verse")
    fun setVoiceVolume(value: Float) {
        voiceVolume = value.coerceIn(0f, 1f)
        val maxVolume = voiceAudioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        voiceAudioManager.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, (voiceVolume * maxVolume).roundToInt().coerceIn(0, maxVolume), 0)
    }
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
                    .putExtra(LiveMediaForegroundService.EXTRA_VOICE, selectedVoice)
                    .putExtra(LiveMediaForegroundService.EXTRA_MODEL_REGISTRY_ID, "")
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
    LaunchedEffect(liveUiState) {
        if (liveUiState.mode == LiveMediaForegroundService.MODE_VOICE) {
            active = liveUiState.active
            state = liveUiState.status.ifBlank { "stopped" }
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
        BqLiveAudioOrb(
            modifier = Modifier.size(210.dp),
            active = active,
            audioLevel = liveUiState.audioLevel
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Mic " + (liveUiState.microphoneLevel * 100).toInt() + "%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = liveUiState.microphoneLevel, modifier = Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AI output " + (liveUiState.outputAudioLevel * 100).toInt() + "%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = liveUiState.outputAudioLevel, modifier = Modifier.fillMaxWidth())
            }
        }

        Text(
            if (active) state.replaceFirstChar { it.uppercase() } else "Voice off",
            style = MaterialTheme.typography.titleMedium
        )
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI voice settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Live speaker volume · " + (voiceVolume * 100).roundToInt() + "%", style = MaterialTheme.typography.bodyMedium)
                Slider(value = voiceVolume, onValueChange = ::setVoiceVolume, valueRange = 0f..1f)
                Text("This controls the live AI call-speaker stream. Device volume buttons remain available during the session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { voicePickerExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("AI voice: " + (availableLiveVoices.firstOrNull { it.first == selectedVoice }?.second ?: "Aurora"))
                    }
                    DropdownMenu(expanded = voicePickerExpanded, onDismissRequest = { voicePickerExpanded = false }) {
                        availableLiveVoices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { selectedVoice = id; voicePickerExpanded = false }) }
                    }
                }
                Text("Volume uses the device media playback level. A voice change applies the next time you start a live session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val serviceVoiceError = liveUiState.answer.takeIf {
            liveUiState.mode == LiveMediaForegroundService.MODE_VOICE &&
                liveUiState.status.equals("error", ignoreCase = true) &&
                it.isNotBlank()
        }
        (startError ?: serviceVoiceError)?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (showDiagnostics && liveUiState.mode == LiveMediaForegroundService.MODE_VOICE && liveUiState.log.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Live session activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    liveUiState.log.takeLast(6).forEach { entry ->
                        Text(entry, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!active && state.contains("credit", ignoreCase = true)) {
            Text("Your current usage allowance is unavailable. Review plans and billing to continue.", color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = onOpenCredits, modifier = Modifier.fillMaxWidth()) { Text("Buy credits") }
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
                    DeviceIntelligenceClient().interpret(requested, null)
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

                        }
                    }
                }.onFailure {
                    scope.launch(Dispatchers.Main) {
                        busy = false
                        status = safeUserFacingIssue(it, "Device intelligence could not complete the request. Please try again.")
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
private fun SettingsScreen(onBack: () -> Unit, adminAuthorized: Boolean, onOpenAdminDashboard: () -> Unit, onSignOut: () -> Unit, onOpenAccountProtection: () -> Unit, onOpenLegalCenter: () -> Unit, onNotificationsChanged: (Boolean) -> Unit, notificationStatus: String?, onOpenWorkspace: (Workspace) -> Unit, onOpenPlans: () -> Unit) {
    val context = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val prefs = remember(uid) { accountPreferences(context) }
    val devicePrefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var themeMode by remember(uid) { mutableStateOf(devicePrefs.getString("theme_mode", "system") ?: "system") }
    var accent by remember(uid) { mutableStateOf(devicePrefs.getString("accent_palette", "signature") ?: "signature") }
    var trainingConsent by remember(uid) { mutableStateOf(prefs.getBoolean("training_consent", false)) }
    var showSettingsPrivacyDirectory by remember { mutableStateOf(false) }
    var showTrainingSettings by remember { mutableStateOf(false) }
    var showFileBoxPanel by remember { mutableStateOf(false) }
    var showProjectsPanel by remember { mutableStateOf(false) }
    var showAboutMePanel by remember { mutableStateOf(false) }
    var trainingSaving by remember { mutableStateOf(false) }
    var chatHistory by remember(uid) { mutableStateOf(prefs.getBoolean("chat_history", true)) }
    var autoSave by remember(uid) { mutableStateOf(prefs.getBoolean("auto_save", true)) }
    var memory by remember(uid) { mutableStateOf(prefs.getBoolean("memory_enabled", true)) }
    var webSearch by remember(uid) { mutableStateOf(prefs.getBoolean("web_search_enabled", true)) }
    var voiceReplies by remember(uid) { mutableStateOf(prefs.getBoolean("voice_replies", true)) }
    var cameraEnhance by remember(uid) { mutableStateOf(prefs.getBoolean("camera_enhance", true)) }
    var companionMemory by remember(uid) { mutableStateOf(prefs.getBoolean("companion_memory", true)) }
    var truthGuardSources by remember(uid) { mutableStateOf(prefs.getBoolean("truthguard_sources", true)) }
    var notifications by remember(uid) { mutableStateOf(prefs.getBoolean("notifications_enabled", true)) }
    var settingsNotice by remember { mutableStateOf<String?>(null) }
    var textScale by remember(uid) { mutableStateOf(prefs.getFloat("text_scale", 1.0f).coerceIn(0.85f, 1.8f)) }
    var fileBoxRetentionDays by remember(uid) { mutableIntStateOf(prefs.getInt("file_box_retention_days", 30).let { if (it in listOf(7,14,30,90)) it else 30 }) }
    var savingRetention by remember { mutableStateOf(false) }
    val aiBirthTimestamp = remember(uid) { BqLifecycleNotifications.birthTimestamp(context) }
    val aiAgeLabel = remember(aiBirthTimestamp) {
        val days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays((System.currentTimeMillis() - aiBirthTimestamp).coerceAtLeast(0L))
        val months = (days / 30.4375).toInt()
        when {
            days < 1L -> "less than one day"
            days < 30L -> "$days day" + if (days == 1L) "" else "s"
            months < 12 -> "$months month" + if (months == 1) "" else "s"
            else -> {
                val years = months / 12
                val remainingMonths = months % 12
                val yearLabel = if (years == 1) "year" else "years"
                val monthLabel = if (remainingMonths == 1) "month" else "months"
                "$years $yearLabel" + if (remainingMonths > 0) " $remainingMonths $monthLabel" else ""
            }
        }
    }
    var consentStatus by remember { mutableStateOf<String?>(null) }
    var entitlements by remember(uid) { mutableStateOf(emptyList<JSONObject>()) }
    var loadingAccount by remember(uid) { mutableStateOf(true) }
    var showDeleteAccount by remember(uid) { mutableStateOf(false) }
    var deletingAccount by remember(uid) { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var scheduledDeletionAt by remember { mutableStateOf<String?>(null) }
    var cancellingDeletion by remember(uid) { mutableStateOf(false) }
    var deletionNotice by remember { mutableStateOf<String?>(null) }
    var playManagement by remember { mutableStateOf<JSONObject?>(null) }
    var selectedAiFeature by remember { mutableStateOf<Workspace?>(null) }
    var settingsCategory by remember(uid) { mutableStateOf("Account") }
    val chatBackgroundPrefs = remember(uid) { accountPreferences(context, "bq_ui") }
    var customChatBackgroundUri by remember(uid) { mutableStateOf(chatBackgroundPrefs.getString("chat_background_uri", null)) }
    var customChatBackgroundBrightness by remember(uid) { mutableStateOf(chatBackgroundPrefs.getFloat("chat_background_brightness", 1f).coerceIn(0.35f, 1.8f)) }
    var changingChatBackground by remember { mutableStateOf(false) }
    val chatBackgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !changingChatBackground) {
            changingChatBackground = true
            scope.launch {
                var persistedPermission = false
                runCatching {
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    require(mime.startsWith("image/")) { "Choose a valid image file." }
                    val imageBytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 10 * 1024 * 1024) error("Choose an image smaller than 10 MB.")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("Could not read the selected image.")
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
                    require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 &&
                        bounds.outWidth.toLong() * bounds.outHeight.toLong() <= 40_000_000L) {
                        "Choose a valid image with dimensions no larger than 12,000 × 12,000 pixels."
                    }
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    persistedPermission = true
                    val uploadId = java.util.UUID.randomUUID().toString()
                    WorkspaceApiClient().chargeChatBackground(uploadId)
                    chatBackgroundPrefs.edit().putString("chat_background_uri", uri.toString()).apply()
                    customChatBackgroundUri = uri.toString()
                    settingsNotice = "Chat background updated. Two credits were charged."
                    (context as? android.app.Activity)?.recreate()
                }.onFailure {
                    if (persistedPermission) runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    settingsNotice = safeUserFacingIssue(it, "Chat background was not changed. You need at least 2 credits.")
                }
                changingChatBackground = false
            }
        }
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        val serverKey = when (key) {
            "web_search_enabled" -> "web_search"
            "memory_enabled" -> "memory"
            "notifications_enabled" -> "notifications"
            "chat_history" -> "history"
            "auto_save" -> "save_files"
            "voice_replies" -> "voice_responses"
            else -> key
        }
        scope.launch {
            runCatching { WorkspaceApiClient().updateFeatureSettings("global", JSONObject().put(serverKey, value)) }
                .onFailure { settingsNotice = "Saved on this device, but could not sync to your account yet." }
        }
    }
    fun saveTheme(value: String) {
        themeMode = value
        devicePrefs.edit().putString("theme_mode", value).apply()
        (context as? android.app.Activity)?.recreate()
    }
    fun saveAccent(value: String) {
        accent = value
        devicePrefs.edit().putString("accent_palette", value).apply()
        (context as? android.app.Activity)?.recreate()
    }

    LaunchedEffect(uid) {
        loadingAccount = true
        val api = WorkspaceApiClient()
        runCatching { api.consent() }.onSuccess { consent ->
            trainingConsent = consent.optBoolean("trainingConsent", false)
            prefs.edit().putBoolean("training_consent", trainingConsent).apply()
        }
        runCatching { api.featureSettings() }.onSuccess { body ->
            val global = body.optJSONObject("settings")?.optJSONObject("global") ?: return@onSuccess
            val mapping = mapOf("web_search" to "web_search_enabled", "memory" to "memory_enabled", "notifications" to "notifications_enabled", "history" to "chat_history", "save_files" to "auto_save", "voice_responses" to "voice_replies", "camera_enhance" to "camera_enhance", "companion_memory" to "companion_memory", "truthguard_sources" to "truthguard_sources", "auto_run" to "auto_run", "streaming" to "streaming", "personalization" to "personalization", "background_jobs" to "background_jobs")
            mapping.forEach { (serverKey, localKey) -> if (global.has(serverKey) && global.opt(serverKey) is Boolean) prefs.edit().putBoolean(localKey, global.optBoolean(serverKey)).apply() }
            if (global.has("text_scale") && global.opt("text_scale") is Number) prefs.edit().putFloat("text_scale", global.optDouble("text_scale").toFloat().coerceIn(0.85f, 1.8f)).apply()
            chatHistory = prefs.getBoolean("chat_history", true)
            autoSave = prefs.getBoolean("auto_save", true)
            memory = prefs.getBoolean("memory_enabled", true)
            webSearch = prefs.getBoolean("web_search_enabled", true)
            voiceReplies = prefs.getBoolean("voice_replies", true)
            cameraEnhance = prefs.getBoolean("camera_enhance", true)
            companionMemory = prefs.getBoolean("companion_memory", true)
            truthGuardSources = prefs.getBoolean("truthguard_sources", true)
            notifications = prefs.getBoolean("notifications_enabled", true)
            textScale = prefs.getFloat("text_scale", 1.0f).coerceIn(0.85f, 1.8f)
            bqTextScaleState.floatValue = textScale
        }
        runCatching { api.fileBox() }.onSuccess { body ->
            val days = body.optInt("retentionDays", 30).let { if (it in listOf(7,14,30,90)) it else 30 }
            fileBoxRetentionDays = days
            prefs.edit().putInt("file_box_retention_days", days).apply()
        }
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All settings", "Account", "Payments & Billing", "Appearance", "Chat & AI Features", "Live AI", "Notifications", "Privacy & Data", "About")) { category ->
                        FilterChip(
                            selected = settingsCategory == category,
                            onClick = { settingsCategory = category },
                            label = { Text(category) }
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bqsqwadai.com/privacy-policy"))) }
                    ) { Icon(Icons.Default.Lock, contentDescription = null); Spacer(Modifier.width(5.dp)); Text("Privacy Policy", maxLines = 1) }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bqsqwadai.com/terms"))) }
                    ) { Icon(Icons.Default.Description, contentDescription = null); Spacer(Modifier.width(5.dp)); Text("Terms & Conditions", maxLines = 1) }
                }
            }
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
                                            .onFailure { deletionNotice = safeUserFacingIssue(it, "Could not cancel scheduled deletion. Please try again.") }
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
            if (settingsCategory == "All settings" || settingsCategory == "Account") {
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
                                Text("AI age · $aiAgeLabel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BqLogo(26.dp)
                                        Text("AI usage", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text(if (loadingAccount) "Checking…" else "Managed automatically", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Feature charges are handled automatically", style = MaterialTheme.typography.bodySmall)
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

            }
            if (settingsCategory == "All settings" || settingsCategory == "Account") {
item { SettingsSectionTitle("Account & security", "Identity, protection and device access") }
            if (adminAuthorized && (settingsCategory == "All settings" || settingsCategory == "Account")) {
                item {
                    FeatureCard("Admin Dashboard", "Manage BQ Sqwad AI app configuration, announcements, media and feature settings.", onOpenAdminDashboard)
                }
            }
            item {
                OutlinedButton(
                    onClick = { onSignOut() },
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
                    FeatureCard("Android permissions", "Review camera, microphone, notifications and other OS permissions.") {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                    }
                }
            }

            
            }
            if (settingsCategory == "All settings" || settingsCategory == "Payments & Billing") {
item { SettingsSectionTitle("Payments & billing", "Manage purchases and subscriptions in Google Play") }
            item {
                FeatureCard("BQ Sqwad AI catalog", "Browse available plans, eligible add-ons and credit packs for your account.", onOpenPlans)
            }
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
                                    Text("AI usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Eligible feature usage is handled automatically", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text("Managed automatically", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Feature usage is managed securely by BQ Sqwad AI.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    playManagement?.takeIf { it.optBoolean("hasSubscription", false) }?.let { billing ->
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Google Play billing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(if (billing.optBoolean("autoRenewEnabled", false)) "Auto-renewal is on" else "Auto-renewal is off", fontWeight = FontWeight.Bold)
                                Text("In-app purchases use Google Play Billing. Subscription changes and cancellations are managed on the Google Play subscriptions page.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(billing.optString("managementUrl")))) }, Modifier.weight(1f)) { Text("Manage") }
                                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(billing.optString("paymentMethodManagementUrl")))) }, Modifier.weight(1f)) { Text("Payment") }
                                }
                            }
                        }
                    }
                }
            }

            }
            if (settingsCategory == "All settings" || settingsCategory == "Account") {
                item {
                    SurfaceCard("AI age", "Your BQ Sqwad AI has been growing for $aiAgeLabel. Its age is based on the account's first recorded app activity.")
                }
            }
            if (settingsCategory == "All settings" || settingsCategory == "Appearance") {
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
                SurfaceCard("Chat background", "Choose a picture from your device to display behind Main Chat. Each successful change costs 2 credits; your chosen image stays on this device.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !changingChatBackground, onClick = { chatBackgroundPicker.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (changingChatBackground) "Applying…" else "Choose picture · 2 credits")
                    }
                    if (customChatBackgroundUri != null) {
                        OutlinedButton(enabled = !changingChatBackground, onClick = {
                            chatBackgroundPrefs.edit().remove("chat_background_uri").apply()
                            customChatBackgroundUri = null
                            (context as? android.app.Activity)?.recreate()
                        }) { Text("Remove") }
                    }
                }
                if (customChatBackgroundUri != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Background brightness · " + (customChatBackgroundBrightness * 100).roundToInt() + "%", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = customChatBackgroundBrightness,
                        onValueChange = { value ->
                            customChatBackgroundBrightness = value
                            chatBackgroundPrefs.edit().putFloat("chat_background_brightness", value).apply()
                        },
                        valueRange = 0.35f..1.8f
                    )
                    Text("Adjust brightness without re-uploading or spending credits again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Text size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Adjust text across the app from smaller to larger.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = textScale, onValueChange = { value ->
                        textScale = value
                        bqTextScaleState.floatValue = value
                        prefs.edit().putFloat("text_scale", value).apply()
                    }, onValueChangeFinished = {
                        scope.launch {
                            runCatching { WorkspaceApiClient().updateFeatureSettings("global", JSONObject().put("text_scale", textScale.toDouble())) }
                                .onFailure { settingsNotice = "Saved on this device, but could not sync text size yet." }
                            // Recreate after persisting so all native screens recalculate text metrics.
                            (context as? android.app.Activity)?.recreate()
                        }
                    }, valueRange = 0.85f..1.8f, steps = 18)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Smaller", style = MaterialTheme.typography.labelSmall)
                        Text((textScale * 100).toInt().toString() + "%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("Larger", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = {
                            textScale = 1.0f
                            bqTextScaleState.floatValue = 1.0f
                            prefs.edit().putFloat("text_scale", 1.0f).apply()
                            scope.launch {
                                runCatching { WorkspaceApiClient().updateFeatureSettings("global", JSONObject().put("text_scale", 1.0)) }
                                    .onFailure { settingsNotice = "Default text size was saved on this device but could not sync yet." }
                                (context as? android.app.Activity)?.recreate()
                            }
                        }
                    ) { Text("Reset text size") }
                }
            }
            
            }
            if (settingsCategory == "All settings" || settingsCategory == "Chat & AI Features") {
item { SettingsSectionTitle("Chat & AI Features", "Control how the assistant works for you") }
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

            
            }
            if (settingsCategory == "All settings" || settingsCategory == "Live AI") {
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

            
            }
            if (settingsCategory == "All settings" || settingsCategory == "Chat & AI Features") {
item { SettingsSectionTitle("AI features", "Open any AI workspace from here instead of the left menu") }
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
                    listOf(
                        Workspace.SEARCH, Workspace.TRUTH_GUARD, Workspace.FRONTIER_LAB,
                        Workspace.DATA_ANALYSIS, Workspace.VIDEO,
                        Workspace.PERSONAL, Workspace.LIFE, Workspace.COMPANION, Workspace.LEARNING,
                        Workspace.BUSINESS, Workspace.ORGANIZATION_STUDIO
                    ).forEach { feature ->
                        FeatureCard(feature.title, feature.description) { selectedAiFeature = feature }
                    }
                }
            }

            
            }
            if (settingsCategory == "All settings" || settingsCategory == "Notifications") {
item { SettingsSectionTitle("Notifications", "Stay informed without losing control") }
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

            
            }
            if (settingsCategory == "All settings" || settingsCategory == "Privacy & Data") {
item { SettingsSectionTitle("File Box", "Store uploaded files and pictures for quick access") }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatic expiry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Choose how long uploaded files, pictures and text documents remain in your File Box. Expired stored files are removed from private storage by the scheduled Worker cleanup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 14, 30, 90).forEach { days ->
                            FilterChip(selected = fileBoxRetentionDays == days, onClick = {
                                if (!savingRetention) {
                                    savingRetention = true
                                    scope.launch {
                                        runCatching { WorkspaceApiClient().setFileBoxRetentionDays(days) }
                                            .onSuccess {
                                                fileBoxRetentionDays = days
                                                prefs.edit().putInt("file_box_retention_days", days).apply()
                                                settingsNotice = "File Box retention set to $days days."
                                            }
                                            .onFailure { settingsNotice = "Could not save File Box retention. Your previous server setting remains active." }
                                        savingRetention = false
                                    }
                                }
                            }, enabled = !savingRetention, label = { Text("$days days") })
                        }
                    }
                    if (savingRetention) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            
            }
            if (settingsCategory == "All settings" || settingsCategory == "Privacy & Data") {
item { SettingsSectionTitle("Privacy & data", "Open the dedicated privacy center to manage consent and account data") }
            item {
                FeatureCard("Open Settings & Privacy", "Manage AI training participation, account data and account protection.") {
                    showSettingsPrivacyDirectory = true
                }
            }
            item {
                FeatureCard("File Box", "Keep uploaded pictures and documents available for quick reuse.") {
                    showFileBoxPanel = true
                }
            }
            item {
                FeatureCard("Projects", "Organize project notes and working documents.") {
                    showProjectsPanel = true
                }
            }
            item {
                FeatureCard("AI Training & Data", if (trainingConsent) "Participation is enabled; you can review or withdraw consent." else "Participation is off by default. Review the optional controls.") {
                    showTrainingSettings = true
                }
            }
            item {
                FeatureCard("Delete account and data", "Review the deletion grace period and schedule account-data deletion.") {
                    showDeleteAccount = true
                    deleteError = null
                }
            }

            
            }
            if (settingsCategory == "All settings" || settingsCategory == "About") {
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
            item {
                FeatureCard("About Me & AI Personality", "Choose your preferred response style and save optional notes locally on this device.") {
                    showAboutMePanel = true
                }
            }
            }
        }
    }

    if (showSettingsPrivacyDirectory) {
        ModalBottomSheet(onDismissRequest = { showSettingsPrivacyDirectory = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Settings & Privacy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("You control what is remembered, contributed and connected. AI training is optional and can be switched off at any time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FeatureCard("File Box", "Store uploaded files, pictures and text documents for quick access until they expire.") {
                    showSettingsPrivacyDirectory = false
                    showFileBoxPanel = true
                }
                FeatureCard("Projects", "Create and manage project notes and working documents.") {
                    showSettingsPrivacyDirectory = false
                    showProjectsPanel = true
                }
                FeatureCard("AI Training & Data", if (trainingConsent) "Participation is enabled. Review the safeguards and turn it off whenever you want." else "Participation is off. Review the optional contribution controls.") {
                    showSettingsPrivacyDirectory = false
                    showTrainingSettings = true
                }
                FeatureCard("Payment method", "Manage purchases and subscriptions through Google Play Billing.") {
                    showSettingsPrivacyDirectory = false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account"))) }
                        .onFailure { settingsNotice = "Google Play payment management could not be opened on this device." }
                }
                FeatureCard("Account protection", "Manage available sign-in and account security controls.") {
                    showSettingsPrivacyDirectory = false
                    onOpenAccountProtection()
                }
                FeatureCard("Delete account and data", "Schedule deletion of your account and associated app data.") {
                    showSettingsPrivacyDirectory = false
                    showDeleteAccount = true
                    deleteError = null
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
    if (showTrainingSettings) {
        ModalBottomSheet(onDismissRequest = { if (!trainingSaving) showTrainingSettings = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI Training & Data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("This optional setting allows BQ Sqwad AI to contribute eligible text-only, non-private prompts and responses to a protected training-data library. Attachments, camera frames, live audio, and chats marked Private are excluded. The server checks your current consent before saving anything. Participation can only be enabled after a server-verified adult or parental-consent gate; a client checkbox alone is not treated as proof.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("This builds a reviewable data library; it does not automatically retrain a foundation model or change model weights.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Contribute eligible chats", fontWeight = FontWeight.SemiBold)
                        Text(if (trainingConsent) "On — eligible text-only chats may be submitted." else "Off — chat text is not submitted to the training library.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = trainingConsent, enabled = !trainingSaving, onCheckedChange = { enabled ->
                        trainingSaving = true
                        consentStatus = "Saving your choice…"
                        scope.launch {
                            runCatching { WorkspaceApiClient().updateTrainingConsent(enabled) }
                                .onSuccess {
                                    trainingConsent = enabled
                                    prefs.edit().putBoolean("training_consent", enabled).apply()
                                    consentStatus = if (enabled) "AI training participation enabled." else "AI training participation disabled; stored contributions were requested for deletion."
                                }
                                .onFailure { consentStatus = "Could not update your choice. Your previous server setting remains in effect." }
                            trainingSaving = false
                        }
                    })
                }
                consentStatus?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { showTrainingSettings = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
    if (showFileBoxPanel) {
        FileBoxPanel(onDismiss = { showFileBoxPanel = false })
    }
    if (showProjectsPanel) {
        ProjectsPanel(onDismiss = { showProjectsPanel = false })
    }
    if (showAboutMePanel) {
        AboutMePanel(onDismiss = { showAboutMePanel = false })
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
                                showDeleteAccount = false
                                onSignOut()
                            }
                            .onFailure { deleteError = safeUserFacingIssue(it, "Account deletion failed. Please try again.") }
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
    val context = LocalContext.current
    val animationsEnabled = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }
    if (!animationsEnabled) {
        BqLogo(76.dp, modifier)
        return
    }
    val transition = rememberInfiniteTransition(label = "bq-floating-logo")
    val floatY by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "float-y"
    )
    val scale by transition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val tilt by transition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tilt"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    Box(modifier = modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(94.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha))
        )
        BqLogo(
            76.dp,
            Modifier.graphicsLayer {
                translationY = floatY
                scaleX = scale
                scaleY = scale
                rotationZ = tilt
            }
        )
    }
}

@Composable
private fun BirthdayConfetti(modifier: Modifier = Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "birthday-confetti")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "confetti-fall"
    )
    Canvas(modifier.fillMaxWidth().height(48.dp)) {
        val palette = listOf(
            androidx.compose.ui.graphics.Color(0xFF2F80ED),
            androidx.compose.ui.graphics.Color(0xFF18A999),
            androidx.compose.ui.graphics.Color(0xFFFFB020),
            androidx.compose.ui.graphics.Color(0xFFB56CE2),
            androidx.compose.ui.graphics.Color(0xFFFF6B6B)
        )
        repeat(28) { index ->
            val x = (index * 47.7f % size.width.coerceAtLeast(1f))
            val startY = (index * 19.3f % size.height.coerceAtLeast(1f))
            val y = (startY + phase * size.height) % size.height
            val radius = 2.2f + (index % 3) * 1.2f
            drawCircle(
                color = palette[index % palette.size],
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(x, y),
                alpha = 0.9f
            )
        }
    }
}
