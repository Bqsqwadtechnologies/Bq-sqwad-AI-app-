package com.bqsqwad.ai.core

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PasskeyClient(private val context: Context) {
    private val baseUrl = "${ApiConfig.baseUrl}"
    private val credentialManager = CredentialManager.create(context)

    private suspend fun request(path: String, method: String = "GET", payload: JSONObject? = null, authenticated: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 120000
                doInput = true
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                if (authenticated) {
                    val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
                    val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            payload?.let { connection.outputStream.use { out -> out.write(it.toString().toByteArray()) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream.bufferedReader().use { it.readText() }
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", "Invalid server response") }
            if (status !in 200..299) error(json.optString("error", "Passkey request failed"))
            json
        }

    suspend fun status(): JSONObject = request("/security/passkeys", authenticated = true)

    suspend fun createPasskey(): JSONObject {
        val options = request("/security/passkeys/options", "POST", JSONObject(), authenticated = true)
        val response = credentialManager.createCredential(
            context,
            CreatePublicKeyCredentialRequest(
                requestJson = options.toString(),
                preferImmediatelyAvailableCredentials = false,
                isAutoSelectAllowed = false
            )
        )
        val registrationJson = (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: error("Passkey provider returned an unexpected response")
        return request(
            "/security/passkeys/verify",
            "POST",
            JSONObject().put("response", JSONObject(registrationJson)),
            authenticated = true
        )
    }

    suspend fun removePasskey(id: String): JSONObject {
        val encoded = java.net.URLEncoder.encode(id, Charsets.UTF_8.name())
        return request("/security/passkeys/$encoded", "DELETE", authenticated = true)
    }

    suspend fun signInWithPasskey(): JSONObject {
        val options = request("/passkey/login/options")
        val sessionId = options.getString("sessionId")
        val publicKeyOptions = options.getJSONObject("options")
        val result = credentialManager.getCredential(
            context,
            GetCredentialRequest(
                listOf(GetPublicKeyCredentialOption(requestJson = publicKeyOptions.toString())),
                preferImmediatelyAvailableCredentials = false
            )
        )
        val credential = result.credential as? PublicKeyCredential ?: error("No passkey credential returned")
        val verified = request(
            "/passkey/login/verify",
            "POST",
            JSONObject()
                .put("sessionId", sessionId)
                .put("response", JSONObject(credential.authenticationResponseJson))
        )
        val customToken = verified.optString("customToken")
        if (customToken.isBlank()) error("Passkey verification did not return a sign-in token")
        Tasks.await(FirebaseAuth.getInstance().signInWithCustomToken(customToken))
        return verified
    }
}
