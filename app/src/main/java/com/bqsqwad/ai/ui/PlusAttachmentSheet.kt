package com.bqsqwad.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class PlusAction { Camera, Photos, Files, Connectors, CreateImage, CreateFile }

/** "+" replaces paperclip (Prompt G 3.2). Keeps existing attachment handlers via callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusAttachmentButton(
    onAction: (PlusAction) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    IconButton(onClick = { open = true }, enabled = enabled, modifier = modifier) {
        Icon(Icons.Default.Add, contentDescription = "Add attachment or create")
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState) {
            Column(Modifier.padding(bottom = 24.dp)) {
                listOf(
                    Triple(PlusAction.Camera, "Camera", Icons.Default.CameraAlt),
                    Triple(PlusAction.Photos, "Photos", Icons.Default.Image),
                    Triple(PlusAction.Files, "Files", Icons.Default.Folder),
                    Triple(PlusAction.Connectors, "Connectors", Icons.Default.Link),
                    Triple(PlusAction.CreateImage, "Create image", Icons.Default.AutoAwesome),
                    Triple(PlusAction.CreateFile, "Create file", Icons.Default.Description)
                ).forEach { (action, label, icon) ->
                    PlusRow(icon, label) {
                        onAction(action)
                        open = false
                    }
                }
            }
        }
    }
}

@Composable
private fun PlusRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}
