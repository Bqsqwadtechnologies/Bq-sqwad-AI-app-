@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject

private data class LivePriceForm(
    val enabled: Boolean = true,
    val killSwitch: Boolean = false,
    val creditsPerMinute: String = "0",
    val startFeeCredits: String = "0",
    val minimumBalanceToStart: String = "0",
    val minimumBalanceAuto: Boolean = true,
    val maxSessionMinutes: String = "120",
    val modelId: String = "",
    val defaultVoice: String = "marin",
    val transcriptionModel: String = "gpt-4o-mini-transcribe"
)

@Composable
fun AdminLivePricingScreen(onBack: () -> Unit) {
    val api = remember { WorkspaceApiClient() }
    val scope = rememberCoroutineScope()
    var voice by remember { mutableStateOf(LivePriceForm()) }
    var camera by remember { mutableStateOf(LivePriceForm()) }
    var dictation by remember { mutableStateOf(LivePriceForm(maxSessionMinutes = "2")) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var billingMarkup by remember { mutableStateOf("600") }
    var openAiInputRate by remember { mutableStateOf("0.05") }
    var openAiOutputRate by remember { mutableStateOf("0.25") }
    var openAiCachedRate by remember { mutableStateOf("0.005") }
    var featureRatesJson by remember { mutableStateOf("""{"audioInputUsdPerToken":0.000032,"audioOutputUsdPerToken":0.000064,"transcriptionUsdPerMinute":0.003,"liveVoiceConnectionUsdPerMinute":0.05,"liveCameraConnectionUsdPerMinute":0.05,"imageOutputUsdPerToken":0.000015,"imageInputUsdPerToken":0.000004,"imageTextInputUsdPerToken":0.0000025,"tavilyCreditsPerSearch":1.7,"deepResearchCredits":5}""") }
    var billingFeeRate by remember { mutableStateOf("15") }
    var billingCreditValue by remember { mutableStateOf("0.07999") }
    var billingSafetyMargin by remember { mutableStateOf("20") }
    var billingMinimumCredits by remember { mutableStateOf("0.01") }
    var billingMaximumCredits by remember { mutableStateOf("100000") }
    var billingApplyFee by remember { mutableStateOf(false) }
    var billingRatesJson by remember { mutableStateOf("{}") }
    var billingModelRatesJson by remember { mutableStateOf("{}") }
    var billingAnalytics by remember { mutableStateOf(JSONObject()) }
    var billingUpdatedAt by remember { mutableStateOf("Not refreshed yet") }
    var billingSaving by remember { mutableStateOf(false) }
    var billingStatus by remember { mutableStateOf<String?>(null) }
    var billingError by remember { mutableStateOf<String?>(null) }

    fun parseForm(feature: String, form: LivePriceForm): JSONObject {
        val rate = form.creditsPerMinute.toDoubleOrNull() ?: throw IllegalArgumentException("$feature: enter a valid credits-per-minute value.")
        val start = form.startFeeCredits.toDoubleOrNull() ?: throw IllegalArgumentException("$feature: enter a valid start fee.")
        val minimum = form.minimumBalanceToStart.toDoubleOrNull() ?: throw IllegalArgumentException("$feature: enter a valid minimum balance.")
        val maxMinutes = form.maxSessionMinutes.toIntOrNull() ?: throw IllegalArgumentException("$feature: maximum minutes must be a whole number.")
        require(rate in 0.0..10000.0 && start in 0.0..10000.0 && minimum in 0.0..100000.0 && maxMinutes in 1..1440) {
            "$feature: prices must be non-negative and max minutes must be 1–1440."
        }
        return JSONObject()
            .put("creditsPerMinute", rate)
            .put("startFeeCredits", start)
            .put("minimumBalanceToStart", minimum)
            .put("maxSessionMinutes", maxMinutes)
            .put("modelId", form.modelId.trim())
            .put("defaultVoice", form.defaultVoice.trim().ifBlank { "marin" })
            .put("transcriptionModel", form.transcriptionModel.trim().ifBlank { "gpt-4o-mini-transcribe" })
            .put("enabled", form.enabled)
            .put("killSwitch", form.killSwitch)
    }

    fun formFrom(response: JSONObject, old: LivePriceForm): LivePriceForm {
        val config = response.optJSONObject("config") ?: response
        return old.copy(
            enabled = response.optBoolean("enabled", config.optBoolean("enabled", true)),
            killSwitch = response.optBoolean("killSwitch", config.optBoolean("killSwitch", false)),
            creditsPerMinute = config.optDouble("creditsPerMinute", 0.0).toString(),
            startFeeCredits = config.optDouble("startFeeCredits", 0.0).toString(),
            minimumBalanceToStart = config.optDouble("minimumBalanceToStart", config.optDouble("creditsPerMinute", 0.0)).toString(),
            minimumBalanceAuto = kotlin.math.abs(config.optDouble("minimumBalanceToStart", config.optDouble("creditsPerMinute", 0.0)) - config.optDouble("creditsPerMinute", 0.0)) < 0.000001,
            maxSessionMinutes = config.optInt("maxSessionMinutes", old.maxSessionMinutes.toIntOrNull() ?: 120).toString(),
            modelId = config.optString("modelId", "").takeIf { it in setOf("gpt-realtime", "gpt-realtime-1.5", "gpt-realtime-2", "gpt-realtime-2.1", "gpt-realtime-2.1-mini", "gpt-realtime-mini", "gpt-realtime-mini-2025-12-15", "gpt-realtime-2025-08-28") }.orEmpty(),
            defaultVoice = config.optString("defaultVoice", "marin"),
            transcriptionModel = config.optString("transcriptionModel", "gpt-4o-mini-transcribe")
        )
    }

    suspend fun refreshBillingAnalytics() {
        val response = api.adminDynamicBillingAnalytics(500)
        billingAnalytics = response.optJSONObject("analytics") ?: JSONObject()
        billingUpdatedAt = response.optString("lastRefreshedAt", "Refresh time unavailable")
    }

    suspend fun loadBillingConfig() {
        val response = api.adminDynamicBillingConfig()
        val config = response.optJSONObject("config") ?: JSONObject()
        billingMarkup = (config.optDouble("profitMarkup", 6.0) * 100).toString()
        val openAiRates = config.optJSONObject("openAiRates") ?: JSONObject()
        openAiInputRate = openAiRates.optDouble("inputPriceUsdPerMillion", 0.05).toString()
        openAiOutputRate = openAiRates.optDouble("outputPriceUsdPerMillion", 0.25).toString()
        openAiCachedRate = openAiRates.optDouble("cachedInputPriceUsdPerMillion", 0.005).toString()
        featureRatesJson = (config.optJSONObject("featureRates") ?: JSONObject("""{"audioInputUsdPerToken":0.000032,"audioOutputUsdPerToken":0.000064,"transcriptionUsdPerMinute":0.003,"liveVoiceConnectionUsdPerMinute":0.05,"liveCameraConnectionUsdPerMinute":0.05,"imageOutputUsdPerToken":0.000015,"imageInputUsdPerToken":0.000004,"imageTextInputUsdPerToken":0.0000025,"tavilyCreditsPerSearch":1.7,"deepResearchCredits":5}""")).toString(2)
        billingFeeRate = (config.optDouble("feeRate", 0.15) * 100).toString()
        billingCreditValue = config.optDouble("creditValueUsd", 0.07999).toString()
        billingSafetyMargin = (config.optDouble("reservationSafetyMargin", 0.2) * 100).toString()
        billingMinimumCredits = config.optDouble("minimumCredits", 0.01).toString()
        billingMaximumCredits = config.optDouble("maximumCredits", 100000.0).toString()
        billingApplyFee = config.optBoolean("applyFeeToUsage", false)
        billingRatesJson = (config.optJSONObject("capabilityRates") ?: JSONObject()).toString(2)
        billingModelRatesJson = (config.optJSONObject("providerModelRates") ?: JSONObject()).toString(2)
    }

    fun saveBillingConfig() {
        billingSaving = true
        billingStatus = null
        billingError = null
        scope.launch {
            try {
                val markupPercent = billingMarkup.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Enter a valid markup percentage.")
                require(markupPercent in 0.0..1000.0) { "Markup must be between 0% and 1000%." }
                val inputRate = openAiInputRate.toDoubleOrNull() ?: throw IllegalArgumentException("Enter a valid OpenAI input rate.")
                val outputRate = openAiOutputRate.toDoubleOrNull() ?: throw IllegalArgumentException("Enter a valid OpenAI output rate.")
                val cachedRate = openAiCachedRate.toDoubleOrNull() ?: throw IllegalArgumentException("Enter a valid OpenAI cached-input rate.")
                val featureRates = try { JSONObject(featureRatesJson) } catch (_: Throwable) { throw IllegalArgumentException("Feature rates must be valid JSON.") }
                val requiredFeatureRates = listOf("audioInputUsdPerToken", "audioOutputUsdPerToken", "transcriptionUsdPerMinute", "liveVoiceConnectionUsdPerMinute", "liveCameraConnectionUsdPerMinute", "imageOutputUsdPerToken", "imageInputUsdPerToken", "imageTextInputUsdPerToken", "tavilyCreditsPerSearch", "deepResearchCredits")
                require(requiredFeatureRates.all { featureRates.has(it) && featureRates.optDouble(it, -1.0) >= 0.0 }) { "Enter every required feature rate as a non-negative number." }
                require(listOf(inputRate, outputRate, cachedRate).all { it in 0.0..10000.0 }) { "OpenAI rates must be between 0 and 10000 USD per million tokens." }
                val result = api.saveAdminDynamicBillingConfig(JSONObject()
                    .put("profitMarkup", markupPercent / 100.0)
                    .put("openAiRates", JSONObject()
                        .put("inputPriceUsdPerMillion", inputRate)
                        .put("outputPriceUsdPerMillion", outputRate)
                        .put("cachedInputPriceUsdPerMillion", cachedRate))
                    .put("featureRates", featureRates))
                val config = result.optJSONObject("config") ?: JSONObject()
                billingMarkup = (config.optDouble("profitMarkup", markupPercent / 100.0) * 100).toString()
                featureRatesJson = (config.optJSONObject("featureRates") ?: featureRates).toString(2)
                val savedRates = config.optJSONObject("openAiRates") ?: JSONObject()
                openAiInputRate = savedRates.optDouble("inputPriceUsdPerMillion", inputRate).toString()
                openAiOutputRate = savedRates.optDouble("outputPriceUsdPerMillion", outputRate).toString()
                openAiCachedRate = savedRates.optDouble("cachedInputPriceUsdPerMillion", cachedRate).toString()
                billingStatus = "OpenAI rates and markup saved. New usage uses these settings."
                refreshBillingAnalytics()
            } catch (t: Throwable) {
                billingError = t.message ?: "Could not save markup percentage."
            } finally {
                billingSaving = false
            }
        }
    }

    fun rollbackBillingConfig() {
        billingSaving = true
        billingStatus = null
        billingError = null
        scope.launch {
            try {
                api.rollbackAdminDynamicBillingConfig()
                loadBillingConfig()
                refreshBillingAnalytics()
                billingStatus = "Rolled back to the previous valid billing configuration."
            } catch (t: Throwable) {
                billingError = t.message ?: "Could not roll back billing configuration."
            } finally {
                billingSaving = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            voice = formFrom(api.adminLivePricing("voice"), voice)
            camera = formFrom(api.adminLivePricing("camera"), camera)
            dictation = formFrom(api.adminLivePricing("dictation"), dictation)
        } catch (t: Throwable) {
            error = t.message ?: "Could not load live pricing."
        } finally {
            loading = false
        }
        runCatching { loadBillingConfig() }.onFailure {
            billingError = it.message ?: "Billing settings are temporarily unavailable."
        }
        runCatching { refreshBillingAnalytics() }.onFailure {
            billingError = it.message ?: "Analytics are temporarily unavailable."
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            runCatching { refreshBillingAnalytics() }.onFailure {
                billingError = it.message ?: "Analytics refresh failed."
            }
        }
    }

    fun save(feature: String, form: LivePriceForm) {
        saving = feature
        status = null
        error = null
        scope.launch {
            try {
                val config = parseForm(feature, form)
                val result = api.saveAdminLivePricing(feature, form.enabled, form.killSwitch, config)
                when (feature) {
                    "voice" -> voice = formFrom(result, form)
                    "camera" -> camera = formFrom(result, form)
                    else -> dictation = formFrom(result, form)
                }
                status = "$feature pricing saved."
            } catch (t: Throwable) {
                error = t.message ?: "Could not save pricing."
            } finally {
                saving = null
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Live pricing", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Prices apply immediately through the server. Zero credits means free; minute charges use the main BQ credit balance.", color = MaterialTheme.colorScheme.onSurface)
            BillingAnalyticsAndSettings(
                analytics = billingAnalytics,
                updatedAt = billingUpdatedAt,
                markup = billingMarkup,
                onMarkup = { billingMarkup = it },
                inputRate = openAiInputRate,
                onInputRate = { openAiInputRate = it },
                outputRate = openAiOutputRate,
                onOutputRate = { openAiOutputRate = it },
                cachedRate = openAiCachedRate,
                onCachedRate = { openAiCachedRate = it },
                featureRatesJson = featureRatesJson,
                onFeatureRatesJson = { featureRatesJson = it },
                saving = billingSaving,
                status = billingStatus,
                error = billingError,
                onSave = { saveBillingConfig() },
                onRefresh = {
                    scope.launch {
                        runCatching { refreshBillingAnalytics() }
                            .onFailure { billingError = it.message ?: "Analytics refresh failed." }
                    }
                }
            )
            if (loading) Text("Loading pricing…", color = MaterialTheme.colorScheme.primary)
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            PricingCard("Live Voice", voice, onChange = { voice = it }, onSave = { save("voice", voice) }, saving = saving == "voice")
            PricingCard("Live Camera and Screen Share", camera, onChange = { camera = it }, onSave = { save("camera", camera) }, saving = saving == "camera")
            PricingCard("Main Chat Dictation", dictation, onChange = { dictation = it }, onSave = { save("dictation", dictation) }, saving = saving == "dictation", showVoiceSettings = false)
        }
    }
}

@Composable
private fun BillingAnalyticsAndSettings(
    analytics: JSONObject,
    updatedAt: String,
    markup: String,
    onMarkup: (String) -> Unit,
    inputRate: String,
    onInputRate: (String) -> Unit,
    outputRate: String,
    onOutputRate: (String) -> Unit,
    cachedRate: String,
    onCachedRate: (String) -> Unit,
    featureRatesJson: String,
    onFeatureRatesJson: (String) -> Unit,
    saving: Boolean,
    status: String?,
    error: String?,
    onSave: () -> Unit,
    onRefresh: () -> Unit
) {
    val today = analytics.optJSONObject("today") ?: JSONObject()
    val month = analytics.optJSONObject("month") ?: JSONObject()
    val all = analytics.optJSONObject("all") ?: JSONObject()
    fun money(value: Double) = "$" + String.format(java.util.Locale.US, "%.4f", value)
    val metric: @Composable (String, String) -> Unit = { label, value ->
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI Billing, Costs & Revenue Analytics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Provider costs and profit are estimates unless the provider supplies verifiable usage and billing data. Analytics refresh every 30 seconds while this screen is open; totals aggregate the latest 500 transaction records, not an unlimited historical ledger.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metric("Provider cost today", money(today.optDouble("providerCostUsd")))
                    metric("Customer revenue today", money(today.optDouble("revenueUsd")))
                    metric("Estimated profit today", money(today.optDouble("estimatedProfitUsd")))
                    metric("Admin-exempt cost today", money(today.optDouble("adminCostUsd")))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metric("Provider cost this month", money(month.optDouble("providerCostUsd")))
                    metric("Customer revenue this month", money(month.optDouble("revenueUsd")))
                    metric("Estimated profit this month", money(month.optDouble("estimatedProfitUsd")))
                    metric("Subscription/free usage cost", money(month.optDouble("subscriptionCostUsd")))
                }
            }
            Text("All loaded transactions: ${all.optInt("requests")} requests · ${all.optInt("succeeded")} successful · ${all.optInt("failed")} failed")
            Text("Credits charged: ${String.format(java.util.Locale.US, "%.2f", all.optDouble("creditsCharged"))} · refunded: ${String.format(java.util.Locale.US, "%.2f", all.optDouble("creditsRefunded"))}")
            Text("Outstanding reservations: ${all.optInt("outstandingReservations")} · unsettled: ${all.optInt("unsettled")}")
            Text("Provider cost overruns: ${all.optInt("costOverruns")} · above-reservation estimate: ${String.format(java.util.Locale.US, "%.2f", all.optDouble("costOverrunCredits"))} credits")
            Text("Provider cost source — today: ${money(today.optDouble("estimatedProviderCostUsd"))} estimated, ${money(today.optDouble("usageCalculatedCostUsd"))} calculated from returned usage. Confirmed profit is unavailable until actual provider invoices/payment-fee settlement are reconciled.", style = MaterialTheme.typography.bodySmall)
            Text("Last refresh: $updatedAt", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh analytics now") }
            val transactions = analytics.optJSONArray("transactions")
            if (transactions != null && transactions.length() > 0) {
                Text("Recent billing ledger", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                for (i in 0 until minOf(transactions.length(), 10)) {
                    val tx = transactions.optJSONObject(i) ?: continue
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${tx.optString("capability")} · ${tx.optString("status")}", fontWeight = FontWeight.SemiBold)
                            Text("User: ${tx.optString("uid")} · Provider/model: ${tx.optString("providerId", "unknown")}/${tx.optString("modelId", "unknown")}")
                            Text("Provider cost (${tx.optString("costType", "estimated")}): ${money(tx.optDouble("providerCostUsd"))} · charged: ${String.format(java.util.Locale.US, "%.2f", tx.optDouble("creditsCharged"))} credits · refunded: ${String.format(java.util.Locale.US, "%.2f", tx.optDouble("creditsRefunded"))} credits${if (tx.optBoolean("costOverrun")) " · COST OVERRUN" else ""}")
                            Text("ID: ${tx.optString("transactionId")} · pricing v${tx.optInt("pricingVersion", 1)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("Dynamic pricing settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("OpenAI rates are USD per million tokens. The default credit reference remains $0.07999 per credit. Customer prices apply the markup percentage to these configured provider rates.", style = MaterialTheme.typography.bodySmall)
            PriceField("OpenAI input USD per 1M tokens", inputRate, onInputRate)
            PriceField("OpenAI output USD per 1M tokens", outputRate, onOutputRate)
            PriceField("OpenAI cached input USD per 1M tokens", cachedRate, onCachedRate)
            PriceField("Profit markup percentage (600 = 600%)", markup, onMarkup)
            Text("Additional feature rates (JSON). Prices stay in the admin dashboard; live connection rates are provider USD/minute, transcription is USD/minute, image/audio rates are USD/token, and Tavily is credits/search.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = featureRatesJson, onValueChange = onFeatureRatesJson, modifier = Modifier.fillMaxWidth(), label = { Text("Feature rates JSON") }, minLines = 8, maxLines = 16)
            Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "Saving billing settings…" else "Save dynamic billing settings")
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val providers = analytics.optJSONArray("byProvider")
            if (providers != null && providers.length() > 0) {
                Text("Provider cost breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                for (i in 0 until minOf(providers.length(), 5)) {
                    val row = providers.optJSONObject(i) ?: continue
                    Text("${row.optString("providerId")}: ${money(row.optDouble("providerCostUsd"))} · ${row.optInt("requests")} requests")
                }
            }
            val capabilities = analytics.optJSONArray("byCapability")
            if (capabilities != null && capabilities.length() > 0) {
                Text("Highest-cost features", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                for (i in 0 until minOf(capabilities.length(), 5)) {
                    val row = capabilities.optJSONObject(i) ?: continue
                    Text("${row.optString("capability")}: ${money(row.optDouble("providerCostUsd"))} · ${row.optInt("requests")} requests")
                }
            }
        }
    }
}

@Composable
private fun PricingCard(
    title: String,
    form: LivePriceForm,
    onChange: (LivePriceForm) -> Unit,
    onSave: () -> Unit,
    saving: Boolean,
    showVoiceSettings: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        var modelMenuExpanded by remember { mutableStateOf(false) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Enabled", color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = form.enabled, onCheckedChange = { onChange(form.copy(enabled = it)) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Emergency kill switch", color = MaterialTheme.colorScheme.onSurface)
                Checkbox(checked = form.killSwitch, onCheckedChange = { onChange(form.copy(killSwitch = it)) })
            }
            PriceField("Credits per minute", form.creditsPerMinute) {
                val automaticMinimum = if (form.minimumBalanceAuto) it.toDoubleOrNull()?.toString() ?: form.minimumBalanceToStart else form.minimumBalanceToStart
                onChange(form.copy(creditsPerMinute = it, minimumBalanceToStart = automaticMinimum))
            }
            PriceField("Start fee (credits)", form.startFeeCredits) { onChange(form.copy(startFeeCredits = it)) }
            PriceField("Minimum balance to start", form.minimumBalanceToStart) {
                onChange(form.copy(minimumBalanceToStart = it, minimumBalanceAuto = false))
            }
            PriceField("Maximum session minutes", form.maxSessionMinutes) { onChange(form.copy(maxSessionMinutes = it)) }
            if (showVoiceSettings) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Realtime model override (optional)", style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { modelMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(form.modelId.ifBlank { "Default — gpt-realtime" })
                        }
                        DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                            listOf("" to "Default — gpt-realtime", "gpt-realtime" to "gpt-realtime", "gpt-realtime-1.5" to "gpt-realtime-1.5", "gpt-realtime-2" to "gpt-realtime-2", "gpt-realtime-2.1" to "gpt-realtime-2.1", "gpt-realtime-2.1-mini" to "gpt-realtime-2.1-mini", "gpt-realtime-mini" to "gpt-realtime-mini", "gpt-realtime-mini-2025-12-15" to "gpt-realtime-mini-2025-12-15", "gpt-realtime-2025-08-28" to "gpt-realtime-2025-08-28").forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { onChange(form.copy(modelId = id)); modelMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
                PriceField("Default voice", form.defaultVoice) { onChange(form.copy(defaultVoice = it)) }
            } else {
                PriceField("Transcription model", form.transcriptionModel) { onChange(form.copy(transcriptionModel = it)) }
            }
            Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "Saving…" else "Save")
            }
        }
    }
}

@Composable
private fun PriceField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}
