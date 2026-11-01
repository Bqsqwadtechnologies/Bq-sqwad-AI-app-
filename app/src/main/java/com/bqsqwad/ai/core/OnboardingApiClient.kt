package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Shared onboarding GET/POST against the website (Prompt H). */
class OnboardingApiClient {
    private fun authToken(): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        return Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
    }

    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        val token = authToken()
        val url = URL("${ApiConfig.websiteBaseUrl.trimEnd('/')}/api/native/onboarding")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            doInput = true
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = c.responseCode
            val raw = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("Could not load onboarding status.")
            JSONObject(raw.ifBlank { "{}" })
        } finally {
            c.disconnect()
        }
    }

    suspend fun submit(profile: OnboardingProfile): JSONObject = withContext(Dispatchers.IO) {
        val token = authToken()
        val url = URL("${ApiConfig.websiteBaseUrl.trimEnd('/')}/api/native/onboarding")
        val body = JSONObject().apply {
            put("age", profile.age)
            put("dateOfBirth", profile.dateOfBirth)
            put("purposes", JSONArray(profile.purposes))
            put("role", profile.role)
            put("roleOther", profile.roleOther)
            put("theme", profile.theme)
            put("version", profile.version)
        }
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 20000
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
                if (err == "age_blocked") error("age_blocked")
                error("Could not save onboarding.")
            }
            JSONObject(raw.ifBlank { "{\"ok\":true}" })
        } finally {
            c.disconnect()
        }
    }
}
