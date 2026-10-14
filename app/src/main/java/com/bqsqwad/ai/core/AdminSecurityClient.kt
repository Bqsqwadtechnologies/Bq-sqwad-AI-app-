package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AdminSecurityStatus(
    val passwordConfigured: Boolean,
    val totpConfigured: Boolean,
    val biometricConfigured: Boolean,
    val encryptionKeyConfigured: Boolean,
    val unlockRequired: Boolean = false
)

class AdminSecurityClient(
    private val baseUrl: String = ApiConfig.baseUrl
) {
    suspend fun status(): AdminSecurityStatus {
        val o = request("GET", "/admin/security/status", null)
        return AdminSecurityStatus(
            passwordConfigured = o.optBoolean("passwordConfigured", false),
            totpConfigured = o.optBoolean("totpConfigured", false),
            biometricConfigured = o.optBoolean("biometricConfigured", false),
            encryptionKeyConfigured = o.optBoolean("encryptionKeyConfigured", false),
            unlockRequired = o.optBoolean("unlockRequired", false)
        )
    }

    suspend fun setPassword(password: String) {
        request("POST", "/admin/security/password", JSONObject().put("password", password))
    }

    suspend fun startTotp(): JSONObject = request("POST", "/admin/security/totp/start", JSONObject())

    suspend fun confirmTotp(code: String) {
        request("POST", "/admin/security/totp/confirm", JSONObject().put("code", code))
    }

    suspend fun sessionStatus(): JSONObject = request("GET", "/admin/security/session", null)

    suspend fun unlock(password: String, code: String) {
        request("POST", "/admin/security/unlock", JSONObject().apply {
            put("password", password)
            put("code", code)
        })
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.let { Tasks.await(it) }
    }

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val user = FirebaseAuth.getInstance().currentUser ?: error("Admin authentication required")
            val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 30000
                doInput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toString().toByteArray()) }
                }
            }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
            if (status !in 200..299) {
                val serverError = runCatching { JSONObject(raw).optString("error") }.getOrDefault("Admin security request failed")
                val friendly = if (serverError == "admin_session_required") {
                    "The admin unlock session has expired. Unlock Admin Security and try again."
                } else serverError
                throw IllegalStateException(friendly)
            }
            runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        }
}
