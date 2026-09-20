package com.bqsqwad.ai.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.bqsqwad.ai.R
import com.bqsqwad.ai.core.ChatApiClient
import com.bqsqwad.ai.domain.Workspace
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

private const val PRIVACY_URL = "https://bqsqwadai.com/privacy"
private const val WALLPAPER_PREF = "bq_wallpaper_uri"

@Composable
fun AuthGate(modifier: Modifier = Modifier) {
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    if (user == null) AuthScreen()
    else NativeApp(modifier, user?.email.orEmpty())
}

@Composable
private fun AuthScreen() {
    val auth = remember { FirebaseAuth.getInstance() }
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (email.isBlank() || password.length < 6) {
            error = "Enter a valid email and a password of at least 6 characters."
            return
        }
        busy = true
        val task = if (register) auth.createUserWithEmailAndPassword(email.trim(), password)
        else auth.signInWithEmailAndPassword(email.trim(), password)
        task.addOnCompleteListener {
            busy = false
            if (!it.isSuccessful) error = it.exception?.message ?: "Authentication failed."
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Card(Modifier.fillMaxWidth().widthIn(max = 460.dp), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.bq_sqwad_logo), "BQ Sqwad AI", Modifier.size(82.dp))
                    Text("BQ Sqwad AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("The AI That Grows With You", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(::submit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (register) "Create account" else "Sign in")
                    }
                    TextButton({ register = !register }, enabled = !busy) {
                        Text(if (register) "Already have an account? Sign in" else "Create a new account")
                    }
                    PrivacyButton()
                }
            }
        }
    }
}

@Composable
private fun NativeApp(modifier: Modifier, email: String) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "chat", modifier = modifier) {
        composable("chat") {
            MainChatScreen(
                onSettings = { nav.navigate("settings") },
                onWorkspace = { nav.navigate("workspace/" + it.name) }
            )
        }
        composable("settings") { SettingsScreen(email) { nav.popBackStack() } }
        composable("workspace/{name}") { backStack ->
            val w = runCatching { Workspace.valueOf(backStack.arguments?.getString("name") ?: "CHAT") }.getOrDefault(Workspace.CHAT)
            WorkspaceScreen(w) { nav.popBackStack() }
        }
    }
}

@Composable
private fun MainChatScreen(onSettings: () -> Unit, onWorkspace: (Workspace) -> Unit) {
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<Pair<Boolean, String>>() }
    val scope = rememberCoroutineScope()
    val api = remember { ChatApiClient() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("BQ Sqwad AI", fontWeight = FontWeight.Bold); Text("Main Chat", style = MaterialTheme.typography.labelSmall) } },
            actions = {
                Box {
                    IconButton({ menu = !menu }) { Icon(Icons.Default.Add, "AI features") }
                    DropdownMenu(menu, { menu = false }) {
                        Workspace.entries.filter { it != Workspace.CHAT }.forEach { w ->
                            DropdownMenuItem(
                                text = { Text(if (w == Workspace.BUSINESS) "Business AI" else w.title) },
                                leadingIcon = { Icon(iconFor(w), null) },
                                onClick = { menu = false; onWorkspace(w) }
                            )
                        }
                    }
                }
                IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings") }
            }
        )
    }) { padding ->
        ChatWallpaper(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    FloatingBrandLogo()
                }
                if (messages.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("How can I help?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Ask anything or use + for another AI workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(messages) { ChatBubble(it.first, it.second) }
                    }
                }
                error?.let {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Surface(Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(28.dp), tonalElevation = 5.dp) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
                        IconButton({ onWorkspace(Workspace.SEARCH) }) { Icon(Icons.Default.Add, "More AI features") }
                        OutlinedTextField(
                            message, { message = it; error = null },
                            Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                            placeholder = { Text("Message BQ Sqwad AI") }, maxLines = 7,
                            trailingIcon = {
                                IconButton(
                                    enabled = message.isNotBlank() && !sending,
                                    onClick = {
                                        val outgoing = message.trim()
                                        message = ""
                                        sending = true
                                        error = null
                                        messages.add(true to outgoing)
                                        scope.launch {
                                            try { messages.add(false to api.sendMessage(outgoing).text) }
                                            catch (t: Throwable) { error = t.message ?: "The AI request failed." }
                                            finally { sending = false }
                                        }
                                    }
                                ) {
                                    if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.ArrowUpward, "Send")
                                }
                            }
                        )
                        IconButton({ onWorkspace(Workspace.VOICE) }) { Icon(Icons.Default.Mic, "Voice") }
                    }
                }
                Text("AI can make mistakes. Check important information.", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivacyButton()
            }
        }
    }
}

@Composable private fun ChatBubble(mine: Boolean, text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(Modifier.widthIn(max = 340.dp), shape = RoundedCornerShape(20.dp), color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(text, Modifier.padding(horizontal = 15.dp, vertical = 11.dp), color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable private fun FloatingBrandLogo() {
    Surface(Modifier.size(84.dp).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .7f), CircleShape), CircleShape, tonalElevation = 7.dp, shadowElevation = 8.dp) {
        Box(contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.bq_sqwad_logo), "BQ Sqwad AI", Modifier.size(64.dp)) }
    }
}

@Composable private fun ChatWallpaper(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bq_sqwad_ui", 0) }
    val uri = remember { mutableStateOf(prefs.getString(WALLPAPER_PREF, null)) }
    val bitmap = remember(uri.value) {
        uri.value?.let { value -> runCatching {
            context.contentResolver.openInputStream(Uri.parse(value)).use { input -> input?.let { BitmapFactory.decodeStream(it)?.asImageBitmap() } }
        }.getOrNull() }
    }
    Box(modifier) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .34f) }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = if (bitmap == null) 1f else .84f)))
        content()
    }
}

@Composable private fun SettingsScreen(email: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bq_sqwad_ui", 0) }
    var wallpaper by remember { mutableStateOf(prefs.getString(WALLPAPER_PREF, null)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.edit().putString(WALLPAPER_PREF, uri.toString()).apply()
            wallpaper = uri.toString()
        }
    }
    Scaffold(topBar = {
        TopAppBar({ Text("Settings", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { p ->
        Column(Modifier.padding(p).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SurfaceCard("Account", email)
            SurfaceCard("Chat appearance", "Choose your own wallpaper. The BQ Sqwad logo stays visible professionally above the background.")
            Button({ picker.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp))
                Text(if (wallpaper == null) "Choose app wallpaper" else "Change app wallpaper")
            }
            if (wallpaper != null) OutlinedButton({ prefs.edit().remove(WALLPAPER_PREF).apply(); wallpaper = null }, Modifier.fillMaxWidth()) { Text("Restore default wallpaper") }
            OutlinedButton({ openExternal(PRIVACY_URL, context) }, Modifier.fillMaxWidth()) { Text("Privacy Policy") }
            OutlinedButton({ context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, Modifier.fillMaxWidth()) { Text("Notification settings") }
            Button({ FirebaseAuth.getInstance().signOut() }, Modifier.fillMaxWidth()) { Text("Sign out") }
        }
    }
}

@Composable
private fun PrivacyButton() {
    val context = LocalContext.current
    TextButton({ openExternal(PRIVACY_URL, context) }) { Text("Privacy Policy") }
}

private fun openExternal(url: String, context: android.content.Context) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun iconFor(w: Workspace) = when (w) {
    Workspace.CHAT -> Icons.Default.AutoAwesome
    Workspace.SEARCH -> Icons.Default.Language
    Workspace.CAMERA -> Icons.Default.Videocam
    Workspace.VOICE -> Icons.Default.GraphicEq
    Workspace.PERSONAL -> Icons.Default.Person
    Workspace.LIFE -> Icons.Default.FavoriteBorder
    Workspace.LEARNING -> Icons.Default.School
    Workspace.BUSINESS -> Icons.Default.Business
    Workspace.ADMIN -> Icons.Default.AdminPanelSettings
}
