package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.bqsqwad.ai.R

/**
 * One app-wide logo source. Choosing a replacement in Settings updates every
 * BqLogo instance immediately and persists the URI on this device.
 */
object BqLogoBranding {
    var customLogoUri by mutableStateOf<String?>(null)
        private set

    fun load(context: Context) {
        if (customLogoUri == null) {
            customLogoUri = context.getSharedPreferences("bq_branding", Context.MODE_PRIVATE)
                .getString("logo_uri", null)
        }
    }

    fun set(context: Context, uri: Uri) {
        // Validate the selected image before saving its URI. A picker URI that
        // cannot be reopened after the picker closes must not be reported as saved.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val readable = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        } ?: false
        require(readable) { "Choose a valid image file." }

        // Persistent read access is required because the selected logo is reused
        // after the app is restarted. Let permission errors reach the UI.
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        customLogoUri = uri.toString()
        context.getSharedPreferences("bq_branding", Context.MODE_PRIVATE)
            .edit().putString("logo_uri", customLogoUri).apply()
    }

    fun reset(context: Context) {
        customLogoUri = null
        context.getSharedPreferences("bq_branding", Context.MODE_PRIVATE)
            .edit().remove("logo_uri").apply()
    }
}

@Composable
fun BqLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { BqLogoBranding.load(context) }
    val uri = BqLogoBranding.customLogoUri
    val targetPixels = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, targetPixels) {
        value = if (uri.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val parsedUri = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(parsedUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    null
                } else {
                    // App logos render at small sizes; decode near the target size
                    // instead of allocating a full-resolution photo for every logo.
                    var sampleSize = 1
                    while (
                        bounds.outWidth / sampleSize > targetPixels * 2 ||
                        bounds.outHeight / sampleSize > targetPixels * 2
                    ) {
                        sampleSize *= 2
                    }
                    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    context.contentResolver.openInputStream(parsedUri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                }
            }.getOrNull()
        }
    }
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        // One identical logo treatment throughout the app: no glow, fade, or
        // feature-specific shadow effect.
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "BQ Sqwad AI logo",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(R.drawable.bq_sqwad_logo),
                contentDescription = "BQ Sqwad AI logo",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun BqTopBar(
    title: String,
    onMenu: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    actionDescription: String = "New chat"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onMenu != null) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.Menu, contentDescription = "Open BQ Sqwad AI menu")
            }
        }
        BqLogo(42.dp)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        if (onAction != null) {
            IconButton(onClick = onAction) {
                Icon(Icons.Default.AutoAwesome, contentDescription = actionDescription)
            }
        }
    }
}

@Composable
fun BqPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(50),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) { Text(text) }
}

@Composable
fun BqSecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(50)
    ) { Text(text) }
}

@Composable
fun BqCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) { content() }
}

@Composable
fun BqStatusBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}


@Composable
fun BqSectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun BqLoadingState(message: String = "Loading…", modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BqEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BqErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        onRetry?.let { BqSecondaryButton("Retry", it) }
    }
}

@Composable
fun BqNavigationCard(title: String, body: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open $title")
        }
    }
}


@Composable
fun SurfaceCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

