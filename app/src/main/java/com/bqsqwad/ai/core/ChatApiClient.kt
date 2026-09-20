package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatReply(
    val text: String,
    val providerId: String?,
    val modelId: String?,
    val creditsCharged: Double
)

class ChatApiClient(
    private val baseUrl: String = "https://europe-west1-" +
        FirebaseApp.getInstance().options.projectId + ".cloudfunctions.net/bqSqwadApi"
) {
    suspend fun sendMessage(message: String): ChatReply = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
        val connection = (URL("$baseUrl/ai").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 120000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use {
            it.write(JSONObject().apply {
                put("capability", "chat")
                put("message", message)
            }.toString().toByteArray())
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        val body = runCatching { JSONObject(raw) }.getOrElse {
            throw IllegalStateException("The AI service returned an invalid response.")
        }
        if (status !in 200..299) throw IllegalStateException(body.optString("error", "AI request failed"))
        val text = body.optString("text").trim()
        if (text.isBlank()) throw IllegalStateException("The AI service returned an empty response.")
        ChatReply(
            text = text,
            providerId = body.optString("providerId").takeIf { it.isNotBlank() },
            modelId = body.optString("modelId").takeIf { it.isNotBlank() },
            creditsCharged = body.optDouble("creditsCharged", 0.0)
        )
    }
}
