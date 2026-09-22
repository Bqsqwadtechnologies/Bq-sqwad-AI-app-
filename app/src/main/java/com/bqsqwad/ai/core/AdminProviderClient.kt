package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AdminProviderSecretStatus(
    val providerId: String,
    val configured: Boolean,
    val source: String,
    val updatedAt: String?
)

class AdminProviderClient(
    private val baseUrl: String = "https://europe-west1-" + FirebaseApp.getInstance().options.projectId + ".cloudfunctions.net/bqSqwadApi"
) {
    suspend fun statuses(): List<AdminProviderSecretStatus> =
        request("GET", "/admin/providers", null).let { root ->
            val array = root.optJSONArray("providers") ?: JSONArray()
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                AdminProviderSecretStatus(
                    providerId = o.optString("providerId"),
                    configured = o.optBoolean("configured", false),
                    source = o.optString("source", "unknown"),
                    updatedAt = o.optString("updatedAt").takeIf { it.isNotBlank() && it != "null" }
                )
            }
        }

    suspend fun save(providerId: String, secret: String) {
        request("POST", "/admin/provider-secret", JSONObject().apply {
            put("providerId", providerId)
            put("secretValue", secret)
        })
    }

    suspend fun remove(providerId: String) {
        request("POST", "/admin/provider-secret", JSONObject().apply {
            put("providerId", providerId)
            put("remove", true)
        })
    }

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val user = FirebaseAuth.getInstance().currentUser ?: error("Admin authentication required")
            val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 30000
                doInput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toString().toByteArray()) }
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) {
                throw IllegalStateException(runCatching { JSONObject(raw).optString("error") }.getOrDefault("Admin provider request failed"))
            }
            runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        }
}
