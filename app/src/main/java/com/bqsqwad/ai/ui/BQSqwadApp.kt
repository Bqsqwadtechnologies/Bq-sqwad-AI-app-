package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.compose.*
import com.bqsqwad.ai.domain.Workspace

@Composable
fun BQSqwadApp(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home", modifier = modifier) {
        composable("home") { HomeScreen(onOpen = { nav.navigate("workspace/" + it.name) }) }
        composable("workspace/{name}") { backStack ->
            val name = backStack.arguments?.getString("name")
            val workspace = runCatching { Workspace.valueOf(name ?: "CHAT") }.getOrDefault(Workspace.CHAT)
            WorkspaceScreen(workspace = workspace, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun HomeScreen(onOpen: (Workspace) -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(
            title = { Column { Text("BQ Sqwad AI", fontWeight = FontWeight.Bold); Text("The AI That Grows With You", style = MaterialTheme.typography.labelSmall) } },
            actions = { IconButton(onClick = { onOpen(Workspace.ADMIN) }) { Icon(Icons.Default.Settings, "Settings") } }
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 18.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("What do you want to accomplish?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            MainChatCard { onOpen(Workspace.CHAT) }
            Text("Workspaces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(Workspace.entries.filter { it != Workspace.CHAT && it != Workspace.ADMIN }) { workspace -> WorkspaceCard(workspace) { onOpen(workspace) } }
            }
        }
    }
}

@Composable
private fun MainChatCard(onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("Main Chat", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
            Text("Ask, create, search when needed, speak your request, or generate images.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Message BQ Sqwad AI") }, trailingIcon = { Icon(Icons.Default.Mic, null) }, shape = RoundedCornerShape(16.dp))
        }
    }
}

@Composable
private fun WorkspaceCard(workspace: Workspace, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(iconFor(workspace), null, tint = MaterialTheme.colorScheme.primary)
            Text(workspace.title, fontWeight = FontWeight.Bold)
            Text(workspace.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkspaceScreen(workspace: Workspace, onBack: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text(workspace.title, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(iconFor(workspace), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            Text(workspace.description, style = MaterialTheme.typography.titleMedium)
            Text("This workspace is isolated from the other AI surfaces. Feature availability, providers, pricing and limits are controlled by the remote configuration layer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (workspace == Workspace.ADMIN) AdminPreview() else Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Workspace ready", fontWeight = FontWeight.Bold); Text("The native surface is established for this feature. Backend capability wiring is added separately so secrets never ship in the APK.") }
            }
        }
    }
}

@Composable
private fun AdminPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("Feature flags" to "Remote availability and entitlement gates", "AI providers" to "Activate/deactivate providers and routing", "Pricing & credits" to "Server-controlled catalogue and limits", "Testing mode" to "Admin-only controlled test environment", "Audit" to "Record sensitive configuration changes").forEach { (title, detail) ->
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

private fun iconFor(workspace: Workspace) = when (workspace) {
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
