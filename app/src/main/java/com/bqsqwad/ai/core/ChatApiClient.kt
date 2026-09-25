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
    private val baseUrl: String = "https://europe-west1-" +
        FirebaseApp.getInstance().options.projectId + ".cloudfunctions.net/bqSqwadApi"
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
            val connection = (URL("$baseUrl/ai").openConnection() as HttpURLConnection).apply {
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
            val raw = stream.bufferedReader().use { it.readText() }
            val body = runCatching { JSONObject(raw) }.getOrElse {
                throw IllegalStateException("The AI service returned an invalid response.")
            }
            if (status !in 200..299) throw IllegalStateException(body.optString("error", "AI request failed"))
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
        try {
            withContext(Dispatchers.IO) {
                val token = authToken()
                val connection = (URL("$baseUrl/ai/stream").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 120000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Accept", "text/event-stream")
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
                if (status !in 200..299) {
                    val raw = (connection.errorStream ?: connection.inputStream).bufferedReader().use { it.readText() }
                    throw IllegalStateException(
                        runCatching { JSONObject(raw).optString("error").ifBlank { raw.take(500) } }
                            .getOrDefault("AI streaming request failed")
                    )
                }
                try {
                    val reader: BufferedReader = connection.inputStream.bufferedReader()
                    val text = StringBuilder()
                    reader.useLines { lines ->
                        for (line in lines) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            val event = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                            when (event.optString("type")) {
                                "delta" -> {
                                    val delta = event.optString("text")
                                    if (delta.isNotEmpty()) {
                                        text.append(delta)
                                        onDelta(delta)
                                    }
                                }
                                "completed" -> Unit
                                "error" -> throw IllegalStateException(
                                    event.optString("message", "AI streaming request failed")
                                )
                            }
                        }
                    }
                    val result = text.toString().trim()
                    if (result.isBlank()) throw IllegalStateException("AI stream returned no text.")
                    ChatReply(result, "openai", null, 0.0)
                } finally {
                    connection.disconnect()
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // Keep chat usable when SSE/provider streaming is unavailable. The normal
            // authenticated endpoint uses the configured provider fallback route.
            val fallback = sendMessage(message, capability, system)
            if (fallback.text.isBlank()) throw t
            // The caller will replace the streamed draft with the complete fallback reply.\n            // Do not emit the whole fallback as a delta or the UI would duplicate text.\n            fallback
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
        val raw = stream.bufferedReader().use { it.readText() }
        val body = runCatching { JSONObject(raw) }.getOrElse {
            throw IllegalStateException("The vision service returned an invalid response.")
        }
        if (status !in 200..299) throw IllegalStateException(body.optString("error", "Camera analysis failed"))
        ChatReply(
            text = body.optString("text").trim(),
            providerId = body.optString("providerId").takeIf { it.isNotBlank() },
            modelId = body.optString("modelId").takeIf { it.isNotBlank() },
            creditsCharged = body.optDouble("creditsCharged", 0.0)
        )
    } }
}