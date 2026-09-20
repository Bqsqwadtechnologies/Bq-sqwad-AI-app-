package com.bqsqwad.ai.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

private val BQSignature = Color(0xFF5BA9FF)
private val BQBlue = Color(0xFF2196F3)
private val BQIndigo = Color(0xFF5C6BC0)
private val BQViolet = Color(0xFF7E57C2)
private val BQTeal = Color(0xFF26A69A)
private val BQEmeraId = Color(0xFF43A047)
private val BQRose = Color(0xFFD81B60)
private val BQAmber = Color(0xFFFFA000)
private val BQGraphite = Color(0xFF78909C)

private fun accent(name: String): Color = when (name.lowercase()) {
    "blue" -> BQBlue
    "indigo" -> BQIndigo
    "violet" -> BQViolet
    "teal" -> BQTeal
    "emerald" -> BQEmeraId
    "rose" -> BQRose
    "amber" -> BQAmber
    "graphite" -> BQGraphite
    else -> BQSignature
}

private fun darkScheme(primary: Color, amoled: Boolean) = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF001B33),
    primaryContainer = primary.copy(alpha = .34f),
    onPrimaryContainer = Color(0xFFE7F2FF),
    secondary = Color(0xFF9BB9D8),
    tertiary = Color(0xFF74D7D1),
    background = if (amoled) Color.Black else Color(0xFF070A10),
    onBackground = Color(0xFFF3F6FA),
    surface = if (amoled) Color.Black else Color(0xFF0D1119),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF202734),
    onSurfaceVariant = Color(0xFFB9C4D1),
    surfaceContainer = if (amoled) Color(0xFF050505) else Color(0xFF121722),
    surfaceContainerHigh = if (amoled) Color(0xFF0A0A0A) else Color(0xFF18202D),
    surfaceContainerHighest = if (amoled) Color(0xFF101010) else Color(0xFF202A38),
    outline = Color(0xFF4A5666)
)

private fun lightScheme(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = .16f),
    onPrimaryContainer = Color(0xFF10243A),
    secondary = Color(0xFF4E6478),
    tertiary = Color(0xFF246B68),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF151A20),
    surface = Color.White,
    onSurface = Color(0xFF151A20),
    surfaceVariant = Color(0xFFE8EDF3),
    onSurfaceVariant = Color(0xFF4F5B67),
    surfaceContainer = Color(0xFFF1F4F8),
    surfaceContainerHigh = Color(0xFFEAEFF5),
    surfaceContainerHighest = Color(0xFFE2E8EF),
    outline = Color(0xFF687583)
)

@Composable
fun BQSqwadTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    val mode = prefs.getString("theme_mode", "system") ?: "system"
    val accentName = prefs.getString("accent_palette", "signature") ?: "signature"
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode.lowercase()) {
        "dark", "amoled" -> true
        "light" -> false
        else -> systemDark
    }
    val scheme = if (dark) darkScheme(accent(accentName), mode.equals("amoled", true))
    else lightScheme(accent(accentName))

    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content
    )
}
