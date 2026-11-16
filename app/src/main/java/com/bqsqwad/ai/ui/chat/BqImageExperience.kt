package com.bqsqwad.ai.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

private val Blue = Color(0xFF1457FF)

const val GENERATING_IMAGE_SENTINEL = "Generating image\u2026"

@Composable
fun BqGeneratingImagePlaceholder(
    logo: @Composable (size: androidx.compose.ui.unit.Dp) -> Unit,
    modifier: Modifier = Modifier,
    aspect: Float = 1f
) {
    Box(
        modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.25f, 4f))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        // Keep the official brand mark compact and upright; animate only the
        // neutral dots so the logo is never used as a rotating spinner.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            logo(18.dp)
            val transition = rememberInfiniteTransition(label = "generationDots")
            repeat(3) { index ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, delayMillis = index * 150, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "generationDot$index"
                )
                Box(
                    Modifier.size(4.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                )
            }
        }
    }
}

@Composable
fun BqMediaViewer(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String = "Media",
    onDownload: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRecreate: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (!visible) return
    BackHandler(enabled = true) { onDismiss() }
    var chromeVisible by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { chromeVisible = !chromeVisible }
                    },
                    contentAlignment = Alignment.Center
                ) { content() }
                if (chromeVisible) {
                    Row(
                        Modifier.align(Alignment.TopStart).fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.45f)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    Column(
                        Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        onDownload?.let { ViewerRailButton(Icons.Default.Download, "Download", it) }
                        onShare?.let { ViewerRailButton(Icons.Default.Share, "Share", it) }
                        onEdit?.let { ViewerRailButton(Icons.Default.Edit, "Edit", it) }
                        onRecreate?.let { ViewerRailButton(Icons.Default.Refresh, "Recreate", it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerRailButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue.copy(alpha = 0.9f))
        ) { Icon(icon, contentDescription = label, tint = Color.White) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun QuietReplyActions(
    visible: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSpeak: () -> Unit,
    onMore: (() -> Unit)? = null
) {
    if (!visible) return
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Share, "Share", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSpeak, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.VolumeUp, "Speak", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onMore?.let {
            IconButton(onClick = it, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
