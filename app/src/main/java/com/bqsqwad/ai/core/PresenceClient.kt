package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** POST /presence/ping every 30s while foreground + signed in. */
class PresenceClient {
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: return@withContext false
        val token = Tasks.await(user.getIdToken(false)).token ?: return@withContext false
        val url = URL("${ApiConfig.baseUrl.trimEnd('/')}/presence/ping")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            doInput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use {
                it.write(JSONObject().put("platform", "android").toString().toByteArray())
            }
            c.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }
}
