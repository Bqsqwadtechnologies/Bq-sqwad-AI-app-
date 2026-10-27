package com.bqsqwad.ai.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Blue = Color(0xFF1457FF)

fun themeChosen(context: Context): Boolean =
    context.getSharedPreferences("bq_device_prefs", Context.MODE_PRIVATE)
        .getBoolean("theme_chosen", false)

fun saveThemeChoice(context: Context, mode: String) {
    context.getSharedPreferences("bq_device_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("theme_mode", mode)
        .putBoolean("theme_chosen", true)
        .apply()
}

@Composable
fun ThemeOncePicker(onChosen: (mode: String) -> Unit) {
    var selected by remember { mutableStateOf("system") }
    val options = listOf(
        Triple("system", "System", Icons.Default.PhoneAndroid),
        Triple("light", "Light", Icons.Default.LightMode),
        Triple("dark", "Dark", Icons.Default.DarkMode)
    )
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Text("Choose your look", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "You can change this later in Settings \u2192 Appearance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            options.forEach { (id, label, icon) ->
                val selectedNow = selected == id
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selectedNow, onClick = { selected = id }, role = Role.RadioButton),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        if (selectedNow) 2.dp else 1.dp,
                        if (selectedNow) Blue else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = if (selectedNow) Blue else MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(12.dp))
                        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        RadioButton(selected = selectedNow, onClick = { selected = id })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onChosen(selected) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Continue", fontWeight = FontWeight.Bold) }
        }
    }
}
