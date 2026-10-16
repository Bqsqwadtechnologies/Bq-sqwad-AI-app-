package com.bqsqwad.ai.core

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

data class HistoryItem(val id: String, val title: String, val updatedAt: String, val messageCount: Int)
data class HistoryMessage(val mine: Boolean, val text: String, val id: String, val prompt: String?, val attachmentNames: List<String> = emptyList(), val imageUrl: String? = null)

/**
 * Chat history is stored by the website backend and shared with the website frontend.
 * Stable IDs make upserts safe when a save is retried after a network failure.
 */
class HistoryApiClient {
    private val website = WebsiteApiClient()

    suspend fun list(limit: Int = 50): List<HistoryItem> {
        val body = website.listChats()
        val rows = body.optJSONArray("chats") ?: body.optJSONArray("items") ?: JSONArray()
        return List(rows.length()) { i ->
            val item = rows.getJSONObject(i)
            HistoryItem(
                id = item.optString("chatId").ifBlank { item.optString("id") },
                title = item.optString("title", "New chat").ifBlank { "New chat" },
                updatedAt = item.optString("updatedAt").ifBlank { item.optString("updated_at") },
                messageCount = item.optInt("messageCount", item.optInt("message_count", 0))
            )
        }.filter { it.id.isNotBlank() }.take(limit.coerceIn(1, 100))
    }

    suspend fun load(id: String): List<HistoryMessage> {
        val body = website.getChat(id)
        val chat = body.optJSONObject("chat") ?: body
        val rows = chat.optJSONArray("messages") ?: JSONArray()
        return List(rows.length()) { i ->
            val item = rows.getJSONObject(i)
            val role = item.optString("role")
            val mine = item.optBoolean("mine", role.equals("user", true))
            val text = item.optString("content").ifBlank { item.optString("text") }.ifBlank { textFromParts(item.optJSONArray("parts")) }
            val attachments = item.optJSONArray("attachments")?.let { array ->
                List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
            } ?: emptyList()
            HistoryMessage(
                mine = mine,
                text = text,
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
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
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in is required")
        // The first message ID is stable for the conversation, so retries after a
        // failed save reuse the same chatId instead of creating duplicate records.
        val firstMessageId = messages.firstOrNull()?.id?.takeIf { it.isNotBlank() }
        val stableId = id?.takeIf { it.isNotBlank() } ?: if (firstMessageId != null) {
            UUID.nameUUIDFromBytes("$uid:$firstMessageId".toByteArray(StandardCharsets.UTF_8)).toString()
        } else UUID.randomUUID().toString()

        val array = JSONArray()
        messages.forEach { message ->
            val role = if (message.mine) "user" else "assistant"
            val item = JSONObject()
                .put("id", message.id.ifBlank { UUID.randomUUID().toString() })
                .put("role", role)
                .put("content", message.text)
                .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", message.text)))
                .put("mine", message.mine)
            if (message.prompt != null) item.put("prompt", message.prompt)
            if (message.attachmentNames.isNotEmpty()) item.put("attachments", JSONArray(message.attachmentNames))
            if (!message.imageUrl.isNullOrBlank()) item.put("imageUrl", message.imageUrl)
            array.put(item)
        }
        val payloadSize = array.toString().toByteArray(StandardCharsets.UTF_8).size
        require(payloadSize <= 700000) {
            "This conversation is too large to save as one history item. Start a new chat to keep history complete."
        }
        website.saveChat(stableId, title, array)
        return stableId
    }

    suspend fun delete(id: String): Boolean {
        website.deleteChat(id)
        return true
    }

    private fun textFromParts(parts: JSONArray?): String {
        if (parts == null) return ""
        return buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
    }
}
