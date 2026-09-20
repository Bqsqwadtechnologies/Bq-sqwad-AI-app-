package com.bqsqwad.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BQDark = darkColorScheme(
    primary = Color(0xFF5BA9FF),
    onPrimary = Color(0xFF001B33),
    primaryContainer = Color(0xFF0B3D69),
    onPrimaryContainer = Color(0xFFD7ECFF),
    secondary = Color(0xFF9BB9D8),
    tertiary = Color(0xFF74D7D1),
    background = Color(0xFF070A10),
    onBackground = Color(0xFFF3F6FA),
    surface = Color(0xFF0D1119),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF202734),
    onSurfaceVariant = Color(0xFFB9C4D1),
    surfaceContainer = Color(0xFF121722),
    surfaceContainerHigh = Color(0xFF18202D),
    surfaceContainerHighest = Color(0xFF202A38),
    outline = Color(0xFF4A5666)
)

@Composable
fun BQSqwadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BQDark,
        typography = Typography(),
        content = content
    )
}
