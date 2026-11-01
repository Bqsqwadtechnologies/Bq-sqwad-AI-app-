package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads multi-plan entitlements from the website (Prompt I).
 * Source of truth: GET {websiteBaseUrl}/api/native/entitlements
 */
class EntitlementsClient {
    private fun authToken(): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        return Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
    }

    suspend fun fetchEntitlements(): JSONObject = withContext(Dispatchers.IO) {
        val token = authToken()
        val url = URL("${ApiConfig.websiteBaseUrl.trimEnd('/')}/api/native/entitlements")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            doInput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Could not load plans.")
            }
            JSONObject(raw.ifBlank { "{\"items\":[]}" })
        } finally {
            connection.disconnect()
        }
    }

    suspend fun fetchMeWithPlans(): JSONObject = withContext(Dispatchers.IO) {
        val token = authToken()
        val url = URL("${ApiConfig.websiteBaseUrl.trimEnd('/')}/api/native/me")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            doInput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Could not load account.")
            }
            JSONObject(raw.ifBlank { "{}" })
        } finally {
            connection.disconnect()
        }
    }
}
