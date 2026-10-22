package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID

data class ChatReply(
    val text: String,
    val providerId: String?,
    val modelId: String?,
    val creditsCharged: Double
)

class ChatApiClient(
    private val baseUrl: String = "${ApiConfig.baseUrl}"
) {
    private fun authToken(): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        return Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
    }

    private suspend fun <T> withFriendlyNetworkErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: UnknownHostException) {
        throw IllegalStateException("You're offline or your connection is unstable. Check your internet connection and try again.", e)
    } catch (e: ConnectException) {
        throw IllegalStateException("You're offline or your connection is unstable. Check your internet connection and try again.", e)
    } catch (e: SocketTimeoutException) {
        throw IllegalStateException("The request took too long. Check your connection and try again.", e)
    } catch (e: IOException) {
        throw IllegalStateException("BQ Sqwad AI couldn't connect. Check your internet connection and try again.", e)
    }

    suspend fun sendMessage(
        message: String,
        capability: String = "chat",
        system: String? = null,
        modelRegistryId: String? = null
    ): ChatReply = withFriendlyNetworkErrors {
        withTimeout(120000) { withContext(Dispatchers.IO) {
            val token = authToken()
            val idempotencyKey = UUID.randomUUID().toString()
            val connection = (URL("$baseUrl/ai").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 120000
                doInput = true
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Idempotency-Key", idempotencyKey)
            }
            connection.outputStream.use {
                it.write(JSONObject().apply {
                    put("capability", capability)
                    put("message", message)
                    if (!system.isNullOrBlank()) put("system", system)
                    if (!modelRegistryId.isNullOrBlank()) put("modelRegistryId", modelRegistryId)
                }.toString().toByteArray())
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(raw) }.getOrNull()
            if (status !in 200..299) {
                val serverError = body?.optString("error").orEmpty().lowercase()
                val friendly = when {
                    status >= 500 || serverError == "route_not_migrated" || serverError == "service_temporarily_unavailable" ->
                        "BQ Sqwad AI is experiencing a temporary issue. The BQ Sqwad AI team is working on it. Please try again shortly."
                    status == 401 || serverError.contains("auth") ->
                        "Please sign in again to continue."
                    status == 402 || serverError.contains("credit") ->
                        "Your current usage allowance is unavailable for this request. Review plans and billing to continue."
                    status == 403 || serverError.contains("consent") ->
                        "Please review and accept the required BQ Sqwad AI terms to continue."
                    status == 429 || serverError.contains("rate_limited") ->
                        "BQ Sqwad AI is busy right now. Please wait a moment and try again."
                    else -> "BQ Sqwad AI couldn't complete that request. Please try again."
                }
                throw IllegalStateException(friendly)
            }
            if (body == null) {
                throw IllegalStateException("BQ Sqwad AI received an unexpected response. The BQ Sqwad AI team is working on it.")
            }
            body.optJSONObject("redirect")?.let { redirect ->
                WorkspaceRedirectStore.publish(redirect)
                connection.disconnect()
                throw WorkspaceRedirectException()
            }
            val text = body.optString("text").trim()
            if (text.isBlank()) throw IllegalStateException("The AI service returned an empty response.")
            ChatReply(
                text = text,
                providerId = body.optString("providerId").takeIf { it.isNotBlank() },
                modelId = body.optString("modelId").takeIf { it.isNotBlank() },
                creditsCharged = body.optDouble("creditsCharged", 0.0)
            )
        } } }

    suspend fun streamMessage(
        message: String,
        capability: String = "chat",
        onDelta: (String) -> Unit,
        system: String? = null,
        modelRegistryId: String? = null
    ): ChatReply = withFriendlyNetworkErrors {
        withTimeout(120000) {
        withContext(Dispatchers.IO) {
            val token = authToken()
            val idempotencyKey = UUID.randomUUID().toString()
            val connection = (URL("$baseUrl/ai/stream").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 120000
                doInput = true
                doOutput = true
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Idempotency-Key", idempotencyKey)
            }

            try {
                connection.outputStream.use {
                    it.write(JSONObject().apply {
                        put("capability", capability)
                        put("message", message)
                        if (!system.isNullOrBlank()) put("system", system)
                        if (!modelRegistryId.isNullOrBlank()) put("modelRegistryId", modelRegistryId)
                    }.toString().toByteArray())
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    val raw = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val body = runCatching { JSONObject(raw) }.getOrNull()
                    val detail = body?.optString("error").orEmpty().lowercase()
                    val friendly = when {
                        status >= 500 || detail == "route_not_migrated" || detail == "service_temporarily_unavailable" ->
                            "BQ Sqwad AI is experiencing a temporary issue. The BQ Sqwad AI team is working on it. Please try again shortly."
                        detail.contains("consent_required") -> "Please accept the required BQ Sqwad AI consent before sending messages."
                        detail.contains("auth") -> "Please sign in to use this AI feature."
                        status == 402 || detail.contains("insufficient credits") || detail.contains("credit_balance") -> "Your current usage allowance is unavailable for this request. Review plans and billing to continue."
                        detail.contains("message_required") -> "Enter a message before sending."
                        detail.contains("capability_disabled") -> "This AI capability is currently unavailable."
                        status == 429 || detail.contains("rate_limited") -> "BQ Sqwad AI is busy right now. Please wait a moment and try again."
                        else -> "BQ Sqwad AI couldn't complete that request. Please try again."
                    }
                    throw IllegalStateException(friendly)
                }

                val contentType = connection.contentType.orEmpty()
                if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                    val raw = connection.inputStream.bufferedReader().use { it.readText() }
                    val body = runCatching { JSONObject(raw) }.getOrNull()
                    val redirect = body?.optJSONObject("redirect")
                    if (redirect != null) {
                        WorkspaceRedirectStore.publish(redirect)
                        throw WorkspaceRedirectException()
                    }
                    throw IllegalStateException("BQ Sqwad AI received an unexpected response. Please retry.")
                }
                val reader = connection.inputStream.bufferedReader()
                val textBuilder = StringBuilder()
                var providerId: String? = null
                var modelId: String? = null
                var creditsCharged = 0.0
                var eventData = StringBuilder()
                var completed = false

                fun processEvent(data: String) {
                    val trimmed = data.trim()
                    if (trimmed.isEmpty() || trimmed == "[DONE]") return
                    val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return
                    when (json.optString("type")) {
                        "delta" -> {
                            val delta = json.optString("text")
                            if (delta.isNotEmpty()) {
                                textBuilder.append(delta)
                                onDelta(delta)
                            }
                        }
                        "completed" -> {
                            providerId = json.optString("providerId").takeIf { it.isNotBlank() }
                            modelId = json.optString("modelId").takeIf { it.isNotBlank() }
                            creditsCharged = json.optDouble("creditsCharged", 0.0)
                            completed = true
                        }
                        "error" -> {
                            val detail = json.optString("message").lowercase()
                            val friendly = when {
                                detail.contains("insufficient credits") || detail.contains("credit_balance") -> "Your current usage allowance is unavailable for this request. Review plans and billing to continue."
                                detail.contains("consent") -> "Please review and accept the required BQ Sqwad AI terms to continue."
                                detail.contains("rate_limited") -> "BQ Sqwad AI is busy right now. Please wait a moment and try again."
                                else -> "BQ Sqwad AI is experiencing a temporary issue. The BQ Sqwad AI team is working on it."
                            }
                            throw IllegalStateException(friendly)
                        }
                    }
                }

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        processEvent(eventData.toString())
                        eventData = StringBuilder()
                    } else if (line.startsWith("data:")) {
                        if (eventData.isNotEmpty()) eventData.append('\n')
                        eventData.append(line.removePrefix("data:").trimStart())
                    }
                }
                if (eventData.isNotEmpty()) processEvent(eventData.toString())

                if (!completed) throw IllegalStateException("AI streaming ended before completion.")
                val finalText = textBuilder.toString().trim()
                if (finalText.isBlank()) throw IllegalStateException("AI service returned no text.")

                ChatReply(
                    text = finalText,
                    providerId = providerId,
                    modelId = modelId,
                    creditsCharged = creditsCharged
                )
            } finally {
                connection.disconnect()
            }
        }
        }
    }

    suspend fun analyzeCameraFrame(
        message: String,
        imageBase64: String?,
        mimeType: String = "image/jpeg",
        capability: String = "live_camera",
        system: String? = null,
        additionalImages: List<Pair<String, String>> = emptyList(),
        additionalFiles: List<Triple<String, String, String>> = emptyList()
    ): ChatReply = withFriendlyNetworkErrors { withTimeout(120000) { withContext(Dispatchers.IO) {
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
        try {
            connection.outputStream.use {
                it.write(JSONObject().apply {
                    put("message", message)
                    val images = (if (imageBase64.isNullOrBlank()) emptyList() else listOf(mimeType to imageBase64.orEmpty())) + additionalImages
                    if (images.isNotEmpty()) put("images", JSONArray().apply {
                        images.take(8).forEach { (type, encoded) ->
                            put(JSONObject().put("mimeType", type).put("imageBase64", encoded))
                        }
                    })
                    if (additionalFiles.isNotEmpty()) put("files", JSONArray().apply {
                        additionalFiles.take(4).forEach { (name, type, encoded) ->
                            put(JSONObject().put("name", name).put("mimeType", type).put("fileBase64", encoded))
                        }
                    })
                    put("capability", capability)
                    if (!system.isNullOrBlank()) put("system", system)
                }.toString().toByteArray())
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(raw) }.getOrNull()
            if (status !in 200..299) {
                val serverError = body?.optString("error").orEmpty()
                val code = serverError.lowercase()
                val friendly = when {
                    status == 401 || code.contains("auth") -> "Please sign in again to continue."
                    status == 402 || code.contains("credit") -> "You don't have enough credits for this request."
                    status == 403 || code.contains("consent") -> "Please review the required permissions to continue."
                    status == 429 -> "BQ Sqwad AI is busy right now. Please wait a moment and try again."
                    status >= 500 -> "BQ Sqwad AI is temporarily unavailable. Please try again shortly."
                    else -> "BQ Sqwad AI couldn't complete that request. Please try again."
                }
                throw IllegalStateException(friendly)
            }
            if (body == null) {
                throw IllegalStateException("BQ Sqwad AI received an unexpected response. Please try again.")
            }
            val responseText = body.optString("text").trim()
            if (responseText.isBlank()) throw IllegalStateException("AI service returned an empty response.")
            ChatReply(
                text = responseText,
                providerId = body.optString("providerId").takeIf { it.isNotBlank() },
                modelId = body.optString("modelId").takeIf { it.isNotBlank() },
                creditsCharged = body.optDouble("creditsCharged", 0.0)
            )
        } finally {
            connection.disconnect()
        }
    } } }
}