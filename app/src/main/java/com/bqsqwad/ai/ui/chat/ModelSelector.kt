package com.bqsqwad.ai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.ChatApiClient
import kotlinx.coroutines.launch

data class PublicModel(
    val modelKey: String,
    val displayName: String,
    val icon: String,
    val description: String,
    val sortOrder: Int,
    val locked: Boolean,
    val lockReason: String?
)

fun iconForModel(icon: String): ImageVector = when (icon) {
    "code-circuit" -> Icons.Default.Code
    else -> Icons.Default.Star
}

/**
 * Compact chip under the message box. Tapping opens a bottom sheet of model cards.
 * Users never see provider IDs or prices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorChip(
    selectedKey: String,
    selectedName: String,
    models: List<PublicModel>,
    onSelect: (PublicModel) -> Unit,
    onBuyCredits: () -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Surface(
        modifier = modifier.clickable { open = true },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(iconForModel(models.find { it.modelKey == selectedKey }?.icon ?: "star-spark"),
                contentDescription = null, modifier = Modifier.size(16.dp))
            Text(selectedName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Change model", modifier = Modifier.size(16.dp))
        }
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState) {
            Text(
                "Choose a model",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(models.sortedBy { it.sortOrder }) { model ->
                    ModelCard(
                        model = model,
                        selected = model.modelKey == selectedKey,
                        onClick = {
                            if (model.locked && model.lockReason == "purchased_credits_required") {
                                onBuyCredits()
                            } else if (!model.locked) {
                                onSelect(model)
                                open = false
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelCard(
    model: PublicModel,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                iconForModel(model.icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.displayName, fontWeight = FontWeight.SemiBold)
                    if (model.locked) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Lock, contentDescription = "Locked", modifier = Modifier.size(14.dp))
                    }
                }
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (model.locked && model.lockReason == "purchased_credits_required") {
                    Text(
                        "Available with purchased credits",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (selected) {
                Text("Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Fetch public model projection from the shared backend. */
suspend fun loadPublicModels(idToken: String?): List<PublicModel> {
    return runCatching {
        val client = ChatApiClient()
        val json = client.getPublicModels(idToken)
        val arr = json.optJSONArray("models") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    PublicModel(
                        modelKey = o.optString("modelKey"),
                        displayName = o.optString("displayName"),
                        icon = o.optString("icon", "star-spark"),
                        description = o.optString("description"),
                        sortOrder = o.optInt("sortOrder", 99),
                        locked = o.optBoolean("locked", false),
                        lockReason = o.optString("lockReason").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
        }
    }.getOrElse { emptyList() }
}
