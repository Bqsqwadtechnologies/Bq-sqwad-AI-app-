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

data class HistoryItem(val id: String, val title: String, val updatedAt: String, val messageCount: Int)
data class HistoryMessage(val mine: Boolean, val text: String, val id: String, val prompt: String?, val attachmentNames: List<String> = emptyList(), val imageUrl: String? = null)

class HistoryApiClient {
    private val baseUrl = "${ApiConfig.baseUrl}"

    private suspend fun request(path: String, method: String = "GET", payload: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
            val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 120000
                doInput = true
                if (method != "GET") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                setRequestProperty("Authorization", "Bearer " + token)
            }
            try {
                if (method != "GET") connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val body = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", "Invalid server response") }
                if (status !in 200..299) error(body.optString("error", "History request failed"))
                body
            } finally {
                connection.disconnect()
            }
        }

    suspend fun list(limit: Int = 50): List<HistoryItem> {
        val body = request("/history?limit=" + limit.coerceIn(1, 100))
        val rows = body.optJSONArray("items") ?: JSONArray()
        return List(rows.length()) { i ->
            val item = rows.getJSONObject(i)
            HistoryItem(item.optString("id"), item.optString("title", "New chat"), item.optString("updatedAt"), item.optInt("messageCount", 0))
        }
    }

    suspend fun load(id: String): List<HistoryMessage> {
        val body = request("/history/" + java.net.URLEncoder.encode(id, "UTF-8"))
        val rows = body.optJSONArray("messages") ?: JSONArray()
        return List(rows.length()) { i ->
            val item = rows.getJSONObject(i)
            HistoryMessage(item.optBoolean("mine"), item.optString("text"), item.optString("id"), item.optString("prompt").ifBlank { null }, item.optJSONArray("attachments")?.let { array -> List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() } } ?: emptyList(), item.optString("imageUrl").ifBlank { null })
        }
    }

    suspend fun save(id: String?, title: String, messages: List<HistoryMessage>): String {
        val array = JSONArray()
        messages.takeLast(100).forEach { m ->
            array.put(JSONObject().apply {
                put("id", m.id)
                put("mine", m.mine)
                put("text", m.text.take(20000))
                if (m.prompt != null) put("prompt", m.prompt.take(20000))
                if (m.attachmentNames.isNotEmpty()) put("attachments", JSONArray(m.attachmentNames.take(8).map { it.take(300) }))
                if (!m.imageUrl.isNullOrBlank()) put("imageUrl", m.imageUrl.take(2000))
            })
        }
        val body = request("/history", "POST", JSONObject().apply {
            if (!id.isNullOrBlank()) put("id", id)
            put("title", title.take(180))
            put("messages", array)
        })
        return body.optString("id")
    }

    suspend fun delete(id: String): Boolean {
        request("/history/" + java.net.URLEncoder.encode(id, "UTF-8"), "DELETE")
        return true
    }
}
