package com.bqsqwad.ai.core

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AdvancedAdminOption(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val rolloutPercent: Int = 100,
    val killSwitch: Boolean = false,
    val requiresApproval: Boolean = false,
    val creditCost: Long = 0,
    val providerId: String? = null,
    val modelId: String? = null
)

class AdminAdvancedClient(
    private val baseUrl: String = "https://europe-west1-${FirebaseApp.getInstance().options.projectId}.cloudfunctions.net/bqSqwadApi"
) {
    suspend fun list(): List<AdvancedAdminOption> = request("GET", "/admin/advanced-options", null).let { root ->
        val array = root.optJSONArray("options") ?: JSONArray()
        (0 until array.length()).map { parse(array.getJSONObject(it)) }
    }

    suspend fun update(option: AdvancedAdminOption): AdvancedAdminOption {
        val body = JSONObject().apply {
            put("id", option.id)
            put("enabled", option.enabled)
            put("rolloutPercent", option.rolloutPercent)
            put("killSwitch", option.killSwitch)
            put("requiresApproval", option.requiresApproval)
            put("creditCost", option.creditCost)
            put("providerId", option.providerId)
            put("modelId", option.modelId)
        }
        return parse(request("POST", "/admin/advanced-option", body).getJSONObject("option"))
    }

    private fun parse(o: JSONObject) = AdvancedAdminOption(
        id = o.optString("id"),
        label = o.optString("label"),
        enabled = o.optBoolean("enabled", true),
        rolloutPercent = o.optInt("rolloutPercent", 100),
        killSwitch = o.optBoolean("killSwitch", false),
        requiresApproval = o.optBoolean("requiresApproval", false),
        creditCost = o.optLong("creditCost", 0),
        providerId = o.optString("providerId").takeIf { it.isNotBlank() && it != "null" },
        modelId = o.optString("modelId").takeIf { it.isNotBlank() && it != "null" }
    )

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.let { Tasks.await(it).token }
            ?: error("Admin authentication required")
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            doInput = true
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) error(JSONObject(text).optString("error", "Admin request failed"))
        JSONObject(text)
    }
}
