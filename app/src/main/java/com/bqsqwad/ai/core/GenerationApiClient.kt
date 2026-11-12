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
    val imageBase64: String? = null
)

internal fun parseGeneratedImage(
    imageUrl: String?,
    imageBase64: String?,
    creditsCharged: Double,
    modelId: String?
): GeneratedImage =
    GeneratedImage(
        url = imageUrl?.takeIf { it.isNotBlank() }.orEmpty(),
        creditsCharged = creditsCharged,
        modelId = modelId?.takeIf { it.isNotBlank() },
        imageBase64 = imageBase64?.takeIf { it.isNotBlank() }
    ).also { generated ->
        require(generated.url.isNotBlank() || !generated.imageBase64.isNullOrBlank()) {
            "Image generation returned no image data."
        }
    }

class GenerationApiClient {
    private val baseUrl = "${ApiConfig.baseUrl}"

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
                    val message = when {
                        status == 401 || code.contains("auth") -> "Please sign in again to continue."
                        status == 402 || code.contains("credit") -> "You don't have enough credits for this request."
                        status == 403 || code.contains("consent") -> "Please review the required permissions to continue."
                        status == 429 -> "BQ Sqwad AI is busy right now. Please wait a moment and try again."
                        status >= 500 -> "BQ Sqwad AI is temporarily unavailable. Please try again shortly."
                        else -> "BQ Sqwad AI couldn't complete that request. Please try again."
                    }
                    error(message)
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
        val body = request("/ai/image", payload = JSONObject().apply {
            put("prompt", prompt.take(12000)); put("size", size); put("quality", quality)
            if (!modelRegistryId.isNullOrBlank()) put("modelRegistryId", modelRegistryId)
        })
        return parseGeneratedImage(body.optString("imageUrl"), body.optString("imageBase64"), body.optDouble("creditsCharged", 0.0), body.optString("modelId"))
    }

    suspend fun editImage(prompt: String, imageBase64: String, size: String = "1024x1024", quality: String = "auto", modelRegistryId: String? = null, mimeType: String = "image/png"): GeneratedImage {
        val body = request("/ai/image/edit", payload = JSONObject().apply {
            put("prompt", prompt.take(12000)); put("imageBase64", imageBase64); put("mimeType", mimeType); put("size", size); put("quality", quality)
            if (!modelRegistryId.isNullOrBlank()) put("modelRegistryId", modelRegistryId)
        })
        return parseGeneratedImage(body.optString("imageUrl"), body.optString("imageBase64"), body.optDouble("creditsCharged", 0.0), body.optString("modelId"))
    }

}
