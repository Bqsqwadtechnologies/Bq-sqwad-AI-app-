package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AdminProviderSecretStatus(
    val providerId: String,
    val configured: Boolean,
    val source: String,
    val updatedAt: String?
)

data class AdminSocialConfig(
    val enabled: Boolean,
    val xEnabled: Boolean,
    val xClientId: String,
    val xScopes: List<String>
)

data class AdminProviderRoute(
    val capability: String,
    val primaryProviderId: String?,
    val primaryModelId: String?,
    val fallbackProviderIds: List<String>,
    val enabled: Boolean
)

class AdminProviderClient(
    private val baseUrl: String = "${API_BASE_URL_PLACEHOLDER}"
) {
    suspend fun planCatalog(): JSONObject = request("GET", "/admin/plan-catalog", null)

    suspend fun savePlanCatalog(catalog: JSONObject): JSONObject =
        request("POST", "/admin/plan-catalog", JSONObject().put("catalog", catalog))

    suspend fun providerOverview(): Triple<List<AdminProviderSecretStatus>, List<AdminProviderRoute>, AdminSocialConfig> =
        request("GET", "/admin/providers", null).let { root ->
            val providers = root.optJSONArray("providers") ?: JSONArray()
            val statuses = (0 until providers.length()).map { i ->
                val o = providers.getJSONObject(i)
                AdminProviderSecretStatus(
                    providerId = o.optString("providerId"),
                    configured = o.optBoolean("configured", false),
                    source = o.optString("source", "unknown"),
                    updatedAt = o.optString("updatedAt").takeIf { it.isNotBlank() && it != "null" }
                )
            }
            val social = root.optJSONObject("social") ?: JSONObject()
            val x = social.optJSONObject("x") ?: JSONObject()
            val socialConfig = AdminSocialConfig(
                enabled = social.optBoolean("enabled", false),
                xEnabled = x.optBoolean("enabled", false),
                xClientId = x.optString("clientId", ""),
                xScopes = (x.optJSONArray("scopes") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                }
            )
            val routeArray = root.optJSONArray("routes") ?: JSONArray()
            val routes = (0 until routeArray.length()).map { i ->
                val o = routeArray.getJSONObject(i)
                val fallback = o.optJSONArray("fallbackProviderIds") ?: JSONArray()
                AdminProviderRoute(
                    capability = o.optString("capability"),
                    primaryProviderId = o.optString("primaryProviderId").takeIf { it.isNotBlank() && it != "null" },
                    primaryModelId = o.optString("primaryModelId").takeIf { it.isNotBlank() && it != "null" },
                    fallbackProviderIds = (0 until fallback.length()).map { fallback.optString(it) }.filter { it.isNotBlank() },
                    enabled = o.optBoolean("enabled", true)
                )
            }
            Triple(statuses, routes, socialConfig)
        }

    suspend fun statuses(): List<AdminProviderSecretStatus> = providerOverview().first

    suspend fun cloudflareSecretStatus(): JSONObject =
        request("GET", "/admin/cloudflare/secrets", null)

    suspend fun saveRoute(
        capability: String,
        primaryProviderId: String,
        primaryModelId: String?,
        fallbackProviderIds: List<String>,
        enabled: Boolean = true
    ) {
        request("POST", "/admin/route", JSONObject().apply {
            put("capabilityId", capability)
            put("primaryProviderId", primaryProviderId)
            if (primaryModelId.isNullOrBlank()) put("primaryModelId", JSONObject.NULL)
            else put("primaryModelId", primaryModelId)
            put("fallbackProviderIds", JSONArray(fallbackProviderIds))
            put("enabled", enabled)
        })
    }

    suspend fun saveSocialConfig(enabled: Boolean, xEnabled: Boolean, xClientId: String, xScopes: List<String>) {
        request("POST", "/admin/social-integrations", JSONObject().apply {
            put("enabled", enabled)
            put("platforms", JSONObject().put("x", JSONObject().apply {
                put("enabled", xEnabled)
                put("clientId", xClientId.trim())
                put("scopes", JSONArray(xScopes))
            }))
        })
    }

    suspend fun save(providerId: String, secret: String) {
        request("POST", "/admin/provider-secret", JSONObject().apply {
            put("providerId", providerId)
            put("secretValue", secret)
        })
    }

    suspend fun remove(providerId: String) {
        request("POST", "/admin/provider-secret", JSONObject().apply {
            put("providerId", providerId)
            put("remove", true)
        })
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
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) {
                throw IllegalStateException(runCatching { JSONObject(raw).optString("error") }.getOrDefault("Admin provider request failed"))
            }
            runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        }
}
