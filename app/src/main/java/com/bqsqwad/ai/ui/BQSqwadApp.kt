package com.bqsqwad.ai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.bqsqwad.ai.core.ChatApiClient
import com.bqsqwad.ai.domain.Workspace
import com.google.firebase.auth.FirebaseAuth

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
    if (user == null) SignInScreen() else MainChatScreen(modifier)
}

@Composable
private fun SignInScreen() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(50.dp))
        LogoBubble(Modifier.size(92.dp))
        Text("BQ Sqwad AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Sign in to continue", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = {
            busy = true; error = null
            authSignIn(email.trim(), password) { msg -> busy = false; error = msg }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Signing in…" else "Sign in") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun authSignIn(email: String, password: String, done: (String?) -> Unit) {
    FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
        .addOnCompleteListener { done(if (it.isSuccessful) null else (it.exception?.message ?: "Sign-in failed")) }
}

@Composable
private fun MainChatScreen(modifier: Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bq_ui", Context.MODE_PRIVATE) }
    var wallpaper by remember { mutableStateOf(prefs.getInt("wallpaper", 0)) }
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<Pair<Boolean,String>>() }
    Box(modifier.fillMaxSize()) {
        WallpaperBackground(wallpaper)
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FloatingLogo(Modifier.size(54.dp))
                Row {
                    IconButton(onClick = { wallpaper = (wallpaper + 1) % 5; prefs.edit().putInt("wallpaper", wallpaper).apply() }) { Icon(Icons.Default.Wallpaper, "Change wallpaper") }
                    IconButton(onClick = { FirebaseAuth.getInstance().signOut() }) { Icon(Icons.Default.Logout, "Sign out") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Main Chat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Ask BQ Sqwad AI anything", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                messages.forEach { (mine, text) ->
                    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth(if (mine) 0.88f else 1f).align(if (mine) Alignment.End else Alignment.Start)) {
                        Text(text, Modifier.padding(14.dp))
                    }
                }
                if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { wallpaper = (wallpaper + 1) % 5; prefs.edit().putInt("wallpaper", wallpaper).apply() }) { Icon(Icons.Default.Add, "More") }
                OutlinedTextField(message, { message = it }, placeholder = { Text("Message BQ Sqwad AI") }, modifier = Modifier.weight(1f), maxLines = 5)
                IconButton(enabled = message.isNotBlank() && !sending, onClick = {
                    val text = message.trim(); message = ""; error = null; messages.add(true to text); sending = true
                    LaunchedEffect(Unit) {}
                }) { Icon(Icons.Default.Send, "Send") }
            }
        }
        FloatingLogo(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 92.dp).size(62.dp))
    }
}

@Composable
private fun WallpaperBackground(index: Int) {
    val brush = when (index) {
        1 -> Brush.linearGradient(listOf(Color(0xFF071A2B), Color(0xFF123D5A)))
        2 -> Brush.linearGradient(listOf(Color(0xFF120B2B), Color(0xFF263A73)))
        3 -> Brush.linearGradient(listOf(Color(0xFF061E1A), Color(0xFF164B3D)))
        4 -> Brush.linearGradient(listOf(Color(0xFF1D1022), Color(0xFF4C244E)))
        else -> Brush.linearGradient(listOf(Color(0xFF05070D), Color(0xFF10243A)))
    }
    Box(Modifier.fillMaxSize().background(brush))
}

@Composable
private fun FloatingLogo(modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF5FB7FF), Color(0xFF0A5DB8), Color.Transparent)))) {
        LogoBubble(Modifier.fillMaxSize().padding(8.dp))
    }
}

@Composable
private fun LogoBubble(modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Text("BQ", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun WorkspaceScreen(w: Workspace, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (w == Workspace.BUSINESS) "Business AI" else w.title, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { p -> Column(Modifier.padding(p).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(iconFor(w), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        when (w) {
            Workspace.CHAT -> Text("Main Chat")
            Workspace.SEARCH -> SurfaceCard("Web Search", "Server-side web search is connected to the backend.")
            Workspace.CAMERA -> SurfaceCard("AI Live Camera", "Camera workspace.")
            Workspace.VOICE -> SurfaceCard("Live Voice AI", "Voice workspace.")
            Workspace.PERSONAL -> SurfaceCard("Personal Intelligence", "Personal workspace.")
            Workspace.LIFE -> SurfaceCard("Life Matters", "Life workspace.")
            Workspace.LEARNING -> SurfaceCard("Learning Studio", "Learning workspace.")
            Workspace.BUSINESS -> SurfaceCard("Business AI", "Agents, knowledge, connections, workflows and intelligence.")
            Workspace.ADMIN -> AdminControlCenter()
        }
    }}
}

@Composable private fun iconFor(w: Workspace) = when (w) {
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

@Composable
fun SurfaceCard(title: String, body: String) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
