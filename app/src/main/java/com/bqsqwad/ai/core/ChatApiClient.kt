package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class ChatReply(
    val text: String,
    val providerId: String?,
    val modelId: String?,
    val creditsCharged: Double
)

class ChatApiClient(
    private val baseUrl: String = "https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev"
) {
    private fun authToken(): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        return Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
    }

    suspend fun sendMessage(
        message: String,
        capability: String = "chat",
        system: String? = null
    ): ChatReply =
        withTimeout(120000) { withContext(Dispatchers.IO) {
            val token = authToken()
            val connection = (URL("$baseUrl/ai/stream").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 120000
                doInput = true
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use {
                it.write(JSONObject().apply {
                    put("capability", capability)
                    put("message", message)
                    if (!system.isNullOrBlank()) put("system", system)
                }.toString().toByteArray())
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(raw) }.getOrNull()
            if (status !in 200..299) {
                val serverError = body?.optString("error").orEmpty()
                val trimmed = raw.trim()
                val detail = serverError.ifBlank {
                    if (trimmed.startsWith("<html", ignoreCase = true) || trimmed.startsWith("<!doctype", ignoreCase = true)) {
                        "BQ Sqwad AI's secure backend is unavailable right now. Please try again shortly."
                    } else {
                        trimmed.take(2000).ifBlank { "No response body" }
                    }
                }
                throw IllegalStateException("HTTP $status: $detail")
            }
            if (body == null) {
                throw IllegalStateException("HTTP $status: Invalid JSON response: " + raw.trim().take(2000).ifBlank { "No response body" })
            }
            val text = body.optString("text").trim()
            if (text.isBlank()) throw IllegalStateException("The AI service returned an empty response.")
            ChatReply(
                text = text,
                providerId = body.optString("providerId").takeIf { it.isNotBlank() },
                modelId = body.optString("modelId").takeIf { it.isNotBlank() },
                creditsCharged = body.optDouble("creditsCharged", 0.0)
            )
        } }

    suspend fun streamMessage(
        message: String,
        capability: String = "chat",
        onDelta: (String) -> Unit,
        system: String? = null
    ): ChatReply = withTimeout(120000) {
        withContext(Dispatchers.IO) {
            val token = authToken()
            val connection = (URL("$baseUrl/ai").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 120000
                doInput = true
                doOutput = true
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use {
                it.write(JSONObject().apply {
                    put("capability", capability)
                    put("message", message)
                    if (!system.isNullOrBlank()) put("system", system)
                }.toString().toByteArray())
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            val body = runCatching { JSONObject(raw) }.getOrNull()
            if (status !in 200..299) {
                val detail = body?.optString("error").orEmpty()
                    .ifBlank { raw.trim().take(1000) }
                    .ifBlank { "AI request failed (HTTP " + status + ")." }
                val friendly = when {
                    detail.contains("consent_required", ignoreCase = true) -> "Please accept the required BQ Sqwad AI consent before sending messages."
                    detail.contains("Authentication required", ignoreCase = true) -> "Please sign in to use this AI feature."
                    detail.contains("Insufficient credits", ignoreCase = true) -> "You do not have enough credits for this AI request."
                    detail.contains("capability_disabled", ignoreCase = true) -> "This AI capability is currently disabled by the administrator."
                    else -> detail
                }
                throw IllegalStateException(friendly)
            }
            if (body == null) throw IllegalStateException("AI service returned an invalid response.")
            val reply = ChatReply(
                text = body.optString("text").trim(),
                providerId = body.optString("providerId").takeIf { it.isNotBlank() },
                modelId = body.optString("modelId").takeIf { it.isNotBlank() },
                creditsCharged = body.optDouble("creditsCharged", 0.0)
            )
            if (reply.text.isBlank()) throw IllegalStateException("AI service returned no text.")
            onDelta(reply.text)
            reply
        }
    }

    suspend fun analyzeCameraFrame(
        message: String,
        imageBase64: String,
        mimeType: String = "image/jpeg"
    ): ChatReply = withTimeout(120000) { withContext(Dispatchers.IO) {
        val token = authToken()
        val connection = (URL("$baseUrl/ai/vision").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 120000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use {
            it.write(JSONObject().apply {
                put("message", message)
                put("imageBase64", imageBase64)
                put("mimeType", mimeType)
            }.toString().toByteArray())
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val body = runCatching { JSONObject(raw) }.getOrNull()
        if (status !in 200..299) {
            val serverError = body?.optString("error").orEmpty()
            val detail = serverError.ifBlank { raw.trim().take(2000).ifBlank { "No response body" } }
            throw IllegalStateException("HTTP $status: $detail")
        }
        if (body == null) {
            throw IllegalStateException("HTTP $status: Invalid JSON response: " + raw.trim().take(2000).ifBlank { "No response body" })
        }
        ChatReply(
            text = body.optString("text").trim(),
            providerId = body.optString("providerId").takeIf { it.isNotBlank() },
            modelId = body.optString("modelId").takeIf { it.isNotBlank() },
            creditsCharged = body.optDouble("creditsCharged", 0.0)
        )
    } }
}