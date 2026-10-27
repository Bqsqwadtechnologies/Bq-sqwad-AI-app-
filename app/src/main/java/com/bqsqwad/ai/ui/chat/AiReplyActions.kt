package com.bqsqwad.ai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val ActionBlue = Color(0xFF1457FF)

data class ReplyAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val primary: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Professional action strip under each AI reply.
 * Each action has a clear icon logo and optional filled primary style.
 */
@Composable
fun AiReplyActionBar(
    actions: List<ReplyAction>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        actions.forEach { action ->
            if (action.primary) {
                FilledTonalButton(
                    onClick = action.onClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(action.icon, contentDescription = action.label, modifier = Modifier.size(16.dp), tint = ActionBlue)
                    Spacer(Modifier.width(6.dp))
                    Text(action.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            } else {
                IconButton(
                    onClick = action.onClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        action.icon,
                        contentDescription = action.label,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

fun defaultReplyActions(
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSpeak: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
    onImage: (() -> Unit)? = null
): List<ReplyAction> = buildList {
    add(ReplyAction("copy", "Copy", Icons.Default.ContentCopy, onClick = onCopy))
    add(ReplyAction("share", "Share", Icons.Default.Share, primary = true, onClick = onShare))
    add(ReplyAction("speak", "Listen", Icons.Default.VolumeUp, onClick = onSpeak))
    if (onRegenerate != null) add(ReplyAction("retry", "Retry", Icons.Default.Refresh, onClick = onRegenerate))
    if (onImage != null) add(ReplyAction("image", "Image", Icons.Default.Image, onClick = onImage))
}

/**
 * Compact card shown while the AI is working (web search, vision, tool use).
 * Can host a live photo/thumbnail of what the model is looking at.
 */
@Composable
fun AiActivityCard(
    title: String,
    detail: String? = null,
    photo: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ActionBlue.copy(alpha = 0.08f),
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (photo != null) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) { photo() }
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ActionBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ActionBlue, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
