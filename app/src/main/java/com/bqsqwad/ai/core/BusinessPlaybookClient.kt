package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Business AI playbooks + integrations against website native API. */
class BusinessPlaybookClient {
    private fun authToken(): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        return Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
    }

    suspend fun catalog(workspaceId: String = "default"): JSONObject = withContext(Dispatchers.IO) {
        val token = authToken()
        val url = URL(
            "${ApiConfig.websiteBaseUrl.trimEnd('/')}/api/native/business-playbooks?workspaceId=" +
                java.net.URLEncoder.encode(workspaceId, Charsets.UTF_8.name())
        )
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            doInput = true
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = c.responseCode
            val raw = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("Could not load Business AI playbooks.")
            JSONObject(raw.ifBlank { "{}" })
        } finally {
            c.disconnect()
        }
    }

    suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val token = authToken()
        val url = URL("${ApiConfig.websiteBaseUrl.trimEnd('/')}/api/native/business-playbooks")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = c.responseCode
            val raw = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val err = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                error(err.ifBlank { "Business AI request failed." })
            }
            JSONObject(raw.ifBlank { "{}" })
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun playbookTitles(body: JSONObject): List<Pair<String, String>> {
            val arr = body.optJSONArray("playbooks") ?: JSONArray()
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.optString("id") to o.optString("title"))
                }
            }
        }
    }
}
