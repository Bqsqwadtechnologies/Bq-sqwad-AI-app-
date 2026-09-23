package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeneratedImage(val url: String, val creditsCharged: Double, val modelId: String?)
data class VideoJob(val jobId: String, val status: String, val progress: Int, val contentUrl: String?, val error: String?, val creditsCharged: Double = 0.0)

class GenerationApiClient {
    private val baseUrl = "https://europe-west1-" + FirebaseApp.getInstance().options.projectId + ".cloudfunctions.net/bqSqwadApi"

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

    suspend fun generateImage(prompt: String, size: String = "1024x1024", quality: String = "auto"): GeneratedImage {
        val body = request("/ai/image", payload = JSONObject().apply {
            put("prompt", prompt.take(12000)); put("size", size); put("quality", quality)
        })
        return GeneratedImage(
            url = body.optString("imageUrl"),
            creditsCharged = body.optDouble("creditsCharged", 0.0),
            modelId = body.optString("modelId").takeIf { it.isNotBlank() }
        )
    }

    suspend fun createVideo(prompt: String, seconds: String = "8", size: String = "1280x720"): VideoJob {
        val body = request("/video/generate", payload = JSONObject().apply {
            put("prompt", prompt.take(12000)); put("seconds", seconds); put("size", size)
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
