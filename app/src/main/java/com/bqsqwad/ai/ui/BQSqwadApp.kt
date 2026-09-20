package com.bqsqwad.ai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
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
import com.bqsqwad.ai.core.WorkspaceApiClient
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
    var replyJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showTools by remember { mutableStateOf(false) }
    var showPlans by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<Pair<Boolean,String>>() }
    if (showTools) ModalBottomSheet(onDismissRequest={showTools=false}) { Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("BQ Sqwad AI Workspaces",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold); Workspace.values().filter{it!=Workspace.CHAT}.forEach{w->TextButton(onClick={showTools=false}){Text(w.title)}}; TextButton(onClick={showTools=false;showPlans=true}){Text("Credits & Plans")} } }
    if (showPlans) PlansSheet { showPlans=false }
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
                IconButton(onClick = { showTools = true }) { Icon(Icons.Default.Add, "AI workspaces") }
                OutlinedTextField(message, { message = it }, placeholder = { Text("Message BQ Sqwad AI") }, modifier = Modifier.weight(1f), maxLines = 5)
                IconButton(enabled = message.isNotBlank() && !sending, onClick = {
                    val text = message.trim(); message = ""; error = null; messages.add(true to text); sending = true
                    replyJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        try {
                            val reply = ChatApiClient().sendMessage(text)
                            messages.add(false to reply.text)
                        } catch (t: Throwable) {
                            error = t.message ?: "AI request failed"
                        } finally { sending = false }
                    }
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
    Scaffold(topBar={TopAppBar(title={Text(if(w==Workspace.BUSINESS)"Business AI" else w.title,fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onBack){Icon(Icons.Default.ArrowBack,"Back")}})}){p->
        Column(Modifier.padding(p).padding(16.dp).fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
            when(w){
                Workspace.CHAT->MainChatScreen(Modifier.fillMaxSize())
                Workspace.SEARCH->SearchWorkspace()
                Workspace.CAMERA->CameraWorkspace()
                Workspace.VOICE->VoiceWorkspace()
                Workspace.PERSONAL->AssistantWorkspace("Personal Intelligence","Plans, organizes and remembers your goals and preferences.")
                Workspace.LIFE->AssistantWorkspace("Life Matters","A dedicated workspace for everyday planning, decisions and practical guidance.")
                Workspace.LEARNING->AssistantWorkspace("Learning Studio","Lessons, explanations, practice and study support.")
                Workspace.BUSINESS->AssistantWorkspace("Business AI","AI agents, knowledge, workflows, connections and business operations.")
                Workspace.ADMIN->AdminControlCenter()
            }
        }
    }
}

@Composable private fun SearchWorkspace(){
    val scope=rememberCoroutineScope(); var q by remember{mutableStateOf("")}; var deep by remember{mutableStateOf(false)}; var busy by remember{mutableStateOf(false)}; var error by remember{mutableStateOf<String?>(null)}; val results=remember{mutableStateListOf<com.bqsqwad.ai.core.SearchSource>()}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Search the web with current sources.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(q,{q=it},modifier=Modifier.fillMaxWidth(),label={Text("Search")})
        Row(verticalAlignment=Alignment.CenterVertically){Switch(deep,{deep=it});Text("Deep Research")}
        Button(enabled=q.isNotBlank()&&!busy,onClick={busy=true;error=null;scope.launch{try{results.clear();results.addAll(WorkspaceApiClient().search(q.trim(),deep).sources)}catch(t:Throwable){error=t.message?: "Search failed"}finally{busy=false}}}){Text(if(busy)"Searching…" else "Search")}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        results.forEach{r->SurfaceCard(r.title,r.snippet ?: r.url)}
    }
}

@Composable private fun CameraWorkspace(){
    var granted by remember{mutableStateOf(false)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted=it}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("AI Live Camera",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("Camera access is permission-gated. Visual AI and device actions remain separate and require explicit user approval.")
        Button(onClick={launcher.launch(Manifest.permission.CAMERA)}){Icon(Icons.Default.Videocam,null);Spacer(Modifier.width(8.dp));Text(if(granted)"Camera permission granted" else "Enable camera")}
        SurfaceCard("Live vision controls","Object and scene understanding, visual questions, location assistance and approved device actions are designed for this workspace.")
    }
}

@Composable private fun VoiceWorkspace(){
    val context=LocalContext.current; var transcript by remember{mutableStateOf("")}; var listening by remember{mutableStateOf(false)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->listening=false;transcript=r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?:transcript}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Live Voice AI",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("Voice input is handled by Android speech recognition; microphone permission is requested by the recognizer.")
        Button(onClick={val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak to BQ Sqwad AI")};listening=true;launcher.launch(i)}){Icon(Icons.Default.Mic,null);Spacer(Modifier.width(8.dp));Text(if(listening)"Listening…" else "Start voice input")}
        if(transcript.isNotBlank())SurfaceCard("Transcript",transcript)
        SurfaceCard("Voice controls","Barge-in, mute, voice selection and conversation history belong to this workspace and remain independently controllable.")
    }
}

@Composable private fun AssistantWorkspace(title:String,body:String){
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant);SurfaceCard("Workspace ready","Use this dedicated AI workspace without mixing its controls into Main Chat.")}
}


@Composable private fun PlansSheet(onDismiss:()->Unit){ val scope=rememberCoroutineScope(); var busy by remember{mutableStateOf(true)}; var error by remember{mutableStateOf<String?>(null)}; val plans=remember{mutableStateListOf<com.bqsqwad.ai.core.PlanInfo>()}; LaunchedEffect(Unit){try{plans.addAll(WorkspaceApiClient().plans())}catch(t:Throwable){error=t.message?: "Unable to load plans"}finally{busy=false}}; ModalBottomSheet(onDismissRequest=onDismiss){Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Credits & Plans",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());error?.let{Text(it,color=MaterialTheme.colorScheme.error)};plans.forEach{p->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(p.name,fontWeight=FontWeight.Bold);Text("Amount: "+p.amountMinor.toLong()+" "+p.currency+" • "+p.billingPeriod);if(p.features.isNotEmpty())Text(p.features.joinToString(" • "));Button(onClick={scope.launch{try{val response=WorkspaceApiClient().paymentInitialize(p.id);val url=response.optString("authorizationUrl");if(url.isNotBlank())LocalContext.current.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)))}catch(t:Throwable){error=t.message?: "Payment initialization failed"}}}){Text("Continue to Paystack")}}}};Text("Auto-renew is controlled separately and is off by default.",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
