package com.bqsqwad.ai.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun BqChatHeader(
    title: String?,
    generating: Boolean,
    typing: Boolean,
    drawerOpen: Boolean,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    logo: @Composable (size: androidx.compose.ui.unit.Dp) -> Unit
) {
    var showTitle by remember { mutableStateOf(false) }
    LaunchedEffect(title, generating, typing) {
        if (title.isNullOrBlank() || generating || typing) {
            showTitle = false
            return@LaunchedEffect
        }
        while (true) {
            delay(4000)
            showTitle = true
            delay(4000)
            showTitle = false
        }
    }
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BqDashMenuIcon(open = drawerOpen, onClick = onMenu)
        Spacer(Modifier.width(6.dp))
        logo(24.dp)
        Spacer(Modifier.width(8.dp))
        AnimatedContent(
            targetState = showTitle && !title.isNullOrBlank(),
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
            },
            label = "headerTitle"
        ) { showing ->
            Text(
                if (showing) title.orEmpty() else "BQ Sqwad AI",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onNewChat, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Add, contentDescription = "New chat", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun BqDashMenuIcon(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (open) {
        IconButton(onClick = onClick, modifier = modifier.size(40.dp)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Close menu")
        }
        return
    }
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        modifier.size(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp, 16.dp)) {
            val stroke = 2.2.dp.toPx()
            val w = size.width
            val h = size.height
            drawLine(color, Offset(0f, h * 0.15f), Offset(w, h * 0.15f), stroke, StrokeCap.Round)
            drawLine(color, Offset(0f, h * 0.5f), Offset(w * 0.72f, h * 0.5f), stroke, StrokeCap.Round)
            drawLine(color, Offset(0f, h * 0.85f), Offset(w * 0.48f, h * 0.85f), stroke, StrokeCap.Round)
        }
    }
}
