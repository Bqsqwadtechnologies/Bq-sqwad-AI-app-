package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DeviceCommandResult(
    val message: String,
    val actions: List<DeviceAction>,
    val creditsCharged: Double
)

class DeviceIntelligenceClient {
    private val baseUrl = "https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev"

    suspend fun interpret(message: String): DeviceCommandResult = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
        val connection = (URL("$baseUrl/device-command").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 120000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use {
            it.write(JSONObject().put("message", message.trim().take(4000)).toString().toByteArray())
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        val body = runCatching { JSONObject(raw) }.getOrElse {
            error("Device intelligence returned an invalid response.")
        }
        if (status !in 200..299) error(body.optString("error", "Device intelligence failed"))
        val actionsJson = body.optJSONArray("actions")
        val actions = buildList {
            for (i in 0 until (actionsJson?.length() ?: 0)) {
                val item = actionsJson?.optJSONObject(i) ?: continue
                val type = item.optString("type").trim()
                if (type.isNotBlank()) add(DeviceAction(type, item.optString("value").takeIf { it.isNotBlank() }))
            }
        }
        DeviceCommandResult(
            message = body.optString("message").trim(),
            actions = actions,
            creditsCharged = body.optDouble("creditsCharged", 0.0)
        )
    }
}
