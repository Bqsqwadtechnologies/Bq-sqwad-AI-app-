package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native transport for the website-owned user preference document.
 * The website remains authoritative; no billing or administrator fields are writable.
 */
object SettingsSyncClient {
    private val websiteBaseUrl = "https://bqsqwadai.com"

    suspend fun load(): JSONObject = request("GET", null)

    suspend fun save(settings: JSONObject): JSONObject = request(
        "PATCH",
        JSONObject().put("settings", settings)
    )

    private suspend fun request(method: String, payload: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
            val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
            val connection = (URL("$websiteBaseUrl/api/native/settings").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 30000
                doInput = true
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (payload != null) connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val result = runCatching { JSONObject(raw) }
                    .getOrElse { JSONObject().put("error", "Invalid server response") }
                if (status !in 200..299) error(result.optString("error").ifBlank { "Settings sync failed ($status)" })
                result
            } finally {
                connection.disconnect()
            }
        }
}
