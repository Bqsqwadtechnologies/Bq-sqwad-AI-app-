package com.bqsqwad.ai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context

/**
 * One-time team/workspace chooser after sign-in.
 * User can change the selection later in Settings.
 */
data class TeamOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color
)

val DefaultTeamOptions = listOf(
    TeamOption("personal", "Personal", "Just you — planning, chat and personal tools", Icons.Default.Person, Color(0xFF1457FF)),
    TeamOption("family", "Family", "Share plans and memories with family members", Icons.Default.Home, Color(0xFF0EA5E9)),
    TeamOption("work", "Work / Business", "Business AI, records and team collaboration", Icons.Default.Business, Color(0xFF6366F1)),
    TeamOption("organization", "Organization", "Organization Studio, production and shared tools", Icons.Default.Apartment, Color(0xFF8B5CF6))
)

fun teamPreference(context: Context) =
    context.getSharedPreferences("bq_team_prefs", Context.MODE_PRIVATE)

fun savedTeamId(context: Context): String? =
    teamPreference(context).getString("selected_team_id", null)

fun saveTeamId(context: Context, teamId: String) {
    teamPreference(context).edit().putString("selected_team_id", teamId).apply()
}

fun needsTeamChooser(context: Context): Boolean =
    savedTeamId(context).isNullOrBlank()

@Composable
fun TeamChooserScreen(
    onTeamChosen: (TeamOption) -> Unit,
    title: String = "Choose your team",
    subtitle: String = "Pick how you will use BQ Sqwad AI. You can change this later in Settings."
) {
    var selected by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(DefaultTeamOptions) { team ->
                val isSelected = selected == team.id
                OutlinedCard(
                    onClick = { selected = team.id },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) team.accent else MaterialTheme.colorScheme.outlineVariant
                    ),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (isSelected) team.accent.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = team.accent.copy(alpha = 0.15f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(team.icon, contentDescription = null, tint = team.accent, modifier = Modifier.size(26.dp))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(team.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text(team.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = team.accent)
                        }
                    }
                }
            }
        }
        Button(
            onClick = {
                val team = DefaultTeamOptions.firstOrNull { it.id == selected } ?: return@Button
                onTeamChosen(team)
            },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1457FF))
        ) {
            Text("Continue", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TeamSettingsRow(
    currentTeamId: String?,
    onChangeTeam: () -> Unit
) {
    val team = DefaultTeamOptions.firstOrNull { it.id == currentTeamId }
    ListItem(
        headlineContent = { Text("Your team") },
        supportingContent = {
            Text(team?.title ?: "Not chosen yet — tap to choose")
        },
        leadingContent = {
            Icon(team?.icon ?: Icons.Default.Groups, contentDescription = null, tint = Color(0xFF1457FF))
        },
        trailingContent = {
            TextButton(onClick = onChangeTeam) { Text("Change") }
        }
    )
}
