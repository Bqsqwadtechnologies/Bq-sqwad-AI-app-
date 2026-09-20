package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.bqsqwad.ai.domain.Workspace

@Composable
fun BQSqwadApp(modifier: Modifier = Modifier) = AuthGate(modifier)

@Composable
private fun WorkspaceScreen(w: Workspace, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (w == Workspace.BUSINESS) "Business AI" else w.title, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { p ->
        Column(Modifier.padding(p).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(iconFor(w), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            when (w) {
                Workspace.CHAT -> Text("Main Chat")
                Workspace.SEARCH -> SurfaceCard("Web Search", "Server-side web search uses the configured search provider.")
                Workspace.CAMERA -> SurfaceCard("AI Live Camera", "Camera access and device actions are permission-gated.")
                Workspace.VOICE -> SurfaceCard("Live Voice AI", "Real-time voice, interruption and voice controls live here.")
                Workspace.PERSONAL -> SurfaceCard("Personal Intelligence", "Planning, organization and personal assistance.")
                Workspace.LIFE -> SurfaceCard("Life Matters", "Dedicated Life Matters workspace with its own controls.")
                Workspace.LEARNING -> SurfaceCard("Learning Studio", "Subjects, lessons and custom learning tools.")
                Workspace.BUSINESS -> SurfaceCard("Business AI", "Agents, knowledge, connections, workflows and intelligence.")
                Workspace.ADMIN -> AdminControlCenter()
            }
        }
    }
}

@Composable
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

@Composable
fun SurfaceCard(title: String, body: String) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
