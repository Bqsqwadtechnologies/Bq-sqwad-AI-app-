package com.bqsqwad.ai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class PlusAction { Camera, Photos, Files, Connectors, CreateImage, CreateFile }

private data class PlusItem(val action: PlusAction, val label: String, val icon: ImageVector)

private val ITEMS = listOf(
    PlusItem(PlusAction.Camera, "Camera", Icons.Default.PhotoCamera),
    PlusItem(PlusAction.Photos, "Photos", Icons.Default.Image),
    PlusItem(PlusAction.Files, "Files", Icons.Default.InsertDriveFile),
    PlusItem(PlusAction.Connectors, "Connectors", Icons.Default.Cable),
    PlusItem(PlusAction.CreateImage, "Create image", Icons.Default.AutoAwesome),
    PlusItem(PlusAction.CreateFile, "Create file", Icons.Default.NoteAdd),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusAttachmentSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    onAction: (PlusAction) -> Unit,
) {
    if (!open) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Add",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Column(Modifier.padding(bottom = 32.dp)) {
            ITEMS.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onAction(item.action)
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(item.icon, contentDescription = null)
                    Text(item.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
