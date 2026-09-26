package com.bqsqwad.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.AdminAdvancedClient
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class LifeMathisGatePlan(
    val id: String,
    val name: String,
    val price: Map<String, String>,
    val basePlans: List<String>,
    val productId: String?,
    val playEligible: Boolean,
    val trialDays: Int,
    val trialEligible: Boolean,
    val features: List<String>
)

@Composable
fun LifeMathisPlanGate(
    onOpenPlans: () -> Unit,
    onStatus: (String) -> Unit = {},
    onAccessGranted: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf(emptyList<LifeMathisGatePlan>()) }
    var activePlanId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching {
                val api = WorkspaceApiClient()
                val catalog = api.nativeCatalog()
                val planArray = catalog.optJSONArray("plans") ?: JSONArray()
                val parsed = mutableListOf<LifeMathisGatePlan>()
                for (i in 0 until planArray.length()) {
                    val p = planArray.optJSONObject(i) ?: continue
                    if (p.optString("module") != "life-mathis") continue
                    val prices = p.optJSONObject("price") ?: JSONObject()
                    val price = linkedMapOf<String, String>()
                    prices.keys().forEach { key -> price[key] = prices.optString(key) }
                    val baseArray = p.optJSONArray("basePlans") ?: JSONArray()
                    val bases = List(baseArray.length()) { n -> baseArray.optString(n) }.filter { it.isNotBlank() }
                    val featuresArray = p.optJSONArray("features") ?: JSONArray()
                    val features = List(featuresArray.length()) { n -> featuresArray.optString(n) }.filter { it.isNotBlank() }
                    parsed += LifeMathisGatePlan(
                        id = p.optString("id"),
                        name = p.optString("name"),
                        price = price,
                        basePlans = bases,
                        productId = p.optString("productId").ifBlank { null },
                        playEligible = p.optBoolean("playEligible", false),
                        trialDays = p.optInt("trialDays", 0),
                        trialEligible = p.optBoolean("trialEligible", false),
                        features = features
                    )
                }
                val entitlementItems = api.entitlements().optJSONArray("items") ?: JSONArray()
                val activeStates = setOf("active", "grace", "on_hold", "paused")
                activePlanId = (0 until entitlementItems.length())
                    .mapNotNull { entitlementItems.optJSONObject(it) }
                    .firstOrNull { activeStates.contains(it.optString("state")) && it.optString("planId") in setOf("lifemathisfree", "lmstandard", "lmultimate") }
                    ?.optString("planId")
                    ?.takeIf { it.isNotBlank() }

                val serverAdmin = runCatching { api.adminAccess().optBoolean("allowed", false) }.getOrDefault(false)
                val serverTestMode = if (serverAdmin) {
                    runCatching { AdminAdvancedClient().testMode() }.getOrDefault(false)
                } else false

                val freeAvailable = parsed.any { it.id == "lifemathisfree" }
                onAccessGranted(serverTestMode || activePlanId != null || freeAvailable)
                plans = parsed.sortedBy { listOf("lifemathisfree", "lmstandard", "lmultimate").indexOf(it.id).let { n -> if (n < 0) 99 else n } }
            }.onFailure { onStatus(it.message ?: "Life Mathis plans could not be loaded.") }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 4.dp
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Life Mathis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Plans and prices are loaded from the BQ Sqwad AI catalog. Monthly and yearly subscriptions use the configured subscription base plans. Access is unlocked only after server verification.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (plans.isEmpty()) {
                Text("Life Mathis plans are temporarily unavailable.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = ::refresh, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
            } else {
                plans.forEach { plan ->
                    LifeMathisGateCard(
                        plan = plan,
                        active = activePlanId == plan.id,
                        billing = billing,
                        onOpenPlans = onOpenPlans,
                        onStatus = onStatus
                    )
                }
            }
        }
    }
}

@Composable
private fun LifeMathisGateCard(
    plan: LifeMathisGatePlan,
    active: Boolean,
    billing: PlayBillingClient?,
    onOpenPlans: () -> Unit,
    onStatus: (String) -> Unit
) {
    val periods = listOf("monthly", "yearly").filter { plan.price.containsKey(it) }
    var selectedPeriod by remember(plan.id) {
        mutableStateOf(periods.firstOrNull { it == "monthly" } ?: periods.firstOrNull() ?: "")
    }
    val isFree = plan.id == "lifemathisfree"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Plan ID: " + plan.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (plan.basePlans.isNotEmpty()) {
                Text("Base plans: " + plan.basePlans.joinToString(" • "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
                    if (active) Text("Current verified access", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                if (isFree) BqStatusBadge("FREE")
            }

            if (periods.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(periods) { period ->
                        FilterChip(
                            selected = selectedPeriod == period,
                            onClick = { selectedPeriod = period },
                            label = { Text(period.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            val value = plan.price[selectedPeriod] ?: plan.price["free"] ?: "0"
            val numeric = value.toDoubleOrNull()
            Text(
                if (numeric != null) {
                    String.format(
                        java.util.Locale.US,
                        "$%.2f / %s",
                        numeric,
                        if (selectedPeriod == "yearly") "year" else "month"
                    )
                } else value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            if (plan.trialEligible && plan.trialDays > 0) {
                Text(plan.trialDays.toString() + "-day free trial", fontWeight = FontWeight.SemiBold)
            }

            plan.features.take(7).forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
            if (plan.features.size > 7) {
                Text("+" + (plan.features.size - 7) + " more features", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (isFree) {
                OutlinedButton(onClick = onOpenPlans, modifier = Modifier.fillMaxWidth()) { Text("Use free plan") }
            } else {
                Button(onClick = onOpenPlans, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedPeriod == "yearly") "Choose yearly plan" else "Choose monthly plan")
                }
            }
        }
    }
}
