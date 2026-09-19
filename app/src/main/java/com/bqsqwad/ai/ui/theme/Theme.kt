package com.bqsqwad.ai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    background = Color(0xFF080A10),
    surface = Color(0xFF10131C),
    surfaceContainer = Color(0xFF151925),
    primary = Color(0xFF4EA1FF),
    secondary = Color(0xFF8DA8C7),
    onBackground = Color(0xFFF4F7FB),
    onSurface = Color(0xFFE7ECF3)
)

@Composable
fun BQSqwadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = Typography(), content = content)
}
