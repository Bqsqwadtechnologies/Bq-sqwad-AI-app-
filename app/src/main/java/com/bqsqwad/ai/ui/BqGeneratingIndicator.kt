package com.bqsqwad.ai.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp

/**
 * Image / long-task generation state: rotating BQ logo only.
 * No percentage, progress bar, or "generating" copy.
 */
@Composable
fun BqGeneratingIndicator(
    label: String = "",
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "bqGenerating")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bqGeneratingAngle"
    )
    Surface(
        modifier = modifier.fillMaxWidth().height(220.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.rotate(angle).shadow(8.dp, CircleShape).size(40.dp)) {
                BqLogo(40.dp)
            }
        }
    }
}
