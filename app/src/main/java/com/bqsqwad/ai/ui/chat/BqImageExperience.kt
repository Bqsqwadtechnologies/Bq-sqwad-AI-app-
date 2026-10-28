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
    val transition = rememberInfiniteTransition(label = "genOrbit")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "orbitProgress"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 720f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "orbitSpin"
    )
    BoxWithConstraints(
        modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.25f, 4f))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val logoPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
        val perimeter = 2f * (w + h)
        val d = progress * perimeter
        val (rawX, rawY) = when {
            d < w -> d to 0f
            d < w + h -> w to (d - w)
            d < 2f * w + h -> (w - (d - w - h)) to h
            else -> 0f to (h - (d - 2f * w - h))
        }
        val x = rawX.coerceIn(logoPx / 2f, (w - logoPx / 2f).coerceAtLeast(logoPx / 2f))
        val y = rawY.coerceIn(logoPx / 2f, (h - logoPx / 2f).coerceAtLeast(logoPx / 2f))
        Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset((x - logoPx / 2f).roundToInt(), (y - logoPx / 2f).roundToInt()) }
                .size(22.dp).rotate(spin),
            contentAlignment = Alignment.Center
        ) { logo(22.dp) }
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
