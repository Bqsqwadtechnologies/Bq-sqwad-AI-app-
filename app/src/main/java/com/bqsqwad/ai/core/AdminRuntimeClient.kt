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
import java.net.URLEncoder

data class AdminRuntimeRecord(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val version: Int,
    val config: JSONObject
)

class AdminRuntimeClient {
    private val baseUrl = "https://europe-west1-" +
        FirebaseApp.getInstance().options.projectId +
        ".cloudfunctions.net/bqSqwadApi"

    suspend fun list(type: String): List<AdminRuntimeRecord> =
        request("GET", "/admin/runtime?type=" + URLEncoder.encode(type, "UTF-8"))
            .let { root -> parse(root.optJSONArray("records") ?: JSONArray()) }

    suspend fun create(type: String, name: String, description: String, config: JSONObject): AdminRuntimeRecord =
        parse(request("POST", "/admin/runtime", JSONObject().apply {
            put("type", type)
            put("name", name)
            put("description", description)
            put("config", config)
        }).getJSONObject("record"))

    suspend fun update(type: String, id: String, status: String? = null, config: JSONObject? = null): AdminRuntimeRecord =
        parse(request("PATCH", "/admin/runtime/" + id, JSONObject().apply {
            put("type", type)
            if (status != null) put("status", status)
            if (config != null) put("config", config)
        }).getJSONObject("record"))

    private fun parse(array: JSONArray): List<AdminRuntimeRecord> =
        (0 until array.length()).map { parse(array.getJSONObject(it)) }

    private fun parse(o: JSONObject): AdminRuntimeRecord =
        AdminRuntimeRecord(
            id = o.optString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            status = o.optString("status", "draft"),
            version = o.optInt("version", 1),
            config = o.optJSONObject("config") ?: JSONObject()
        )

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Admin authentication required")
        val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
        val c = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
            setRequestProperty("Authorization", "Bearer " + token)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        if (status !in 200..299) error(runCatching { JSONObject(raw).optString("error") }.getOrDefault("Admin runtime request failed"))
        JSONObject(raw)
    }
}
