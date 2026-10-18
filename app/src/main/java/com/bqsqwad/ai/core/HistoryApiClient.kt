package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class HistoryItem(val id: String, val title: String, val updatedAt: String, val messageCount: Int)
data class HistoryMessage(val mine: Boolean, val text: String, val id: String, val prompt: String?, val attachmentNames: List<String> = emptyList(), val imageUrl: String? = null)

/**
 * Uses the website's authenticated native-chat API so Android and the website
 * share the same Firestore chats/{chatId} records. The Cloudflare /history
 * endpoint is read only here for legacy migration on the website server.
 */
class HistoryApiClient {
    private val websiteBaseUrl = "https://bqsqwadai.com"

    private suspend fun request(path: String, method: String = "GET", payload: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
            val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
            val connection = (URL(websiteBaseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 120000
                doInput = true
                if (method != "GET") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                setRequestProperty("Authorization", "Bearer " + token)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (method != "GET") connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val body = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", "Invalid server response") }
                if (status !in 200..299) {
                    val detail = body.optString("error", "History request failed")
                    error(if (status == 401) "Please sign in again to access chat history." else detail)
                }
                body
            } finally {
                connection.disconnect()
            }
        }

    suspend fun list(limit: Int = 50): List<HistoryItem> {
        val body = request("/api/native/chats?limit=" + limit.coerceIn(1, 100))
        val rows = body.optJSONArray("chats") ?: JSONArray()
        return List(rows.length()) { i ->
            val item = rows.getJSONObject(i)
            HistoryItem(
                item.optString("id"),
                item.optString("title", "New chat"),
                item.optString("updatedAt"),
                item.optInt("messageCount", 0)
            )
        }
    }

    suspend fun load(id: String): List<HistoryMessage> {
        val path = "/api/native/chats?chatId=" + URLEncoder.encode(id, "UTF-8")
        val chat = request(path).optJSONObject("chat") ?: error("Conversation not found")
        val rows = runCatching { JSONArray(chat.optString("messagesJson", "[]")) }.getOrElse { JSONArray() }
        return List(rows.length()) { i ->
            val item = rows.getJSONObject(i)
            val parts = item.optJSONArray("parts") ?: JSONArray()
            val text = buildString {
                for (partIndex in 0 until parts.length()) {
                    val part = parts.optJSONObject(partIndex) ?: continue
                    if (part.optString("type") == "text") {
                        val value = part.optString("text")
                        if (value.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append(value)
                        }
                    }
                }
            }.ifBlank { item.optString("text") }
            val attachments = item.optJSONArray("attachments")?.let { array ->
                List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
            } ?: emptyList()
            HistoryMessage(
                mine = item.optString("role") == "user" || item.optBoolean("mine"),
                text = text,
                id = item.optString("id").ifBlank { "message-$i" },
                prompt = item.optString("prompt").ifBlank { null },
                attachmentNames = attachments,
                imageUrl = item.optString("imageUrl").ifBlank { null }
            )
        }
    }

    suspend fun save(id: String?, title: String, messages: List<HistoryMessage>): String {
        require(messages.size <= 100) {
            "This conversation has more than 100 messages. Start a new chat to keep history complete."
        }
        messages.forEach { message ->
            require(message.text.length <= 20000 && (message.prompt?.length ?: 0) <= 20000) {
                "A message is too large to save in history. Keep individual messages under 20,000 characters."
            }
            require(message.attachmentNames.size <= 8 && message.attachmentNames.all { it.length <= 300 }) {
                "This message has too many or too-large attachment names to save."
            }
            require(message.imageUrl.isNullOrBlank() || (message.imageUrl.length <= 2000 && message.imageUrl.startsWith("https://", ignoreCase = true))) {
                "The generated image link cannot be saved safely."
            }
        }
        val chatId = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val array = JSONArray()
        messages.forEach { message ->
            val parts = JSONArray()
            if (message.text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", message.text))
            val row = JSONObject()
                .put("id", message.id)
                .put("role", if (message.mine) "user" else "assistant")
                .put("parts", parts)
            if (message.prompt != null) row.put("prompt", message.prompt)
            if (message.attachmentNames.isNotEmpty()) row.put("attachments", JSONArray(message.attachmentNames))
            if (!message.imageUrl.isNullOrBlank()) row.put("imageUrl", message.imageUrl)
            array.put(row)
        }
        val payload = JSONObject()
            .put("chatId", chatId)
            .put("title", title.take(120))
            .put("messages", array)
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= 700000) {
            "This conversation is too large to save as one history item. Start a new chat to keep history complete."
        }
        val body = request("/api/native/chats", "POST", payload)
        return body.optJSONObject("chat")?.optString("id")?.takeIf { it.isNotBlank() } ?: chatId
    }

    suspend fun delete(id: String): Boolean {
        request("/api/native/chats?chatId=" + URLEncoder.encode(id, "UTF-8"), "DELETE")
        return true
    }
}
