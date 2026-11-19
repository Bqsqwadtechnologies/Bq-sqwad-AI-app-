package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeneratedImage(
    val url: String,
    val creditsCharged: Double,
    val modelId: String?,
    val imageBase64: String? = null,
    val generationId: String? = null,
    val storageBucket: String? = null,
    val storagePath: String? = null,
    val mediaUrlExpiresIn: Long? = null,
    val requestId: String? = null,
    val status: String = "completed",
    val contentType: String = "image/png",
    val width: Int? = null,
    val height: Int? = null
)

internal fun parseGeneratedImage(
    imageUrl: String?,
    imageBase64: String?,
    creditsCharged: Double,
    modelId: String?,
    generationId: String? = null,
    storageBucket: String? = null,
    storagePath: String? = null,
    mediaUrlExpiresIn: Long? = null,
    requestId: String? = null,
    status: String = "completed",
    contentType: String = "image/png",
    width: Int? = null,
    height: Int? = null
): GeneratedImage =
    GeneratedImage(
        url = imageUrl?.takeIf { it.isNotBlank() }.orEmpty(),
        creditsCharged = creditsCharged,
        modelId = modelId?.takeIf { it.isNotBlank() },
        imageBase64 = imageBase64?.takeIf { it.isNotBlank() },
        generationId = generationId?.takeIf { it.isNotBlank() },
        storageBucket = storageBucket?.takeIf { it.isNotBlank() },
        storagePath = storagePath?.takeIf { it.isNotBlank() },
        mediaUrlExpiresIn = mediaUrlExpiresIn?.takeIf { it > 0 },
        requestId = requestId?.takeIf { it.isNotBlank() },
        status = status.ifBlank { "completed" },
        contentType = contentType.ifBlank { "image/png" },
        width = width?.takeIf { it > 0 },
        height = height?.takeIf { it > 0 }
    ).also { generated ->
        require(generated.url.isNotBlank() || !generated.imageBase64.isNullOrBlank()) {
            "Image generation returned no image data."
        }
    }

class GenerationApiClient {
    private val baseUrl = "${ApiConfig.baseUrl}"

    private suspend fun requestWebsitePayments(payload: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
            val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
            val targetUrl = "${ApiConfig.websiteBaseUrl.trimEnd('/')}/api/native/payments"
            val c = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 120000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
                val status = c.responseCode
                val stream = if (status in 200..299) c.inputStream else c.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val body = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", "Invalid server response") }
                if (status !in 200..299 || body.optBoolean("ok") == false) {
                    val errorMsg = body.optString("error").ifBlank { "Image generation could not be completed." }
                    error(errorMsg)
                }
                body
            } finally {
                c.disconnect()
            }
        }

    private suspend fun request(path: String, method: String = "POST", payload: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
            val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
            val c = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 120000
                doInput = true
                if (method != "GET") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                if (method != "GET") c.outputStream.use { it.write(payload.toString().toByteArray()) }
                val status = c.responseCode
                val stream = if (status in 200..299) c.inputStream else c.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val body = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", "Invalid server response") }
                if (status !in 200..299) {
                    val code = body.optString("error").lowercase()
                    val serverRef = body.optString("refCode").takeIf { it.isNotBlank() }
                    val message = when {
                        status == 401 || code.contains("auth") -> "Please sign in again to continue."
                        status == 402 || code.contains("credit") -> "You don't have enough credits for this request."
                        status == 403 || code.contains("consent") -> "Please review the required permissions to continue."
                        status == 429 -> "BQ Sqwad AI is busy right now. Please wait a moment and try again."
                        status >= 500 -> "BQ Sqwad AI is temporarily unavailable. Please try again shortly."
                        else -> "BQ Sqwad AI couldn't complete that request. Please try again."
                    }
                    val suffix = buildString {
                        if (serverRef != null) append(" refCode=").append(serverRef)
                        if (code.isNotBlank()) append(" errorCode=").append(code)
                    }
                    error(message + suffix)
                }
                body.optJSONObject("redirect")?.let { redirect ->
                    WorkspaceRedirectStore.publish(redirect)
                    throw WorkspaceRedirectException()
                }
                body
            } catch (e: java.net.UnknownHostException) {
                throw IllegalStateException("You're offline or your connection is unstable. Check your internet connection and try again.", e)
            } catch (e: java.net.ConnectException) {
                throw IllegalStateException("You're offline or your connection is unstable. Check your internet connection and try again.", e)
            } catch (e: java.net.SocketTimeoutException) {
                throw IllegalStateException("The request took too long. Check your connection and try again.", e)
            } finally {
                c.disconnect()
            }
        }

    suspend fun generateImage(prompt: String, size: String = "1024x1024", quality: String = "auto", modelRegistryId: String? = null): GeneratedImage {
        val websiteQuality = if (quality.contains("high", ignoreCase = true) || quality.contains("hd", ignoreCase = true)) "hd" else "standard"
        val websitePayload = JSONObject().apply {
            put("action", "generate_image")
            put("prompt", prompt.take(12000))
            put("quality", websiteQuality)
        }
        val body = runCatching {
            requestWebsitePayments(websitePayload)
        }.getOrElse { webErr ->
            val msg = webErr.message.orEmpty()
            if (msg.contains("credit", ignoreCase = true) || msg.contains("sign in", ignoreCase = true) || msg.contains("verification", ignoreCase = true)) {
                throw webErr
            }
            request("/ai/image", payload = JSONObject().apply {
                put("prompt", prompt.take(12000)); put("size", size); put("quality", quality)
                if (!modelRegistryId.isNullOrBlank()) put("modelRegistryId", modelRegistryId)
            })
        }
        val imgUrl = body.optString("imageUrl").takeIf { it.isNotBlank() && it != "null" }
            ?: body.optString("url").takeIf { it.isNotBlank() && it != "null" }
        return parseGeneratedImage(
            imgUrl,
            body.optString("imageBase64").takeUnless { it.isBlank() || it == "null" },
            body.optDouble("creditsCharged", 0.0),
            body.optString("modelId").takeUnless { it.isBlank() || it == "null" },
            body.optString("generationId").takeUnless { it.isBlank() || it == "null" },
            body.optString("storageBucket").takeUnless { it.isBlank() || it == "null" },
            body.optString("storagePath").takeUnless { it.isBlank() || it == "null" },
            body.optLong("mediaUrlExpiresIn").takeIf { it > 0 },
            body.optString("requestId").takeUnless { it.isBlank() || it == "null" },
            body.optString("status", "completed"),
            body.optString("contentType", "image/png"),
            body.optInt("width").takeIf { it > 0 },
            body.optInt("height").takeIf { it > 0 }
        )
    }

    /** Refresh an expired signed media URL after verifying the current Firebase owner. */
    suspend fun refreshMediaUrl(generationId: String): String {
        require(generationId.isNotBlank()) { "Generation ID is required." }
        val body = request("/media/signed-url", payload = JSONObject().put("generationId", generationId))
        return body.optString("signedUrl").takeIf { it.startsWith("https://") }
            ?: error("The saved image link could not be refreshed.")
    }

    suspend fun editImage(prompt: String, imageBase64: String, size: String = "1024x1024", quality: String = "auto", modelRegistryId: String? = null, mimeType: String = "image/png"): GeneratedImage {
        val websiteQuality = if (quality.contains("high", ignoreCase = true) || quality.contains("hd", ignoreCase = true)) "hd" else "standard"
        val websitePayload = JSONObject().apply {
            put("action", "generate_image")
            put("prompt", prompt.take(12000))
            put("imageBase64", imageBase64)
            put("mimeType", mimeType)
            put("quality", websiteQuality)
        }
        val body = runCatching {
            requestWebsitePayments(websitePayload)
        }.getOrElse { webErr ->
            val msg = webErr.message.orEmpty()
            if (msg.contains("credit", ignoreCase = true) || msg.contains("sign in", ignoreCase = true) || msg.contains("verification", ignoreCase = true)) {
                throw webErr
            }
            request("/ai/image/edit", payload = JSONObject().apply {
                put("prompt", prompt.take(12000)); put("imageBase64", imageBase64); put("mimeType", mimeType); put("size", size); put("quality", quality)
                if (!modelRegistryId.isNullOrBlank()) put("modelRegistryId", modelRegistryId)
            })
        }
        val imgUrl = body.optString("imageUrl").takeIf { it.isNotBlank() && it != "null" }
            ?: body.optString("url").takeIf { it.isNotBlank() && it != "null" }
        return parseGeneratedImage(
            imgUrl,
            body.optString("imageBase64").takeUnless { it.isBlank() || it == "null" },
            body.optDouble("creditsCharged", 0.0),
            body.optString("modelId").takeUnless { it.isBlank() || it == "null" },
            body.optString("generationId").takeUnless { it.isBlank() || it == "null" },
            body.optString("storageBucket").takeUnless { it.isBlank() || it == "null" },
            body.optString("storagePath").takeUnless { it.isBlank() || it == "null" },
            body.optLong("mediaUrlExpiresIn").takeIf { it > 0 },
            body.optString("requestId").takeUnless { it.isBlank() || it == "null" },
            body.optString("status", "completed"),
            body.optString("contentType", "image/png"),
            body.optInt("width").takeIf { it > 0 },
            body.optInt("height").takeIf { it > 0 }
        )
    }
}
