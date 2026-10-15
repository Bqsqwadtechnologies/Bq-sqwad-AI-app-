package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AdminModelManagementScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember { WorkspaceApiClient() }
    var models by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var checkingModelId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("openai") }
    var providerModelId by remember { mutableStateOf("") }
    var feature by remember { mutableStateOf("text-chat") }
    var description by remember { mutableStateOf("") }
    var creditCost by remember { mutableStateOf("1") }
    var priceUsd by remember { mutableStateOf("0.01") }
    var billingMode by remember { mutableStateOf("fixed_credit") }
    var inputRate by remember { mutableStateOf("0") }
    var outputRate by remember { mutableStateOf("0") }
    var cachedInputRate by remember { mutableStateOf("") }
    var creditValueUsd by remember { mutableStateOf("0.07999") }
    var costMultiplier by remember { mutableStateOf("8") }
    var estimatedFeePercent by remember { mutableStateOf("15") }
    var maxOutputTokens by remember { mutableStateOf("2048") }
    var usageReport by remember { mutableStateOf(JSONObject()) }
    var creditsPerMinute by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(true) }
    var isDefault by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf(true) }
    var confirmDisable by remember { mutableStateOf<JSONObject?>(null) }

    fun load() {
        scope.launch {
            loading = true
            runCatching { api.adminAiModels().optJSONArray("models") ?: JSONArray() }
                .onSuccess { arr -> models = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
                .onFailure { message = it.message ?: "Could not load model registry." }
            runCatching { api.adminAiUsage(100) }.onSuccess { usageReport = it }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI Model Management", style = MaterialTheme.typography.headlineSmall)
        Text("Changes are stored on the server. Active models become the server default for their feature on subsequent requests. API keys stay in Cloudflare secrets; never enter keys here.", style = MaterialTheme.typography.bodyMedium)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        val totals = usageReport.optJSONObject("totals") ?: JSONObject()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Token usage and cost report", style = MaterialTheme.typography.titleMedium)
                Text("Requests: ${totals.optInt("requests")} · Input tokens: ${totals.optLong("inputTokens")} · Output tokens: ${totals.optLong("outputTokens")}")
                Text("Provider cost: USD ${"%.6f".format(totals.optDouble("providerCostUsd"))} · Credits charged: ${"%.6f".format(totals.optDouble("chargedCredits"))}")
                Text("Gross usage value: USD ${"%.6f".format(totals.optDouble("grossUsageValueUsd"))} · Estimated fee: USD ${"%.6f".format(totals.optDouble("estimatedFeeUsd"))}")
                Text("Estimated net contribution: USD ${"%.6f".format(totals.optDouble("estimatedNetProfitUsd"))} (before other overheads)")
                Text("Report covers the latest returned records. Costs are calculated from provider usage and configured rates.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (loading) Text("Loading registered models…")
        models.forEach { model ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(model.optString("name"), style = MaterialTheme.typography.titleMedium)
                    Text("${model.optString("providerId")} / ${model.optString("modelId")}", style = MaterialTheme.typography.bodySmall)
                    Text("Feature: ${model.optString("feature")} · Billing: ${model.optString("billingMode", "fixed_credit")} · Legacy rate: ${model.optInt("creditCost")} credits/request · Reference USD ${"%.4f".format(model.optDouble("priceUsd"))}")
                    if (model.optString("billingMode") == "token") {
                        Text("Provider per 1M tokens: input USD ${model.optDouble("inputPriceUsdPerMillion")} · output USD ${model.optDouble("outputPriceUsdPerMillion")} · cached input USD ${if (model.isNull("cachedInputPriceUsdPerMillion")) "same as input" else model.optDouble("cachedInputPriceUsdPerMillion")}")
                        Text("Multiplier ${model.optDouble("customerMultiplier", 8.0)}× · fee estimate ${model.optDouble("estimatedFeePercent", 15.0)}% · credit reference USD ${model.optDouble("creditReferenceValueUsd", 0.07999)} · output limit ${model.optInt("maxOutputTokens", 2048)} tokens")
                    }
                    if (!model.isNull("creditsPerMinute")) Text("Live-session rate: ${model.optInt("creditsPerMinute")} credits/minute")
                    Text("Status: ${if (model.optBoolean("active")) "enabled" else "disabled"} · ${if (model.optBoolean("isDefault")) "DEFAULT" else "not default"} · ${if (model.optBoolean("published")) "listed" else "hidden from user catalogue"}")
                    val providerStatus = model.optString("providerStatus", "not_checked")
                    val providerStatusLabel = when (providerStatus) {
                        "available" -> "LIVE PROVIDER STATUS: AVAILABLE"
                        "unavailable" -> "LIVE PROVIDER STATUS: UNAVAILABLE / REJECTED"
                        "provider_not_configured" -> "LIVE PROVIDER STATUS: PROVIDER KEY NOT CONFIGURED"
                        "not_verified" -> "LIVE PROVIDER STATUS: NOT VERIFIED (NETWORK/TIMEOUT)"
                        else -> "LIVE PROVIDER STATUS: NOT CHECKED"
                    }
                    Text(providerStatusLabel, style = MaterialTheme.typography.bodySmall,
                        color = if (providerStatus == "available") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    model.optString("providerStatusMessage").takeIf { it.isNotBlank() }?.let {
                        Text(it.take(180), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (model.optJSONObject("validation")?.optBoolean("ok") == true) "Provider model validation: passed" else "Provider model validation: not recorded", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = checkingModelId.isBlank(),
                            onClick = {
                                val modelId = model.optString("id")
                                checkingModelId = modelId
                                scope.launch {
                                    runCatching { api.checkAdminAiModelStatus(modelId) }
                                        .onSuccess { statusResult ->
                                            message = "${statusResult.optString("providerId")} / ${statusResult.optString("modelId")}: ${statusResult.optString("providerStatus")}."
                                            load()
                                        }
                                        .onFailure { message = it.message ?: "Could not check provider availability." }
                                    checkingModelId = ""
                                }
                            }
                        ) { Text(if (checkingModelId == model.optString("id")) "Checking…" else "Check live status") }
                        OutlinedButton(onClick = {
                            id = model.optString("id"); name = model.optString("name")
                            provider = model.optString("providerId", "openai"); providerModelId = model.optString("modelId")
                            feature = model.optString("feature", "text-chat"); description = model.optString("description")
                            creditCost = model.optInt("creditCost").toString()
                            priceUsd = model.optDouble("priceUsd").toString()
                            billingMode = model.optString("billingMode", "fixed_credit")
                            inputRate = model.optDouble("inputPriceUsdPerMillion").toString()
                            outputRate = model.optDouble("outputPriceUsdPerMillion").toString()
                            cachedInputRate = if (model.isNull("cachedInputPriceUsdPerMillion")) "" else model.optDouble("cachedInputPriceUsdPerMillion").toString()
                            creditValueUsd = model.optDouble("creditReferenceValueUsd", 0.07999).toString()
                            costMultiplier = model.optDouble("customerMultiplier", 8.0).toString()
                            estimatedFeePercent = model.optDouble("estimatedFeePercent", 15.0).toString()
                            maxOutputTokens = model.optInt("maxOutputTokens", 2048).toString()
                            creditsPerMinute = if (model.isNull("creditsPerMinute")) "" else model.optDouble("creditsPerMinute").toString()
                            active = model.optBoolean("active"); isDefault = model.optBoolean("isDefault"); published = model.optBoolean("published")
                            message = "Editing ${model.optString("name")}."
                        }) { Text("Edit") }
                        OutlinedButton(onClick = { confirmDisable = JSONObject(model.toString()).put("active", false) }) { Text("Deactivate") }
                    }
                }
            }
        }
        Text("Add or update model", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(id, { id = it.lowercase().replace(Regex("[^a-z0-9-]"), "-") }, label = { Text("Registry ID (for example, my-chat-model)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(name, { name = it }, label = { Text("Display name shown to users") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Provider (choose a supported provider for this feature)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { provider = "openai" }) {
                Text(if (provider == "openai") "✓ OpenAI" else "OpenAI")
            }
            OutlinedButton(onClick = { provider = "gemini" }) {
                Text(if (provider == "gemini") "✓ Gemini" else "Gemini")
            }
        }
        OutlinedTextField(providerModelId, { providerModelId = it }, label = { Text("Exact provider model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Feature (tap to select)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("text-chat", "image-generation", "live-camera").forEach { option ->
                OutlinedButton(onClick = { feature = option }) { Text(if (feature == option) "✓ $option" else option) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("live-voice", "device-commands", "video-generation").forEach { option ->
                OutlinedButton(onClick = { feature = option }) { Text(if (feature == option) "✓ $option" else option) }
            }
        }
        OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(creditCost, { creditCost = it }, label = { Text("Credits charged per request (minimum 1)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(priceUsd, { priceUsd = it }, label = { Text("Legacy reference price in USD per request") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Billing mode")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { billingMode = "fixed_credit" }) { Text(if (billingMode == "fixed_credit") "✓ Fixed credits" else "Fixed credits") }
            OutlinedButton(onClick = { billingMode = "token" }) { Text(if (billingMode == "token") "✓ Token billing" else "Token billing") }
        }
        Text("Token prices are USD per 1,000,000 tokens. Configure rates for this exact model before activating token billing.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(inputRate, { inputRate = it }, label = { Text("Provider input USD per 1M tokens") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(outputRate, { outputRate = it }, label = { Text("Provider output USD per 1M tokens") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(cachedInputRate, { cachedInputRate = it }, label = { Text("Cached input USD per 1M tokens (blank = input rate)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(creditValueUsd, { creditValueUsd = it }, label = { Text("Reference value of one credit in USD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(costMultiplier, { costMultiplier = it }, label = { Text("Customer/provider cost multiplier (default 8)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(estimatedFeePercent, { estimatedFeePercent = it }, label = { Text("Estimated fee percentage (15% illustrative; configure actual fee)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(maxOutputTokens, { maxOutputTokens = it }, label = { Text("Maximum output tokens (128–8192)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(creditsPerMinute, { creditsPerMinute = it }, label = { Text("Credits per live minute (preserve existing live-session rate)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row { Checkbox(active, { active = it }); Text("Enable this model for users", Modifier.padding(top = 12.dp)) }
        Row { Checkbox(isDefault, { isDefault = it }); Text("Use as the default model for this feature", Modifier.padding(top = 12.dp)) }
        Row { Checkbox(published, { published = it }); Text("Publish in the user model catalogue", Modifier.padding(top = 12.dp)) }
        OutlinedButton(
            enabled = !saving && !validating && providerModelId.isNotBlank() && provider in setOf("openai", "gemini"),
            onClick = {
                val probe = JSONObject().apply {
                    put("providerId", provider.trim())
                    put("modelId", providerModelId.trim())
                    put("feature", feature)
                }
                scope.launch {
                    validating = true
                    runCatching { api.validateAdminAiModel(probe) }
                        .onSuccess { result ->
                            message = "Provider validation passed: ${result.optString("providerModelName", providerModelId)}. Save will revalidate before publishing."
                        }
                        .onFailure { message = it.message ?: "Provider validation failed." }
                    validating = false
                }
            }
        ) { Text(if (validating) "Validating model…" else "Validate provider model") }
        Button(
            enabled = !saving && !validating && id.isNotBlank() && name.isNotBlank() && providerModelId.isNotBlank() &&
                provider in setOf("openai", "gemini") && (creditCost.toIntOrNull() ?: -1) >= 1 &&
                (priceUsd.toDoubleOrNull() ?: -1.0) >= 0 &&
                (inputRate.toDoubleOrNull() ?: -1.0) >= 0 &&
                (outputRate.toDoubleOrNull() ?: -1.0) >= 0 &&
                (cachedInputRate.isBlank() || (cachedInputRate.toDoubleOrNull() ?: -1.0) >= 0) &&
                (creditValueUsd.toDoubleOrNull() ?: -1.0) > 0 &&
                (costMultiplier.toDoubleOrNull() ?: -1.0) >= 1 &&
                (estimatedFeePercent.toDoubleOrNull() ?: -1.0) in 0.0..100.0 &&
                (maxOutputTokens.toIntOrNull() ?: -1) in 128..8192 &&
                (creditsPerMinute.isBlank() || (creditsPerMinute.toDoubleOrNull() ?: -1.0) >= 0) &&
                (!isDefault || (active && published)),
            onClick = {
                val payload = JSONObject().apply {
                    put("id", id.trim()); put("name", name.trim()); put("providerId", provider.trim())
                    put("modelId", providerModelId.trim()); put("feature", feature)
                    put("description", description.trim()); put("creditCost", creditCost.toInt())
                    put("priceUsd", priceUsd.toDouble())
                    put("billingMode", billingMode)
                    put("inputPriceUsdPerMillion", inputRate.toDouble())
                    put("outputPriceUsdPerMillion", outputRate.toDouble())
                    if (cachedInputRate.isNotBlank()) put("cachedInputPriceUsdPerMillion", cachedInputRate.toDouble()) else put("cachedInputPriceUsdPerMillion", JSONObject.NULL)
                    put("creditReferenceValueUsd", creditValueUsd.toDouble())
                    put("customerMultiplier", costMultiplier.toDouble())
                    put("estimatedFeePercent", estimatedFeePercent.toDouble())
                    put("maxOutputTokens", maxOutputTokens.toInt())
                    if (creditsPerMinute.isNotBlank()) put("creditsPerMinute", creditsPerMinute.toDouble()) else put("creditsPerMinute", JSONObject.NULL)
                    put("active", active); put("isDefault", isDefault); put("published", published)
                }
                scope.launch {
                    saving = true
                    runCatching { api.saveAdminAiModel(payload) }
                        .onSuccess { message = "Model saved. Active feature defaults update for new requests."; load() }
                        .onFailure { message = it.message ?: "Could not save model." }
                    saving = false
                }
            }
        ) { Text(if (saving) "Validating and saving…" else "Save model and apply") }
        OutlinedButton(onClick = { id = ""; name = ""; provider = "openai"; providerModelId = ""; feature = "text-chat"; description = ""; creditCost = "1"; priceUsd = "0.01"; billingMode = "fixed_credit"; inputRate = "0"; outputRate = "0"; cachedInputRate = ""; creditValueUsd = "0.07999"; costMultiplier = "8"; estimatedFeePercent = "15"; maxOutputTokens = "2048"; creditsPerMinute = ""; active = true; isDefault = false; published = true }) { Text("Clear form for new model") }
        Text("OpenAI and Gemini can be selected for features supported by each provider. Live voice, live camera, image generation and video generation currently require compatible OpenAI models; text chat supports both providers, and device commands support OpenAI or Gemini. Use Check live status to check whether the provider API key can currently access each model. A successful model-list check does not guarantee a full generation or realtime session will succeed.", style = MaterialTheme.typography.bodySmall)
        Text("Model IDs are checked against the provider before saving. USD is a reference price; the enforced in-app charge is credits per request or credits per live minute.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClose) { Text("Close model management") }
    }

    confirmDisable?.let { selected ->
        AlertDialog(
            onDismissRequest = { confirmDisable = null },
            title = { Text("Deactivate model?") },
            text = { Text("This will mark the model inactive in the registry. If it is currently the active feature default, choose another model and save it to switch the feature safely.") },
            confirmButton = {
                TextButton(onClick = {
                    val updated = JSONObject(selected.toString()).apply {
                        put("id", selected.optString("id")); put("name", selected.optString("name"))
                        put("providerId", selected.optString("providerId")); put("modelId", selected.optString("modelId"))
                        put("feature", selected.optString("feature")); put("description", selected.optString("description"))
                        put("creditCost", selected.optInt("creditCost")); put("priceUsd", selected.optDouble("priceUsd"))
                        if (!selected.isNull("creditsPerMinute")) put("creditsPerMinute", selected.optInt("creditsPerMinute")) else put("creditsPerMinute", JSONObject.NULL)
                        put("published", selected.optBoolean("published")); put("isDefault", false); put("active", false)
                    }
                    confirmDisable = null
                    scope.launch {
                        runCatching { api.saveAdminAiModel(updated) }
                            .onSuccess { message = "Model deactivated."; load() }
                            .onFailure { message = it.message ?: "Could not deactivate model." }
                    }
                }) { Text("Deactivate") }
            },
            dismissButton = { TextButton(onClick = { confirmDisable = null }) { Text("Cancel") } }
        )
    }
}
