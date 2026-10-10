package com.bqsqwad.ai.core

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.tasks.Task
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AccountDeletionClient {
    private fun endpoint(path: String): String = "${ApiConfig.baseUrl}$path"

    suspend fun deleteAccount(): JSONObject = post("/account/schedule-deletion")

    suspend fun cancelDeletion(): JSONObject = post("/account/cancel-deletion")

    private suspend fun post(path: String): JSONObject = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: error("No signed-in account")
        val token = awaitTask(user.getIdToken(true)).token ?: error("Authentication token unavailable")
        val connection = (URL(endpoint(path)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(
                runCatching { JSONObject(body).optString("message").ifBlank { JSONObject(body).optString("error") } }
                    .getOrDefault("Account request failed")
            )
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}

private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { continuation ->
    task.addOnSuccessListener { continuation.resume(it) }
    task.addOnFailureListener { continuation.resumeWithException(it) }
    task.addOnCanceledListener { continuation.cancel() }
}
