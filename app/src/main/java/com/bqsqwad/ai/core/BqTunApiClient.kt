package com.bqsqwad.ai.core

import com.bqsqwad.ai.BuildConfig
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native client for the BQ Tun fine-tuning/inference service.
 *
 * The server is authenticated with the user's Firebase ID token.
 * The production base URL is supplied at build time through BQ_TUN_BASE_URL;
 * no server credential is stored in the APK.
 */
class BqTunApiClient(
    private val baseUrl: String = BuildConfig.BQ_TUN_BASE_URL
) {
    private fun authToken(): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: error("Sign in is required")
        return Tasks.await(user.getIdToken(false)).token
            ?: error("Authentication token unavailable")
    }

    private suspend fun request(
        path: String,
        method: String,
        payload: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = authToken()
        val url = baseUrl.trimEnd('/') + path
        require(url.startsWith("https://")) {
            "BQ Tun backend URL is not configured. Set BQ_TUN_BASE_URL to an HTTPS endpoint."
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 120000
            doInput = true
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

        try {
            payload?.let { body ->
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(raw) }.getOrNull()
            if (status !in 200..299) {
                val detail = body?.optString("detail").orEmpty()
                    .ifBlank { body?.optString("error").orEmpty() }
                    .ifBlank { raw.trim().take(2000) }
                    .ifBlank { "BQ Tun request failed (HTTP $status)." }
                throw IllegalStateException(detail)
            }
            body ?: JSONObject().put("raw", raw)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun health(): JSONObject = request("/health", "GET")

    suspend fun chat(
        agentId: String,
        message: String,
        instructions: String? = null,
        knowledge: JSONArray? = null
    ): JSONObject = request(
        "/v1/chat",
        "POST",
        JSONObject().apply {
            put("agentId", agentId)
            put("message", message)
            if (!instructions.isNullOrBlank()) put("instructions", instructions)
            if (knowledge != null) put("knowledge", knowledge)
        }
    )

    suspend fun submitTraining(config: JSONObject): JSONObject =
        request("/v1/train", "POST", config)

    suspend fun trainingStatus(jobId: String): JSONObject =
        request("/v1/train/status/$jobId", "GET")

    suspend fun cancelTraining(jobId: String): JSONObject =
        request("/v1/train/cancel/$jobId", "POST")

    suspend fun deploy(jobId: String): JSONObject =
        request("/v1/deploy", "POST", JSONObject().put("jobId", jobId))

    suspend fun harvest(
        queries: List<String>,
        sources: String? = null,
        limit: Int = 15
    ): JSONObject = request(
        "/v1/harvest",
        "POST",
        JSONObject().apply {
            put("queries", JSONArray(queries))
            if (!sources.isNullOrBlank()) put("sources", sources)
            put("limit", limit)
        }
    )

    suspend fun harvestStatus(harvestId: String): JSONObject =
        request("/v1/harvest/$harvestId", "GET")

    suspend fun trainFromHarvest(harvestId: String): JSONObject =
        request("/v1/jobs/from-harvest/$harvestId", "POST")
}
