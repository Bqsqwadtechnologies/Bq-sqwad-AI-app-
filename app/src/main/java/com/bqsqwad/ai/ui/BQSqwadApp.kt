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
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
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
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
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
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BQSqwadApp(modifier: Modifier = Modifier) = AuthGate(modifier)

@Composable
private fun AuthGate(modifier: Modifier) {
    /*
     * Guests may still browse the native shell. Once Firebase authentication exists,
     * however, MainShell is never rendered until the server has confirmed required consent.
     * This is deliberately fail-closed: a missing/error consent response goes to ConsentGate,
     * which can retry the authoritative GET /consent call.
     */
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    var consentAccepted by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { updated ->
            user = updated.currentUser
            consentAccepted = if (updated.currentUser == null) null else false
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    LaunchedEffect(user?.uid) {
        val signedIn = user
        if (signedIn == null) {
            consentAccepted = null
            return@LaunchedEffect
        }

        consentAccepted = null
        val result = runCatching { WorkspaceApiClient().consent() }
        result.onSuccess { consent ->
            val accepted = consent.optBoolean("requiredAccepted", false)
            consentAccepted = accepted
            context.getSharedPreferences("bq_consent", Context.MODE_PRIVATE).edit()
                .putBoolean("required_accepted", accepted)
                .putBoolean("training_consent", consent.optBoolean("trainingConsent", false))
                .apply()
        }.onFailure {
            // Do not fall back to a cached "accepted" value for access control.
            // ConsentGate will retry against the server and remains the only path forward.
            consentAccepted = false
        }
    }

    when {
        user == null -> MainShell(modifier)
        consentAccepted == true -> MainShell(modifier)
        else -> ConsentGate(modifier)
    }
}

private fun authSignOutForConsent() {
    FirebaseAuth.getInstance().signOut()
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
                    onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            runCatching { WorkspaceApiClient().saveConsent("2026-09", trainingConsent) }
                                .onSuccess {
                                    context.getSharedPreferences("bq_consent", Context.MODE_PRIVATE).edit()
                                        .putString("policy_version", "2026-09")
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
            enabled = !busy && name.isNotBlank() && email.isNotBlank() && password.length >= 6 &&
                password == confirmPassword,
            onClick = {
                busy = true
                error = null
                auth.createUserWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener { task ->
                        busy = false
                        if (!task.isSuccessful) {
                            error = friendlyAuthError(task.exception)
                            return@addOnCompleteListener
                        }
                        auth.currentUser?.sendEmailVerification()
                        onAuthenticated()
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
            TextButton(onClick = { browserUrl = "https://bqsqwadai.com/terms#:~:text=Using%20the%20service" }) { Text("Terms and Conditions") }
            Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    // This is the explicit Google button flow. It avoids presenting the
    // generic Credential Manager sheet (which can include passkeys) and lets
    // users add/re-authenticate a Google account when necessary.
    val googleOption = GetSignInWithGoogleOption.Builder(
        "848359744340-kk08ve8dlk9o80584ijl214d6h25m2gt.apps.googleusercontent.com"
    ).build()
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
private fun MainShell(modifier: Modifier) {
    val email = FirebaseAuth.getInstance().currentUser?.email.orEmpty()
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

    LaunchedEffect(email) {
        // The backend is authoritative for admin visibility. Normal users never receive
        // an Admin workspace merely because of a client-side email comparison.
        adminAuthorized = runCatching {
            WorkspaceApiClient().adminAccess().optBoolean("allowed")
        }.getOrDefault(false)
    }

    LaunchedEffect(email) {
        accessLoading = true
        val api = WorkspaceApiClient()
        runCatching {
            val catalog = api.nativeCatalog()
            val entitlements = api.entitlements()
            val admin = runCatching { api.adminAccess().optBoolean("allowed") }.getOrDefault(false)
            val testMode = if (admin) runCatching { AdminAdvancedClient().testMode() }.getOrDefault(false) else false
            WorkspaceAccessSnapshot.fromCatalog(catalog, entitlements, admin, testMode)
        }.onSuccess { workspaceAccess = it }
         .onFailure { workspaceAccess = WorkspaceAccessSnapshot.empty() }
        accessLoading = false
    }

    val openWorkspace: (Workspace) -> Unit = { selected ->
        drawerOpen = false
        if (selected == Workspace.ADMIN) {
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
                    items(Workspace.values().filter { it != Workspace.CHAT && (it != Workspace.ADMIN || adminAuthorized) }) { w ->
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
        when {
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
                        onSettings = { featureSettings = workspace!! }
                    )
                }
            }
            else -> MainChatScreen(
                modifier = Modifier.fillMaxSize(),
                openHub = { drawerOpen = true },
                openFeatures = { featureHub = true },
                openSettings = { settings = true },
                onLearnMore = { featureInfo = Workspace.CHAT },
                onPrivacy = { featurePrivacy = Workspace.CHAT }
            )
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

private data class ChatMessage(val mine: Boolean, val text: String, val id: String = UUID.randomUUID().toString(), val prompt: String? = null)

@Composable
private fun MainChatScreen(modifier: Modifier, openHub: () -> Unit, openFeatures: () -> Unit, openSettings: () -> Unit, onLearnMore: () -> Unit, onPrivacy: () -> Unit) {
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
    var imageMode by remember { mutableStateOf(false) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var selectedAdvanced by remember { mutableStateOf<AppFeature?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    var streamJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (spoken.isNotBlank()) message = if (message.isBlank()) spoken else "$message $spoken"
        else micError = "No speech was detected. Try again."
    }

    fun sendAuthenticatedMessage(text: String) {
        if ((text.isBlank() && attachments.isEmpty()) || busy) return
        val activeCapability = selectedAdvanced?.capability ?: "chat"
        val activeSystem = selectedAdvanced?.let { "You are operating in the BQ Sqwad AI ${it.title} mode. Use the capabilities of this mode when relevant, while remaining accurate about what you can and cannot do." }
        if (imageMode && text.isNotBlank() && attachments.isEmpty()) {
            val promptText = text.trim()
            message = ""
            imageMode = false
            messages.add(ChatMessage(true, promptText))
            val aiIndex = messages.size
            messages.add(ChatMessage(false, "Generating image…"))
            busy = true
            error = null
            streamJob = scope.launch(Dispatchers.IO) {
                try {
                    val generated = GenerationApiClient().generateImage(promptText)
                    scope.launch(Dispatchers.Main) {
                        messages[aiIndex] = messages[aiIndex].copy(text = "Image generated. Open the generated image: " + generated.url)
                        busy = false
                        streamJob = null
                    }
                } catch (t: Throwable) {
                    scope.launch(Dispatchers.Main) {
                        if (messages.getOrNull(aiIndex)?.text == "Generating image…") messages.removeAt(aiIndex)
                        error = t.message ?: "Image generation failed"
                        busy = false
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
                IconButton(onClick = onLearnMore) {
                    Icon(Icons.Default.Info, contentDescription = "Learn more about Main Chat")
                }
                IconButton(onClick = onPrivacy) {
                    Icon(Icons.Default.Lock, contentDescription = "Main Chat privacy")
                }
                IconButton(onClick = openSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
            if (ADVANCED_APP_FEATURES.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Advanced AI", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            selectedAdvanced?.title ?: "Main Chat",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedAdvanced == null,
                                onClick = { selectedAdvanced = null },
                                label = { Text("Main Chat") },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) }
                            )
                        }
                        items(ADVANCED_APP_FEATURES) { feature ->
                            FilterChip(
                                selected = selectedAdvanced?.id == feature.id,
                                onClick = { selectedAdvanced = if (selectedAdvanced?.id == feature.id) null else feature },
                                label = { Text(feature.title, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.Bolt, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
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
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                .padding(top = 20.dp, bottom = 12.dp),
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
                                        .size(116.dp)
                                        .border(
                                            2.dp,
                                            Color(0xFF1457FF).copy(alpha = 0.55f + (logoPulse * 0.45f)),
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
                                    color = Color(0xFF1457FF).copy(alpha = 0.94f),
                                    shadowElevation = 8.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Text("AI Features", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = imageMode,
                    onClick = { imageMode = !imageMode },
                    enabled = !busy,
                    label = { Text(if (imageMode) "Image generation on" else "Generate image") },
                    leadingIcon = { Icon(Icons.Default.Image, null) }
                )
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
            val composerBlue = Color(0xFF1457FF).copy(alpha = 0.55f + (pulse * 0.45f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .size(58.dp)
                        .border(
                            2.dp,
                            Color(0xFF1457FF).copy(alpha = 0.75f + pulse * 0.25f),
                            CircleShape
                        ),
                    shape = CircleShape,
                    color = Color(0xFF1457FF),
                    shadowElevation = 18.dp
                ) {
                    IconButton(
                        onClick = openFeatures,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Open AI Features",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, composerBlue, composerShape),
                    shape = composerShape,
                    color = Color.White,
                    shadowElevation = 10.dp
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(
                            modifier = Modifier.width(52.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            IconButton(
                                onClick = {
                                    micError = null
                                    speechLauncher.launch(
                                        Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(
                                                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                            )
                                            putExtra(
                                                android.speech.RecognizerIntent.EXTRA_PROMPT,
                                                "Speak your message"
                                            )
                                        }
                                    )
                                },
                                enabled = !busy
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Speech to text",
                                    tint = Color(0xFF1457FF)
                                )
                            }
                            IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
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
                            placeholder = {
                                Text(
                                    "Message BQ Sqwad AI",
                                    color = Color(0xFF667085)
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 68.dp),
                            minLines = 2,
                            maxLines = 6,
                            textStyle = LocalTextStyle.current.copy(
                                color = Color.Black,
                                fontWeight = FontWeight.Medium
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                                disabledTextColor = Color.Black,
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
                            enabled = message.isNotBlank() || attachments.isNotEmpty() || busy,
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
                                tint = Color(0xFF1457FF),
                                modifier = Modifier.size(28.dp)
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
    Workspace.ORGANIZATION_STUDIO -> "Organization Studio AI provides professional organization intelligence, production coordination and the connected organization tools exposed by the backend."
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
    Workspace.ORGANIZATION_STUDIO -> "Organization Studio AI"
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
    "rate_limiting", "abuse_protection", "incident_system", "ai_evaluation_center"
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

@Composable
private fun FeatureHub(
    onBack: () -> Unit,
    onPlans: () -> Unit,
    onHistory: () -> Unit,
    onWorkspace: (Workspace) -> Unit,
    onAdvancedFeature: (AppFeature) -> Unit
) {
    val visibleWorkspaces = Workspace.values().filter { it != Workspace.CHAT && it != Workspace.ADMIN }
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.CloudDone, null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Live feature catalogue", fontWeight = FontWeight.SemiBold)
                            Text("Access, plans, limits and availability come from the server-controlled catalogue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
    onSettings: () -> Unit
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
        Column(Modifier.padding(p).padding(16.dp).fillMaxSize()) {
            when (w) {
                Workspace.VIDEO -> VideoGenerationWorkspace()
                Workspace.SEARCH -> SearchWorkspace()
                Workspace.TRUTH_GUARD -> TruthGuardWorkspace()
                Workspace.FRONTIER_LAB -> FrontierLabWorkspace()
                Workspace.DATA_ANALYSIS -> DataAnalysisWorkspace()
                Workspace.CAMERA -> CameraWorkspace()
                Workspace.VOICE -> VoiceWorkspace()
                Workspace.PERSONAL -> PersonalIntelligenceWorkspace()
                Workspace.LIFE -> LifeMattersWorkspace()
                Workspace.COMPANION -> CompanionWorkspace()
                Workspace.LEARNING -> LearningStudioWorkspace()
                Workspace.BUSINESS -> BusinessWorkspaceScreen()
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
private fun CameraWorkspace() {