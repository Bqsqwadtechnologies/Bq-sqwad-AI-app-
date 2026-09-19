package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.res.painterResource\nimport com.bqsqwad.ai.R
import androidx.navigation.compose.*
import com.bqsqwad.ai.domain.Workspace
import com.bqsqwad.ai.core.AdminAdvancedClient
import com.bqsqwad.ai.core.AdvancedAdminOption
import kotlinx.coroutines.launch

@Composable
fun BQSqwadApp(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home", modifier = modifier) {
        composable("home") { HomeScreen { nav.navigate("workspace/" + it.name) } }
        composable("workspace/{name}") { backStack ->
            val workspace = runCatching { Workspace.valueOf(backStack.arguments?.getString("name") ?: "CHAT") }.getOrDefault(Workspace.CHAT)
            WorkspaceScreen(workspace, nav::popBackStack)
        }
    }
}

@Composable
private fun HomeScreen(onOpen: (Workspace) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("BQ Sqwad AI", fontWeight = FontWeight.Bold); Text("The AI That Grows With You", style = MaterialTheme.typography.labelSmall) } },
            actions = { IconButton({ onOpen(Workspace.ADMIN) }) { Icon(Icons.Default.Settings, "Admin") } }
        )
    }) { p ->
        Column(Modifier.padding(p).padding(18.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("What do you want to accomplish?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            MainChatCard { onOpen(Workspace.CHAT) }
            Text("AI Workspaces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(Workspace.entries.filter { it != Workspace.CHAT && it != Workspace.ADMIN }) { w -> WorkspaceCard(w) { onOpen(w) } }
            }
        }
    }
}

@Composable
private fun MainChatCard(onClick: () -> Unit) {
    Card(onClick, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("Main Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            Text("Fast conversation, voice-to-text, optional spoken replies. Image, video and file analysis require a subscription.")
            OutlinedTextField("", {}, enabled = false, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Message BQ Sqwad AI") }, trailingIcon = { Icon(Icons.Default.Mic, "Voice") })
        }
    }
}

@Composable
private fun WorkspaceCard(w: Workspace, onClick: () -> Unit) {
    Card(onClick, Modifier.heightIn(min = 145.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(iconFor(w), null, tint = MaterialTheme.colorScheme.primary)
            Text(w.title, fontWeight = FontWeight.Bold)
            Text(w.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkspaceScreen(w: Workspace, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar({ Text(w.title, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { p ->
        Column(Modifier.padding(p).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(iconFor(w), null, tint = MaterialTheme.colorScheme.primary, Modifier.size(40.dp))
            Text(w.description, style = MaterialTheme.typography.titleMedium)
            when (w) {
                Workspace.CHAT -> ChatSurface()
                Workspace.SEARCH -> SearchSurface()
                Workspace.CAMERA -> CameraSurface()
                Workspace.VOICE -> VoiceSurface()
                Workspace.PERSONAL -> SimpleSurface("Planning, tasks, memory-aware assistance and personal organization.")
                Workspace.LIFE -> SimpleSurface("A dedicated Life Matters workspace with its own entitlement and safety controls.")
                Workspace.LEARNING -> SimpleSurface("Subjects, custom topics, lessons and voice teaching live here.")
                Workspace.BUSINESS -> BusinessSurface()
                Workspace.ADMIN -> AdminSurface()
            }
        }
    }
}

@Composable private fun ChatSurface() {
    var message by remember { mutableStateOf("") }
    var showAttachments by remember { mutableStateOf(false) }
    val hasStarted = message.isNotBlank()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                AnimatedVisibility(visible = !hasStarted, enter = fadeIn(), exit = fadeOut()) {
                    FloatingLogoSlot()
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedVisibility(visible = message.length >= 1200) {
                    SuggestionChip(
                        onClick = { /* Connected to file creation layer later. */ },
                        label = { Text("Turn this long message into a file") },
                        icon = { Icon(Icons.Default.Description, null) }
                    )
                }

                Box {
                    AnimatedVisibility(visible = showAttachments, enter = fadeIn(), exit = fadeOut()) {
                        AttachmentMenu(
                            onDismiss = { showAttachments = false },
                            onSelected = { showAttachments = false }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = { showAttachments = !showAttachments }) {
                            Icon(
                                if (showAttachments) Icons.Default.Close else Icons.Default.Add,
                                if (showAttachments) "Close attachments" else "Add attachment"
                            )
                        }
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                            placeholder = { Text("Message BQ Sqwad AI") },
                            maxLines = 7,
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { /* Voice-to-text layer later. */ }) {
                                        Icon(Icons.Default.Mic, "Voice to text")
                                    }
                                    IconButton(
                                        enabled = message.isNotBlank(),
                                        onClick = { /* AI gateway later. */ }
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, "Send")
                                    }
                                }
                            }
                        )
                    }
                }
                Text(
                    "AI responses can make mistakes. Check important information.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun FloatingLogoSlot() {
    Surface(
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            androidx.compose.foundation.Image(painterResource(com.bqsqwad.ai.R.drawable.bq_sqwad_logo), contentDescription = "BQ Sqwad AI", modifier = Modifier.size(72.dp))
            Text("BQ Sqwad AI", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AttachmentMenu(onDismiss: () -> Unit, onSelected: () -> Unit) {
    Card(
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(8.dp)) {
            AttachmentAction(Icons.Default.InsertDriveFile, "Upload files", onSelected)
            AttachmentAction(Icons.Default.PhotoLibrary, "Photos & gallery", onSelected)
            AttachmentAction(Icons.Default.CameraAlt, "Take a photo", onSelected)
            AttachmentAction(Icons.Default.Person, "Choose a person/contact", onSelected)
            AttachmentAction(Icons.Default.Close, "Close", onDismiss)
        }
    }
}

@Composable
private fun AttachmentAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null)
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}
@Composable private fun SearchSurface() {
    SurfaceCard("Search workspace", "Current-information requests can use the web-search capability when enabled by remote configuration. Sources remain attached to the result.")
    OutlinedTextField("", {}, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search the web") })
}
@Composable private fun CameraSurface() {
    SurfaceCard("Live vision", "Camera access is permission-gated. Screen capture, location and device actions require explicit Android consent.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button({}) { Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Camera") }; OutlinedButton({}) { Text("Privacy controls") } }
}
@Composable private fun VoiceSurface() {
    SurfaceCard("Real-time voice", "Voice conversation is one workspace: microphone, AI speaking, voice selection and interruption are controlled here.")
    Button({}) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Start voice conversation") }
}
@Composable private fun BusinessSurface() {
    SurfaceCard("Business dashboard", "Plan, usage, agents, connections, knowledge, workflows, intelligence and automation are organized below this workspace.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { AssistChip({}, label = { Text("Agents") }); AssistChip({}, label = { Text("Connections") }); AssistChip({}, label = { Text("Knowledge") }) }
}
@Composable
private fun AdminSurface() = AdminControlCenter()

@Composable private fun SimpleSurface(text: String) = SurfaceCard("Workspace", text)

@Composable private fun SurfaceCard(title: String, body: String) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
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
