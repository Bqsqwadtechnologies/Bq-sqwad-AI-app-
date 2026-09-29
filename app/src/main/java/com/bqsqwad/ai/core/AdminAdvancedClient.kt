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
    val section: String = "system",
    val enabled: Boolean = true,
    val rolloutPercent: Int = 100,
    val killSwitch: Boolean = false,
    val requiresApproval: Boolean = false,
    val creditCost: Long = 0,
    val dailyLimit: Long? = null,
    val concurrencyLimit: Long? = null,
    val providerCapabilityId: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val allowedPlanIds: List<String> = emptyList(),
    val version: Int = 1,
    val schedule: String? = null,
    val alertThreshold: Double? = null,
    val config: AdminOperationalConfig = AdminOperationalConfig()
)

data class AdminOperationalConfig(
    val responseMaxTokens: Long? = null,
    val contextWindowLimit: Long? = null,
    val maxToolCalls: Long = 10,
    val maxExecutionSeconds: Long = 120,
    val temperature: Double? = null,
    val confidenceRequired: Boolean = false,
    val sourceVerificationRequired: Boolean = false,
    val systemPromptVersion: String? = null,
    val rateLimitPerMinute: Long? = null,
    val rateLimitPerDay: Long? = null,
    val maintenanceMode: Boolean = false,
    val backgroundAllowed: Boolean = false,
    val confirmationRequired: Boolean = false,
    val permissionRequired: Boolean = false,
    val dataRetentionDays: Long? = null,
    val fileRetentionHours: Long? = null,
    val domainAllowList: List<String> = emptyList(),
    val domainDenyList: List<String> = emptyList(),
    val fallbackEnabled: Boolean = true,
    val fallbackProviderIds: List<String> = emptyList(),
    val latencyBudgetMs: Long? = null,
    val errorThresholdPercent: Double? = null,
    val queueLimit: Long? = null,
    val maxFileSizeMb: Long? = null,
    val maxUploadConcurrency: Long? = null,
    val notificationEnabled: Boolean = true,
    val auditRequired: Boolean = true,
    val creditsPerSearch: Double = 0.0,
    val creditsPerDeepResearch: Double = 0.0,
    val creditsPerResultPage: Double = 0.0,
    val maxResults: Long = 10,
    val dailyUserLimit: Long? = null
)

data class AdminPlayCatalogItem(
    val module:String,
    val name:String,
    val planId:String,
    val productId:String?,
    val billingPeriod:String,
    val basePlanId:String?,
    val priceUsd:Double?,
    val playEligible:Boolean,
    val contactSales:Boolean,
    val trialOfferId:String?,
    val trialDays:Int?,
    val limits:String,
    val quantityTiers:String
)

class AdminAdvancedClient(
    private val baseUrl: String = "https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev"
) {
    suspend fun list(): List<AdvancedAdminOption> = request("GET", "/admin/config", null).let { root ->
        parseOptions(root.optJSONArray("options") ?: JSONArray())
    }

    suspend fun playCatalog(): List<AdminPlayCatalogItem> = request("GET", "/admin/play-catalog", null).let { root ->
        val array = root.optJSONArray("ids") ?: JSONArray()
        (0 until array.length()).map { i ->
            val x = array.getJSONObject(i)
            AdminPlayCatalogItem(
                module = x.optString("module"),
                name = x.optString("name"),
                planId = x.optString("planId"),
                productId = x.optString("productId").takeIf { it.isNotBlank() },
                billingPeriod = x.optString("billingPeriod"),
                basePlanId = x.optString("basePlanId").takeIf { it.isNotBlank() },
                priceUsd = if (x.isNull("priceUsd")) null else x.optDouble("priceUsd"),
                playEligible = x.optBoolean("playEligible", false),
                contactSales = x.optBoolean("contactSales", false),
                trialOfferId = x.optString("trialOfferId").takeIf { it.isNotBlank() && it != "null" },
                trialDays = if (x.isNull("trialDays")) null else x.optInt("trialDays"),
                limits = x.optJSONObject("limits")?.toString() ?: "",
                quantityTiers = x.optJSONArray("quantityTiers")?.toString() ?: ""
            )
        }
    }

    suspend fun grantCredits(email: String, amount: Double, reason: String = "admin_grant"): JSONObject =
        request("POST", "/admin/credits/grant", JSONObject().apply {
            put("email", email.trim())
            put("amount", amount)
            put("reason", reason.trim().ifBlank { "admin_grant" })
        })

    suspend fun testMode(): Boolean =
        request("GET", "/admin/test-mode", null).optBoolean("enabled", false)

    suspend fun setTestMode(enabled: Boolean): Boolean =
        request("POST", "/admin/test-mode", JSONObject().put("enabled", enabled))
            .optJSONObject("option")?.optBoolean("enabled", enabled) ?: enabled

    suspend fun update(option: AdvancedAdminOption): AdvancedAdminOption {
        val body = JSONObject().apply {
            put("id", option.id)
            put("enabled", option.enabled)
            put("rolloutPercent", option.rolloutPercent)
            put("killSwitch", option.killSwitch)
            put("requiresApproval", option.requiresApproval)
            put("creditCost", option.creditCost)
            putNullable("dailyLimit", option.dailyLimit)
            putNullable("concurrencyLimit", option.concurrencyLimit)
            putNullable("providerCapabilityId", option.providerCapabilityId)
            putNullable("providerId", option.providerId)
            putNullable("modelId", option.modelId)
            put("allowedPlanIds", JSONArray(option.allowedPlanIds))
            put("version", option.version)
            putNullable("schedule", option.schedule)
            putNullable("alertThreshold", option.alertThreshold)
            put("config", option.config.toJson())
        }
        return parse(request("POST", "/admin/control", body).getJSONObject("option"))
    }

    private fun parseOptions(array: JSONArray): List<AdvancedAdminOption> =
        (0 until array.length()).map { parse(array.getJSONObject(it)) }

    private fun parse(o: JSONObject): AdvancedAdminOption {
        val c = o.optJSONObject("config") ?: JSONObject()
        return AdvancedAdminOption(
            id = o.optString("id"),
            label = o.optString("label"),
            section = o.optString("section", "system"),
            enabled = o.optBoolean("enabled", true),
            rolloutPercent = o.optInt("rolloutPercent", 100),
            killSwitch = o.optBoolean("killSwitch", false),
            requiresApproval = o.optBoolean("requiresApproval", false),
            creditCost = o.optLong("creditCost", 0),
            dailyLimit = cLong(o, "dailyLimit"),
            concurrencyLimit = cLong(o, "concurrencyLimit"),
            providerCapabilityId = o.optString("providerCapabilityId").takeIf { it.isNotBlank() && it != "null" },
            providerId = o.optString("providerId").takeIf { it.isNotBlank() && it != "null" },
            modelId = o.optString("modelId").takeIf { it.isNotBlank() && it != "null" },
            allowedPlanIds = stringList(o.optJSONArray("allowedPlanIds")),
            version = o.optInt("version", 1),
            schedule = o.optString("schedule").takeIf { it.isNotBlank() && it != "null" },
            alertThreshold = if (o.isNull("alertThreshold")) null else o.optDouble("alertThreshold"),
            config = AdminOperationalConfig(
                responseMaxTokens = cLong(c, "responseMaxTokens"),
                contextWindowLimit = cLong(c, "contextWindowLimit"),
                maxToolCalls = c.optLong("maxToolCalls", 10),
                maxExecutionSeconds = c.optLong("maxExecutionSeconds", 120),
                temperature = if (c.isNull("temperature")) null else c.optDouble("temperature"),
                confidenceRequired = c.optBoolean("confidenceRequired", false),
                sourceVerificationRequired = c.optBoolean("sourceVerificationRequired", false),
                systemPromptVersion = c.optString("systemPromptVersion").takeIf { it.isNotBlank() },
                rateLimitPerMinute = cLong(c, "rateLimitPerMinute"),
                rateLimitPerDay = cLong(c, "rateLimitPerDay"),
                maintenanceMode = c.optBoolean("maintenanceMode", false),
                backgroundAllowed = c.optBoolean("backgroundAllowed", false),
                confirmationRequired = c.optBoolean("confirmationRequired", false),
                permissionRequired = c.optBoolean("permissionRequired", false),
                dataRetentionDays = cLong(c, "dataRetentionDays"),
                fileRetentionHours = cLong(c, "fileRetentionHours"),
                domainAllowList = stringList(c.optJSONArray("domainAllowList")),
                domainDenyList = stringList(c.optJSONArray("domainDenyList")),
                fallbackEnabled = c.optBoolean("fallbackEnabled", true),
                fallbackProviderIds = stringList(c.optJSONArray("fallbackProviderIds")),
                latencyBudgetMs = cLong(c, "latencyBudgetMs"),
                errorThresholdPercent = if (c.isNull("errorThresholdPercent")) null else c.optDouble("errorThresholdPercent"),
                queueLimit = cLong(c, "queueLimit"),
                maxFileSizeMb = cLong(c, "maxFileSizeMb"),
                maxUploadConcurrency = cLong(c, "maxUploadConcurrency"),
                notificationEnabled = c.optBoolean("notificationEnabled", true),
                auditRequired = c.optBoolean("auditRequired", true),
                creditsPerSearch = c.optDouble("creditsPerSearch", 0.0),
                creditsPerDeepResearch = c.optDouble("creditsPerDeepResearch", 0.0),
                creditsPerResultPage = c.optDouble("creditsPerResultPage", 0.0),
                maxResults = c.optLong("maxResults", 10),
                dailyUserLimit = cLong(c, "dailyUserLimit")
            )
        )
    }

    private fun cLong(o: JSONObject, key: String): Long? =
        if (o.isNull(key) || !o.has(key)) null else o.optLong(key)

    private fun stringList(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun AdminOperationalConfig.toJson() = JSONObject().apply {
        putNullable("responseMaxTokens", responseMaxTokens)
        putNullable("contextWindowLimit", contextWindowLimit)
        put("maxToolCalls", maxToolCalls)
        put("maxExecutionSeconds", maxExecutionSeconds)
        putNullable("temperature", temperature)
        put("confidenceRequired", confidenceRequired)
        put("sourceVerificationRequired", sourceVerificationRequired)
        putNullable("systemPromptVersion", systemPromptVersion)
        putNullable("rateLimitPerMinute", rateLimitPerMinute)
        putNullable("rateLimitPerDay", rateLimitPerDay)
        put("maintenanceMode", maintenanceMode)
        put("backgroundAllowed", backgroundAllowed)
        put("confirmationRequired", confirmationRequired)
        put("permissionRequired", permissionRequired)
        putNullable("dataRetentionDays", dataRetentionDays)
        putNullable("fileRetentionHours", fileRetentionHours)
        put("domainAllowList", JSONArray(domainAllowList))
        put("domainDenyList", JSONArray(domainDenyList))
        put("fallbackEnabled", fallbackEnabled)
        put("fallbackProviderIds", JSONArray(fallbackProviderIds))
        putNullable("latencyBudgetMs", latencyBudgetMs)
        putNullable("errorThresholdPercent", errorThresholdPercent)
        putNullable("queueLimit", queueLimit)
        putNullable("maxFileSizeMb", maxFileSizeMb)
        putNullable("maxUploadConcurrency", maxUploadConcurrency)
        put("notificationEnabled", notificationEnabled)
        put("auditRequired", auditRequired)
        put("creditsPerSearch", creditsPerSearch)
        put("creditsPerDeepResearch", creditsPerDeepResearch)
        put("creditsPerResultPage", creditsPerResultPage)
        put("maxResults", maxResults)
        putNullable("dailyUserLimit", dailyUserLimit)
    }

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
