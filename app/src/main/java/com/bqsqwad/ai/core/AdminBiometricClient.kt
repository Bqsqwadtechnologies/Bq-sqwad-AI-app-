package com.bqsqwad.ai.core

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import android.util.Base64
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection
import java.net.URL

class AdminBiometricClient(private val context: Context) {
    private val alias = "bq_admin_biometric_v1"
    private val baseUrl = "https://europe-west1-" + FirebaseApp.getInstance().options.projectId + ".cloudfunctions.net/bqSqwadApi"

    private suspend fun request(path: String, method: String = "POST", payload: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
            setRequestProperty("Authorization", "Bearer $token")
            if (method != "GET") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (method != "GET") connection.outputStream.use { it.write(payload.toString().toByteArray()) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(raw)
        if (status !in 200..299) error(json.optString("error", "Biometric request failed"))
        json
    }

    fun isEnrolled(): Boolean = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.containsAlias(alias)
    } catch (_: Throwable) { false }

    fun createKeyPair() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN
        ).setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(30)
        }
        generator.initialize(builder.build())
        generator.generateKeyPair()
    }

    fun publicKeyBase64(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val certificate = ks.getCertificate(alias) ?: error("Admin biometric key is not enrolled")
        return Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    suspend fun register(): JSONObject {
        createKeyPair()
        return request("/admin/security/biometric/register", "POST", JSONObject().put("publicKey", publicKeyBase64()))
    }

    suspend fun beginChallenge(): JSONObject = request("/admin/security/biometric/challenge")

    suspend fun signChallengeWithBiometric(activity: FragmentActivity, challengeBase64: String): String {
        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                        val key = ks.getKey(alias, null) as? java.security.PrivateKey ?: error("Admin biometric key is unavailable")
                        val signature = Signature.getInstance("SHA256withECDSA")
                        signature.initSign(key)
                        signature.update(Base64.decode(challengeBase64, Base64.DEFAULT))
                        continuation.resume(Base64.encodeToString(signature.sign(), Base64.NO_WRAP))
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    continuation.resumeWithException(IllegalStateException(errString.toString()))
                }
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Admin biometric unlock")
                    .setSubtitle("Use your enrolled fingerprint or biometric")
                    .setNegativeButtonText("Cancel")
                    .build()
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    suspend fun unlock(activity: FragmentActivity): JSONObject {
        val challenge = beginChallenge()
        val signature = signChallengeWithBiometric(activity, challenge.getString("challenge"))
        return request(
            "/admin/security/biometric/verify",
            "POST",
            JSONObject().put("challengeId", challenge.getString("challengeId")).put("signature", signature)
        )
    }

    suspend fun remove(): JSONObject {
        val result = request("/admin/security/biometric", "DELETE")
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
        return result
    }
}
