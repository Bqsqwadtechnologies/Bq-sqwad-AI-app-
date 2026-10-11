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
    var message by remember { mutableStateOf<String?>(null) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("openai") }
    var providerModelId by remember { mutableStateOf("") }
    var feature by remember { mutableStateOf("text-chat") }
    var description by remember { mutableStateOf("") }
    var creditCost by remember { mutableStateOf("1") }
    var priceUsd by remember { mutableStateOf("0.01") }
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
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI Model Management", style = MaterialTheme.typography.headlineSmall)
        Text("Changes are stored on the server. Active models become the server default for their feature on subsequent requests. API keys stay in Cloudflare secrets; never enter keys here.", style = MaterialTheme.typography.bodyMedium)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (loading) Text("Loading registered models…")
        models.forEach { model ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(model.optString("name"), style = MaterialTheme.typography.titleMedium)
                    Text("${model.optString("providerId")} / ${model.optString("modelId")}", style = MaterialTheme.typography.bodySmall)
                    Text("Feature: ${model.optString("feature")} · Credits/request: ${model.optInt("creditCost")} · Display price: $${"%.4f".format(model.optDouble("priceUsd"))}")
                    Text("Status: ${if (model.optBoolean("active")) "enabled" else "disabled"} · ${if (model.optBoolean("isDefault")) "DEFAULT" else "not default"} · ${if (model.optBoolean("published")) "listed" else "hidden from user catalogue"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            id = model.optString("id"); name = model.optString("name")
                            provider = model.optString("providerId", "openai"); providerModelId = model.optString("modelId")
                            feature = model.optString("feature", "text-chat"); description = model.optString("description")
                            creditCost = model.optInt("creditCost").toString()
                            priceUsd = model.optDouble("priceUsd").toString()
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
        OutlinedTextField(provider, { provider = it.lowercase() }, label = { Text("Provider: openai or gemini") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
        OutlinedTextField(creditCost, { creditCost = it }, label = { Text("Credits charged per request") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(priceUsd, { priceUsd = it }, label = { Text("Display price in USD per request (informational)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row { Checkbox(active, { active = it }); Text("Enable this model for users", Modifier.padding(top = 12.dp)) }
        Row { Checkbox(isDefault, { isDefault = it }); Text("Use as the default model for this feature", Modifier.padding(top = 12.dp)) }
        Row { Checkbox(published, { published = it }); Text("Publish in the user model catalogue", Modifier.padding(top = 12.dp)) }
        Button(
            enabled = !saving && id.isNotBlank() && name.isNotBlank() && providerModelId.isNotBlank() &&
                provider in setOf("openai", "gemini") && (creditCost.toIntOrNull() ?: -1) >= 0 &&
                (priceUsd.toDoubleOrNull() ?: -1.0) >= 0,
            onClick = {
                val payload = JSONObject().apply {
                    put("id", id.trim()); put("name", name.trim()); put("providerId", provider.trim())
                    put("modelId", providerModelId.trim()); put("feature", feature)
                    put("description", description.trim()); put("creditCost", creditCost.toInt())
                    put("priceUsd", priceUsd.toDouble()); put("active", active); put("isDefault", isDefault); put("published", published)
                }
                scope.launch {
                    saving = true
                    runCatching { api.saveAdminAiModel(payload) }
                        .onSuccess { message = "Model saved. Active feature defaults update for new requests."; load() }
                        .onFailure { message = it.message ?: "Could not save model." }
                    saving = false
                }
            }
        ) { Text(if (saving) "Saving…" else "Save model and apply") }
        OutlinedButton(onClick = { id = ""; name = ""; provider = "openai"; providerModelId = ""; feature = "text-chat"; description = ""; creditCost = "1"; priceUsd = "0.01"; active = true; isDefault = false; published = true }) { Text("Clear form for new model") }
        Text("Important: model IDs must be valid for the provider and feature. Voice/image/video APIs have different request formats; registering a model does not make an incompatible model work with those APIs. Verify each feature before enabling it.", style = MaterialTheme.typography.bodySmall)
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
