package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Shared website API. Account credits, entitlements, plans, chat history and the
 * primary chat endpoint are owned by the website backend, not the Cloudflare Worker.
 */
data class WebsiteChatMessage(val id: String, val role: String, val content: String)

class WebsiteApiClient(
    private val baseUrl: String = "https://bqsqwadai.com"
) {
    private fun token(forceRefresh: Boolean): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        return Tasks.await(user.getIdToken(forceRefresh)).token
            ?: error("Authentication token unavailable")
    }

    private fun openJson(path: String, method: String, payload: JSONObject?, forceRefresh: Boolean): HttpURLConnection {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = if (path == "/api/chat") 120000 else 20000
            doInput = true
            if (payload != null && method != "GET" && method != "DELETE") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            if (path != "/api/native/pricing" && path != "/api/public/catalog") {
                setRequestProperty("Authorization", "Bearer " + token(forceRefresh))
            }
            setRequestProperty("Accept", "application/json")
        }
        if (payload != null && method != "GET" && method != "DELETE") {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }

    suspend fun requestJson(path: String, method: String = "GET", payload: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                for (attempt in 0..1) {
                    connection = openJson(path, method, payload, forceRefresh = attempt == 1)
                    val status = connection.responseCode
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED && attempt == 0 && path != "/api/native/pricing" && path != "/api/public/catalog") {
                        connection.disconnect()
                        connection = null
                        continue
                    }
                    val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val body = runCatching { JSONObject(raw) }.getOrElse {
                        JSONObject().put("error", if (status in 200..299) "Invalid server response" else raw.take(500))
                    }
                    if (status !in 200..299) {
                        val detail = body.optString("error").ifBlank { "Website request failed (HTTP $status)" }
                        throw IllegalStateException(detail)
                    }
                    return@withContext body
                }
                error("Sign in required.")
            } finally {
                connection?.disconnect()
            }
        }

    suspend fun me(): JSONObject = requestJson("/api/native/me")
    suspend fun billing(): JSONObject = requestJson("/api/native/billing")
    suspend fun pricing(): JSONObject = requestJson("/api/native/pricing")
    suspend fun publicCatalog(): JSONObject = requestJson("/api/public/catalog")

    /** Returns a compatibility snapshot for the existing Android credit UI. */
    suspend fun creditSnapshot(): JSONObject {
        val me = me()
        val billing = billing()
        val balanceValue = billing.opt("balance")
        val balanceObject = billing.optJSONObject("balance")
            ?: me.optJSONObject("credits")
            ?: JSONObject()
        val meCreditsValue = me.opt("credits")
        val available = when (balanceValue) {
            is Number -> balanceValue.toDouble()
            else -> firstNumber(
                balanceObject, "available", "credits", "balance", "total"
            ) ?: (meCreditsValue as? Number)?.toDouble()
                ?: firstNumber(me.optJSONObject("credits") ?: JSONObject(), "available", "balance", "credits")
                ?: 0.0
        }
        val result = JSONObject(me.toString())
        result.put("available", available.coerceAtLeast(0.0))
        result.put("adminUnlimited", me.optBoolean("adminUnlimited", billing.optBoolean("adminUnlimited", false)))
        result.put("balance", if (balanceValue is JSONObject) JSONObject(balanceValue.toString()) else available)
        billing.optJSONObject("entitlements")?.let { result.put("entitlements", JSONObject(it.toString())) }
        billing.optJSONArray("transactions")?.let { result.put("transactions", JSONArray(it.toString())) }
        return result
    }

    private fun firstNumber(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is Number) return value.toDouble()
        }
        return null
    }

    suspend fun listChats(): JSONObject = requestJson("/api/native/chats")

    suspend fun getChat(chatId: String): JSONObject =
        requestJson("/api/native/chats?chatId=" + java.net.URLEncoder.encode(chatId, "UTF-8"))

    suspend fun saveChat(chatId: String, title: String, messages: JSONArray): JSONObject =
        requestJson("/api/native/chats", "POST", JSONObject()
            .put("chatId", chatId)
            .put("title", title.take(180))
            .put("messages", messages))

    suspend fun deleteChat(chatId: String): JSONObject =
        requestJson("/api/native/chats?chatId=" + java.net.URLEncoder.encode(chatId, "UTF-8"), "DELETE")

    /**
     * Streams the primary chat through the website's shared credit ledger.
     * Each message includes both modern UIMessage parts and plain content for
     * compatibility with existing server-side message adapters.
     */
    suspend fun streamChat(
        conversation: List<WebsiteChatMessage>,
        latestUserText: String,
        system: String?,
        onDelta: (String) -> Unit
    ): ChatReply = withTimeout(120000) {
        withContext(Dispatchers.IO) {
            val history = JSONArray()
            if (!system.isNullOrBlank()) history.put(uiMessage(UUID.randomUUID().toString(), "system", system))
            val messages = conversation.toMutableList()
            val lastUser = messages.indexOfLast { it.role == "user" }
            if (lastUser >= 0) messages[lastUser] = messages[lastUser].copy(content = latestUserText)
            else messages.add(WebsiteChatMessage(UUID.randomUUID().toString(), "user", latestUserText))
            messages.forEach { history.put(uiMessage(it.id.ifBlank { UUID.randomUUID().toString() }, it.role, it.content)) }
            val payload = JSONObject().put("messages", history)
            if (!system.isNullOrBlank()) payload.put("system", system)

            var connection: HttpURLConnection? = null
            try {
                for (attempt in 0..1) {
                    connection = openJson("/api/chat", "POST", payload, forceRefresh = attempt == 1)
                    connection!!.setRequestProperty("Accept", "text/event-stream, application/json")
                    val status = connection!!.responseCode
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED && attempt == 0) {
                        connection!!.disconnect()
                        connection = null
                        continue
                    }
                    if (status !in 200..299) {
                        val raw = connection!!.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val body = runCatching { JSONObject(raw) }.getOrNull()
                        val detail = body?.optString("error").orEmpty()
                        throw IllegalStateException(when {
                            status == 402 || detail.contains("credit", true) -> "Your current usage allowance is unavailable for this request. Review plans and billing to continue."
                            status == 401 -> "Please sign in again to continue."
                            status == 403 && detail.contains("consent", true) -> "Please review and accept the required BQ Sqwad AI terms to continue."
                            status == 429 -> "BQ Sqwad AI is busy right now. Please wait a moment and try again."
                            else -> detail.ifBlank { "BQ Sqwad AI couldn't complete that request. Please try again." }
                        })
                    }
                    val builder = StringBuilder()
                    var providerId: String? = null
                    var modelId: String? = null
                    var charged = 0.0
                    val contentType = connection!!.contentType.orEmpty()
                    if (contentType.contains("text/event-stream", true)) {
                        val reader = connection!!.inputStream.bufferedReader()
                        var data = StringBuilder()
                        fun consume(raw: String) {
                            val value = raw.trim()
                            if (value.isEmpty() || value == "[DONE]") return
                            if (value.startsWith("0:")) {
                                val tokenText = runCatching { org.json.JSONTokener(value.substring(2)).nextValue() as? String }.getOrNull().orEmpty()
                                if (tokenText.isNotEmpty()) { builder.append(tokenText); onDelta(tokenText) }
                                return
                            }
                            val json = runCatching { JSONObject(value) }.getOrNull()
                            if (json == null) {
                                val stringValue = runCatching { org.json.JSONTokener(value).nextValue() as? String }.getOrNull()
                                if (!stringValue.isNullOrEmpty()) { builder.append(stringValue); onDelta(stringValue) }
                                return
                            }
                            when (json.optString("type")) {
                                "delta", "text-delta" -> {
                                    val delta = json.optString("text").ifBlank { json.optString("delta") }
                                    if (delta.isNotEmpty()) { builder.append(delta); onDelta(delta) }
                                }
                                "completed", "finish" -> {
                                    providerId = json.optString("providerId").takeIf { it.isNotBlank() }
                                    modelId = json.optString("modelId").takeIf { it.isNotBlank() }
                                    charged = json.optDouble("creditsCharged", json.optDouble("creditsUsed", 0.0))
                                    val finalText = json.optString("text")
                                    if (builder.isEmpty() && finalText.isNotBlank()) { builder.append(finalText); onDelta(finalText) }
                                }
                                "error" -> throw IllegalStateException(json.optString("message").ifBlank { "The AI request failed." })
                                else -> {
                                    val delta = json.optString("delta")
                                    if (delta.isNotEmpty()) { builder.append(delta); onDelta(delta) }
                                }
                            }
                        }
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) { consume(data.toString()); data = StringBuilder() }
                            else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").trimStart())
                            }
                        }
                        if (data.isNotEmpty()) consume(data.toString())
                    } else {
                        val raw = connection!!.inputStream.bufferedReader().use { it.readText() }
                        val body = runCatching { JSONObject(raw) }.getOrNull()
                            ?: throw IllegalStateException("The AI service returned an unexpected response.")
                        val answer = body.optString("text").ifBlank { body.optString("answer") }
                        if (answer.isNotBlank()) { builder.append(answer); onDelta(answer) }
                        providerId = body.optString("providerId").takeIf { it.isNotBlank() }
                        modelId = body.optString("modelId").takeIf { it.isNotBlank() }
                        charged = body.optDouble("creditsCharged", body.optDouble("creditsUsed", 0.0))
                    }
                    val answer = builder.toString().trim()
                    if (answer.isBlank()) throw IllegalStateException("The AI service returned an empty response.")
                    return@withContext ChatReply(answer, providerId, modelId, charged)
                }
                error("Sign in required.")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun uiMessage(id: String, role: String, content: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("role", role)
            .put("content", content)
            .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", content)))
}
