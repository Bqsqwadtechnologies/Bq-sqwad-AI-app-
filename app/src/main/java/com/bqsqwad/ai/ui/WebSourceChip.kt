package com.bqsqwad.ai.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.ApiConfig
import com.bqsqwad.ai.core.SearchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun WebSourceChip(source: SearchSource, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val parsed = remember(source.url) { Uri.parse(source.url) }
    val safeUrl = parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
    val domain = parsed.host.orEmpty().removePrefix("www.").ifBlank { "source" }
    val iconUrl = remember(domain) { "${ApiConfig.baseUrl}/web/favicon?domain=${Uri.encode(domain)}" }
    val favicon by produceState<ImageBitmap?>(initialValue = null, key1 = iconUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(iconUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    instanceFollowRedirects = false
                }
                try {
                    if (connection.responseCode in 200..299) connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                    else null
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }
    var showTitle by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).combinedClickable(
            enabled = safeUrl,
            onClick = { if (safeUrl) onOpen(source.url) },
            onLongClick = { showTitle = true }
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (favicon != null) {
                androidx.compose.foundation.Image(
                    bitmap = favicon!!,
                    contentDescription = "$domain site icon",
                    modifier = Modifier.size(18.dp).clip(CircleShape)
                )
            } else {
                Surface(modifier = Modifier.size(20.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(domain.firstOrNull()?.uppercase() ?: "•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(domain, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }

    if (showTitle) {
        AlertDialog(
            onDismissRequest = { showTitle = false },
            title = { Text(source.title.ifBlank { domain }) },
            text = { Text("Source title") },
            confirmButton = { TextButton(onClick = { showTitle = false }) { Text("Close") } }
        )
    }
}
