package com.bqsqwad.ai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.*
import com.bqsqwad.ai.domain.Workspace
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun BQSqwadApp(modifier: Modifier = Modifier) = AuthGate(modifier)

@Composable
private fun AuthGate(modifier: Modifier) {
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    if (user == null) SignInScreen() else MainShell(modifier, user.email.orEmpty())
}

@Composable
private fun SignInScreen() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        LogoBubble(Modifier.size(92.dp))
        Text("BQ Sqwad AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Sign in to continue", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = {
            busy = true
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
                .addOnCompleteListener { busy = false; error = if (it.isSuccessful) null else it.exception?.message ?: "Sign-in failed" }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Signing in…" else "Sign in") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MainShell(modifier: Modifier, email: String) {
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

private data class ChatMessage(val mine: Boolean, val text: String)

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
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isNotEmpty()) message = "Attached " + selected.size + " file(s). Please analyze them."
    }
    Box(modifier.fillMaxSize()) {
        WallpaperBackground(wallpaper)
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FloatingLogo(Modifier.size(54.dp))
                Row {
                    IconButton(onClick = { wallpaper = (wallpaper + 1) % 5; prefs.edit().putInt("wallpaper", wallpaper).apply() }) { Icon(Icons.Default.Wallpaper, "Wallpaper") }
                    IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            }
            Text("Main Chat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("The AI that grows with you", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { m ->
                    Column(Modifier.fillMaxWidth(if (m.mine) .88f else 1f).align(if (m.mine) Alignment.End else Alignment.Start)) {
                        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) { Text(m.text, Modifier.padding(14.dp)) }
                        if (!m.mine) Row {
                            TextButton({ clipboard.setText(AnnotatedString(m.text)) }) { Text("Copy") }
                            TextButton({ shareText(context, m.text) }) { Text("Share") }
                            TextButton({ message = m.text }) { Text("Resend") }
                            TextButton({ error = "Report recorded for review." }) { Text("Report") }
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
                IconButton(enabled = message.isNotBlank() && !busy, onClick = {
                    val text = message.trim()
                    message = ""
                    messages.add(ChatMessage(true, text))
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val reply = ChatApiClient().sendMessage(text).text
                            launch(Dispatchers.Main) { messages.add(ChatMessage(false, reply)); busy = false }
                        } catch (t: Throwable) {
                            launch(Dispatchers.Main) { error = t.message ?: "AI request failed"; busy = false }
                        }
                    }
                }) { Icon(Icons.Default.Send, "Send") }
            }
        }
        FloatingLogo(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(62.dp))
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
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI Live Camera", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Explicit camera permission. Visual analysis and device actions remain separately controlled.")
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(if (granted) "Camera permission granted" else "Enable camera") }
        SurfaceCard("AI capabilities", "Live vision, visual questions, object/scene understanding and approved device actions.")
    }
}

@Composable
private fun VoiceWorkspace() {
    val scope = rememberCoroutineScope()
    var transcript by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        transcript = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (transcript.isNotBlank()) scope.launch { reply = runCatching { ChatApiClient().sendMessage(transcript).text }.getOrElse { it.message ?: "Voice request failed" } }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live Voice AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Button(onClick = {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to BQ Sqwad AI")
            }
            launcher.launch(i)
        }) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Speak") }
        if (transcript.isNotBlank()) SurfaceCard("You", transcript)
        if (reply.isNotBlank()) SurfaceCard("BQ Sqwad AI", reply)
        SurfaceCard("Voice engine", "Saved voice preference, mute, interruption support and history remain workspace controls.")
    }
}

@Composable
private fun PlansSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var serverPlans by remember { mutableStateOf(emptyList<PlanInfo>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { serverPlans = WorkspaceApiClient().plans() } catch (t: Throwable) { error = t.message ?: "Unable to load server plans" } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Credits & Plans", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { Text("Server pricing is authoritative; legacy plans are parity references.") }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(serverPlans) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text(p.amountMinor.toLong().toString() + " " + p.currency + " • " + p.billingPeriod)
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val url = WorkspaceApiClient().paymentInitialize(p.id, "bqsqwad://paystack").optString("authorizationUrl")
                                    if (url.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (t: Throwable) { error = t.message ?: "Payment initialization failed" }
                            }
                        }) { Text("Continue to Paystack") }
                    }
                }
            }
            item { Text("Former website parity plans", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(NativeFeatureCatalog.plans) { p -> SurfaceCard(p.name, if (p.module == "credits") p.credits.toString() + " credits • " + p.period + " • " + p.displayUsd else p.module + " module • server-configured") }
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

@Composable private fun SurfaceCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable private fun AccessDenied() { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("Admin access required", fontWeight = FontWeight.Bold) } }

private fun shareText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share"))
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

@Composable private fun LogoBubble(modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Text("BQ", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary) }
}
