package com.bqsqwad.ai.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// Shared message surfaces intentionally remain distinct in light and dark themes.
val bubbleUser = Color(0xFF7EC8F7)
val onBubbleUser = Color(0xFF0A1A2A)
val bubbleAi = Color(0xFF000000)
val onBubbleAi = Color(0xFFFFFFFF)
val bubbleAiBorder = Color(0xFF2A3441)

private val BQSignature = Color(0xFF5BA9FF)
private val BQBlue = Color(0xFF2196F3)
private val BQIndigo = Color(0xFF5C6BC0)
private val BQViolet = Color(0xFF7E57C2)
private val BQTeal = Color(0xFF26A69A)
private val BQEmeraId = Color(0xFF43A047)
private val BQRose = BQBlue
private val BQAmber = Color(0xFFFFA000)
private val BQGraphite = Color(0xFF78909C)

private fun accentForTheme(name: String, dark: Boolean): Color {
    val key = name.lowercase()
    return if (dark) {
        when (key) {
            "blue" -> Color(0xFF64B5F6)
            "indigo" -> Color(0xFF9FA8DA)
            "violet" -> Color(0xFFCE93D8)
            "teal" -> Color(0xFF80CBC4)
            "emerald" -> Color(0xFFA5D6A7)
            "rose" -> BQSignature
            "amber" -> Color(0xFFFFD180)
            "graphite" -> Color(0xFFB0BEC5)
            else -> BQSignature
        }
    } else {
        when (key) {
            "blue", "rose" -> Color(0xFF1565C0)
            "indigo" -> Color(0xFF3949AB)
            "violet" -> Color(0xFF6A1B9A)
            "teal" -> Color(0xFF00695C)
            "emerald" -> Color(0xFF2E7D32)
            "amber" -> Color(0xFF8A4B00)
            "graphite" -> Color(0xFF455A64)
            else -> Color(0xFF1565C0)
        }
    }
}

private data class ThemePalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val onPrimary: Color = Color.White
)

private fun darkPalette(primary: Color, mode: String): ThemePalette {
    val key = mode.lowercase()
    return when (key) {
        "midnight" -> ThemePalette(
            primary, Color(0xFF9EB8FF), Color(0xFF7DD8FF),
            Color(0xFF040711), Color(0xFF080D1B), Color(0xFF10182B), Color(0xFF18233A),
            Color(0xFF202C45), Color(0xFFF3F6FF), Color(0xFFB8C5DC), Color(0xFF52627E),
            Color(0xFF06142C)
        )
        "ocean" -> ThemePalette(
            primary, Color(0xFF9DD7E9), Color(0xFF75E0D2),
            Color(0xFF031216), Color(0xFF071C21), Color(0xFF0D2930), Color(0xFF14363D),
            Color(0xFF1B424A), Color(0xFFE9FAFC), Color(0xFFB4D1D7), Color(0xFF4A6970),
            Color(0xFF042329)
        )
        "aurora" -> ThemePalette(
            primary, Color(0xFFB9A7FF), Color(0xFF83E6C5),
            Color(0xFF090713), Color(0xFF120D1E), Color(0xFF1C1530), Color(0xFF281F40),
            Color(0xFF33294B), Color(0xFFF8F3FF), Color(0xFFC7BDD7), Color(0xFF625674),
            Color(0xFF17102A)
        )
        "sunset" -> ThemePalette(
            primary, Color(0xFFFFC27A), Color(0xFF8DC5FF),
            Color(0xFF0E1016), Color(0xFF151A24), Color(0xFF202A38), Color(0xFF2B3749),
            Color(0xFF303A4B), Color(0xFFFFF7E8), Color(0xFFFFD7A0), Color(0xFF687583),
            Color(0xFF06142C)
        )
        else -> ThemePalette(
            primary, Color(0xFF9BB9D8), Color(0xFF74D7D1),
            if (key == "amoled") Color.Black else Color(0xFF070A10),
            if (key == "amoled") Color.Black else Color(0xFF0D1119),
            if (key == "amoled") Color(0xFF0A0A0A) else Color(0xFF18202D),
            if (key == "amoled") Color(0xFF101010) else Color(0xFF202A38),
            Color(0xFF202734), Color(0xFFE7ECF3), Color(0xFFB9C4D1), Color(0xFF4A5666)
        )
    }
}

private fun lightPalette(primary: Color, mode: String): ThemePalette {
    return when (mode.lowercase()) {
        "ocean" -> ThemePalette(
            primary, Color(0xFF176B7A), Color(0xFF236D68),
            Color(0xFFF3FAFC), Color.White, Color(0xFFE5F3F6), Color(0xFFD8EDF1),
            Color(0xFFDDECEF), Color(0xFF142126), Color(0xFF50666D), Color(0xFF71858B)
        )
        "sunset" -> ThemePalette(
            primary, Color(0xFF7A5200), Color(0xFF285C8F),
            Color(0xFFFFF9F0), Color.White, Color(0xFFF6EBDD), Color(0xFFEFE1CB),
            Color(0xFFEEE4D3), Color(0xFF201A10), Color(0xFF654A28), Color(0xFF83735C)
        )
        else -> ThemePalette(
            primary, Color(0xFF4E6478), Color(0xFF246B68),
            Color(0xFFF7F9FC), Color.White, Color(0xFFEAEFF5), Color(0xFFE2E8EF),
            Color(0xFFE8EDF3), Color(0xFF151A20), Color(0xFF4F5B67), Color(0xFF687583)
        )
    }
}

private fun paletteScheme(p: ThemePalette, dark: Boolean) =
    if (dark) darkColorScheme(
        primary = p.primary, onPrimary = if (p.primary.luminance() > 0.179f) Color(0xFF06142C) else Color.White,
        primaryContainer = p.primary.copy(alpha = .30f), onPrimaryContainer = Color(0xFFEAF3FF),
        secondary = p.secondary, tertiary = p.tertiary,
        background = p.background, onBackground = p.onSurface,
        surface = p.surface, onSurface = p.onSurface,
        surfaceVariant = p.surfaceVariant, onSurfaceVariant = p.onSurfaceVariant,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh,
        surfaceContainerHighest = p.surfaceHighest, outline = p.outline,
        error = Color(0xFFFFC46B), onError = Color(0xFF271900),
        errorContainer = Color(0xFF3D2A0A), onErrorContainer = Color(0xFFFFE2AD)
    ) else lightColorScheme(
        primary = p.primary, onPrimary = p.onPrimary,
        primaryContainer = p.primary.copy(alpha = .14f), onPrimaryContainer = Color(0xFF10243A),
        secondary = p.secondary, tertiary = p.tertiary,
        background = p.background, onBackground = p.onSurface,
        surface = p.surface, onSurface = p.onSurface,
        surfaceVariant = p.surfaceVariant, onSurfaceVariant = p.onSurfaceVariant,
        surfaceContainer = p.surfaceHigh, surfaceContainerHigh = p.surfaceHigh,
        surfaceContainerHighest = p.surfaceHighest, outline = p.outline,
        error = Color(0xFF8A4B00), onError = Color.White,
        errorContainer = Color(0xFFFFF0D2), onErrorContainer = Color(0xFF4B2D00)
    )

@Composable
fun BQSqwadTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("bq_sqwad_ui", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("theme_mode", "system") ?: "system") }
    var accentName by remember { mutableStateOf(prefs.getString("accent_palette", "signature") ?: "signature") }
    var textScale by remember { mutableStateOf(prefs.getFloat("text_scale", 1.0f).coerceIn(0.85f, 1.4f)) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "theme_mode" -> mode = prefs.getString(key, "system") ?: "system"
                "accent_palette" -> accentName = prefs.getString(key, "signature") ?: "signature"
                "text_scale" -> textScale = prefs.getFloat(key, 1.0f).coerceIn(0.85f, 1.4f)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val normalized = mode.lowercase()
    val dark = when (normalized) {
        "dark", "amoled", "midnight", "aurora", "sunset" -> true
        "light" -> false
        "ocean" -> systemDark
        else -> systemDark
    }
    val paletteMode = if (normalized == "system") "system" else normalized
    val schemeMode = when {
        paletteMode == "system" -> if (dark) "dark" else "light"
        else -> paletteMode
    }
    val primary = accentForTheme(accentName, dark)
    val palette = if (dark) darkPalette(primary, schemeMode) else lightPalette(primary, schemeMode)

    val systemDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = systemDensity.density,
            fontScale = systemDensity.fontScale * textScale
        )
    ) {
        MaterialTheme(
            colorScheme = paletteScheme(palette, dark),
            typography = Typography(),
            content = content
        )
    }
}