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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import org.json.JSONObject

private data class LivePriceForm(
    val enabled: Boolean = true,
    val killSwitch: Boolean = false,
    val creditsPerMinute: String = "0",
    val startFeeCredits: String = "0",
    val minimumBalanceToStart: String = "0",
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
            maxSessionMinutes = config.optInt("maxSessionMinutes", old.maxSessionMinutes.toIntOrNull() ?: 120).toString(),
            modelId = config.optString("modelId", ""),
            defaultVoice = config.optString("defaultVoice", "marin"),
            transcriptionModel = config.optString("transcriptionModel", "gpt-4o-mini-transcribe")
        )
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
            PriceField("Credits per minute", form.creditsPerMinute) { onChange(form.copy(creditsPerMinute = it)) }
            PriceField("Start fee (credits)", form.startFeeCredits) { onChange(form.copy(startFeeCredits = it)) }
            PriceField("Minimum balance to start", form.minimumBalanceToStart) { onChange(form.copy(minimumBalanceToStart = it)) }
            PriceField("Maximum session minutes", form.maxSessionMinutes) { onChange(form.copy(maxSessionMinutes = it)) }
            if (showVoiceSettings) {
                PriceField("Realtime model override (optional)", form.modelId) { onChange(form.copy(modelId = it)) }
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
