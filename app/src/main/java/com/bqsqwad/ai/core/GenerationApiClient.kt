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
data class VideoJob(val jobId: String, val status: String, val progress: Int, val contentUrl: String?, val error: String?, val creditsCharged: Double = 0.0)

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
            if (method != "GET") c.outputStream.use { it.write(payload.toString().toByteArray()) }
            val status = c.responseCode
            val raw = (if (status in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            val body = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", "Invalid server response") }
            if (status !in 200..299) error(body.optString("error", "Generation request failed"))
            body
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
    suspend fun createVideo(prompt: String, seconds: String = "8", size: String = "1280x720", modelRegistryId: String? = null): VideoJob {
        val body = request("/video/generate", payload = JSONObject().apply {
            put("prompt", prompt.take(12000)); put("seconds", seconds); put("size", size)
            modelRegistryId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("modelRegistryId", it) }
        })
        return VideoJob(
            jobId = body.optString("jobId"),
            status = body.optString("status", "queued"),
            progress = body.optInt("progress", 0),
            contentUrl = null,
            error = null,
            creditsCharged = body.optDouble("creditsCharged", 0.0)
        )
    }

    suspend fun videoJob(jobId: String): VideoJob {
        val body = request("/video/jobs/" + java.net.URLEncoder.encode(jobId, "UTF-8"), "GET")
        return VideoJob(
            jobId = body.optString("jobId", jobId),
            status = body.optString("status", "queued"),
            progress = body.optInt("progress", 0),
            contentUrl = body.optString("contentUrl").takeIf { it.isNotBlank() },
            error = body.optString("error").takeIf { it.isNotBlank() },
            creditsCharged = body.optDouble("creditsCharged", 0.0)
        )
    }
}
