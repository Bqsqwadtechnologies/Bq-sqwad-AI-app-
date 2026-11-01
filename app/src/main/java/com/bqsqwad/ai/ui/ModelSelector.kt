package com.bqsqwad.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.ChatApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PublicModelUi(
    val modelKey: String,
    val displayName: String,
    val icon: String,
    val description: String,
    val sortOrder: Int,
    val locked: Boolean,
    val lockReason: String?
)

/** Compact model chip under the composer (Prompt G). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelector(
    selectedKey: String,
    onSelect: (PublicModelUi) -> Unit,
    onBuyCredits: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var models by remember { mutableStateOf<List<PublicModelUi>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        scope.launch {
            models = runCatching {
                val body = ChatApiClient().getPublicModels()
                val arr = body.optJSONArray("models")
                buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            add(
                                PublicModelUi(
                                    modelKey = o.optString("modelKey"),
                                    displayName = o.optString("displayName"),
                                    icon = o.optString("icon"),
                                    description = o.optString("description"),
                                    sortOrder = o.optInt("sortOrder", 99),
                                    locked = o.optBoolean("locked"),
                                    lockReason = o.optString("lockReason").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }.sortedBy { it.sortOrder }
            }.getOrElse {
                listOf(
                    PublicModelUi("bq-7-star", "BQ-7-Star", "star-spark", "Fast everyday intelligence", 1, false, null),
                    PublicModelUi("bq-codron", "BQ-Codron", "code-circuit", "Coding advice · Intelligence · Ultra fast", 2, true, "purchased_credits_required")
                )
            }
        }
    }

    val selected = models.firstOrNull { it.modelKey == selectedKey } ?: models.firstOrNull()
        ?: PublicModelUi("bq-7-star", "BQ-7-Star", "star-spark", "Fast everyday intelligence", 1, false, null)

    Surface(
        modifier = modifier.clickable { open = true },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (selected.icon == "code-circuit") Icons.Default.Code else Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(selected.displayName, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState) {
            Text(
                "Choose a model",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            models.forEach { model ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (model.locked && model.lockReason == "purchased_credits_required") {
                                onBuyCredits?.invoke()
                                open = false
                            } else if (!model.locked) {
                                onSelect(model)
                                open = false
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        if (model.icon == "code-circuit") Icons.Default.Code else Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(Modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                            if (model.locked) Icon(Icons.Default.Lock, contentDescription = "Locked", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (model.locked && model.lockReason == "purchased_credits_required") {
                            Text("Available with purchased credits", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            TextButton(onClick = { open = false }, modifier = Modifier.padding(16.dp)) { Text("Close") }
        }
    }
}
