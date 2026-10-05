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
    private fun endpoint(): String {
        val projectId = com.google.firebase.FirebaseApp.getInstance().options.projectId
            ?: error("Firebase project ID is unavailable")
        return "${ApiConfig.baseUrl}/account"
    }

    suspend fun deleteAccount(): JSONObject = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: error("No signed-in account")
        val token = awaitTask(user.getIdToken(true)).token ?: error("Authentication token unavailable")
        val connection = (URL(endpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(
                runCatching { JSONObject(body).optString("error") }.getOrDefault("Account deletion failed")
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
