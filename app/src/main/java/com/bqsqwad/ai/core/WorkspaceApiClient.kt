package com.bqsqwad.ai.core

import com.google.android.gms.tasks.Tasks
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SearchSource(val title:String,val url:String,val snippet:String?)
data class SearchReply(val sources:List<SearchSource>,val creditsCharged:Double,val answer:String? = null)

class WorkspaceRedirectException : IllegalStateException("workspace_redirected")

object WorkspaceRedirectStore {
    private val _redirect = MutableStateFlow<JSONObject?>(null)
    val redirect: StateFlow<JSONObject?> = _redirect.asStateFlow()

    fun publish(value: JSONObject) { _redirect.value = JSONObject(value.toString()) }
    fun clear() { _redirect.value = null }
}

class WorkspaceApiClient {
    private val baseUrl = "${ApiConfig.baseUrl}"

    private fun connectionUnavailableMessage(): String =
        "No internet connection. Please turn on Wi-Fi or mobile data, then try again."

    private fun hasUsableNetwork(): Boolean {
        return runCatching {
            val context = FirebaseApp.getInstance().applicationContext
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(true)
    }

    private fun isConnectionFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current is IOException
            ) return true
            current = current.cause
        }
        return false
    }

    private fun ensureNetworkAvailable() {
        if (!hasUsableNetwork()) error(connectionUnavailableMessage())
    }

    private suspend fun publicRequest(path:String, method:String="GET"):JSONObject = withContext(Dispatchers.IO) {
        ensureNetworkAvailable()
        val statusAndBody = try {
            val c=(URL(baseUrl+path).openConnection() as HttpURLConnection).apply {
                requestMethod=method; connectTimeout=4500; readTimeout=6500; doInput=true
            }
            try {
                val status=c.responseCode
                val raw=(if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
                status to raw
            } finally {
                c.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (isConnectionFailure(failure)) throw IllegalStateException(connectionUnavailableMessage(), failure)
            error("BQ Sqwad AI is temporarily unavailable. The BQ Sqwad AI team is working on it. Please try again shortly.")
        }
        val (status, raw) = statusAndBody
        if(status !in 200..299) error(
            if (status >= 500) "BQ Sqwad AI is temporarily unavailable. The team is working on it. Please try again shortly."
            else "BQ Sqwad AI couldn't load this information right now. Please try again."
        )
        runCatching { JSONObject(raw) }.getOrElse {
            error("BQ Sqwad AI received an unexpected response. Please try again shortly.")
        }
    }

    private suspend fun request(path:String, method:String="POST", payload:JSONObject=JSONObject()):JSONObject = withContext(Dispatchers.IO) {
        ensureNetworkAvailable()
        val user=FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        val token=Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
        val statusAndRaw = try {
            val c=(URL(baseUrl+path).openConnection() as HttpURLConnection).apply {
                requestMethod=method; connectTimeout=if (path.startsWith("/file-box")) 15000 else 6500; readTimeout=if (path == "/ai" || path == "/ai/stream" || path == "/ai/vision" || path == "/web-search") 120000 else if (path.startsWith("/file-box")) 45000 else 10000; doInput=true
                if(method!="GET"){doOutput=true;setRequestProperty("Content-Type","application/json")}
                setRequestProperty("Authorization","Bearer $token")
            }
            try {
                if(method!="GET") c.outputStream.use{it.write(payload.toString().toByteArray())}
                val status=c.responseCode
                val stream=if(status in 200..299)c.inputStream else c.errorStream
                val raw=stream?.bufferedReader()?.use{it.readText()}.orEmpty()
                status to raw
            } finally {
                c.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (isConnectionFailure(failure)) throw IllegalStateException(connectionUnavailableMessage(), failure)
            error("BQ Sqwad AI is temporarily unavailable. The BQ Sqwad AI team is working on it. Please try again shortly.")
        }
        val (status, raw) = statusAndRaw
        val body=runCatching{JSONObject(raw)}.getOrNull()
        if(status !in 200..299) {
            val serverError = body?.optString("error").orEmpty().lowercase()
            val friendly = when {
                serverError == "web_search_not_configured" ->
                    "Web Search is unavailable right now because the search provider is not configured."
                serverError == "openai_provider_not_configured" ->
                    "Live Voice and AI Live Camera are unavailable because their AI provider is not configured yet. Please try another feature or contact support."
                serverError == "live_voice_disabled" ->
                    "Live Voice is currently disabled. Try again later or use another AI feature."
                serverError == "realtime_session_failed" -> {
                    val providerStatus = body?.optInt("providerStatus", 0) ?: 0
                    if (providerStatus > 0) "The live AI connection could not be created (provider status $providerStatus). Please try again later."
                    else "The live AI connection could not be created. Please try again later."
                }
                serverError == "realtime_client_secret_missing" ->
                    "The live AI connection could not be secured. Please try again later."
                serverError == "token_pricing_not_configured" ->
                    "Token billing is selected for this model, but its provider token prices are not configured in the admin dashboard."
                serverError == "provider_usage_unavailable" ->
                    "The AI provider did not return usage details, so this request was not charged. Please try again or contact support."
                serverError == "media_provider_cost_not_configured" ->
                    "The selected image or video model needs a valid provider cost and billing unit in the admin dashboard."
                serverError.startsWith("learning_feature_locked") ->
                    "That Learning feature is not included in the currently active plan. Review Learning plans to unlock it."
                serverError.startsWith("daily_capability_limit_reached") ->
                    "You have reached the daily Learning allowance. Upgrade the plan or try again tomorrow."
                serverError.startsWith("monthly_capability_limit_reached") ->
                    "This plan has reached its monthly usage limit. Review the plan details to continue."
                serverError == "truthguard_monthly_limit_reached" ->
                    "You have reached the monthly TruthGuard check limit. Upgrade the plan or try again next month."
                serverError == "life_mathis_monthly_limit_reached" ->
                    "You have reached the Life Mathis conversation limit for this month. Review plans to continue."
                serverError == "frontier_plan_required" ->
                    "Frontier Lab requires an active Frontier subscription. Review Frontier plans to continue."
                serverError == "learning_studio_daily_limit_reached" ->
                    "You have used today's 3 included Learning Studio lessons. Upgrade your Learning plan or try again tomorrow."
                serverError == "workspace_plan_route_required" ->
                    "This workspace feature must be opened from its own workspace so the correct plan and usage limits can be checked."
                serverError == "companion_memory_plan_required" ->
                    "Companion Memory is included with Companion Basic and higher plans. Review Companion plans to unlock it."
                serverError == "organization_plan_required" ->
                    "Activate an Organization plan before using this workspace feature."
                serverError == "organization_insufficient_balance" ->
                    "Your Organization usage wallet does not have enough balance. Add funds in Organization Billing and retry."
                serverError.startsWith("organization_feature_locked") ->
                    "This feature is not included in the active Organization plan. Review the plan features to continue."
                serverError.startsWith("capability_disabled") ->
                    "This AI feature has been temporarily disabled by the administrator. Please try again later."
                serverError == "business_plan_entitlement_required" ->
                    "A verified Business plan is required for this workspace action. Review Business plans to continue."
                serverError.startsWith("business_feature_locked") ->
                    "This tool is not included in the active Business plan. Upgrade to the plan that includes it."
                serverError.startsWith("business_limit_reached") ->
                    "This Business plan has reached its included capacity. Upgrade the plan or remove an existing item."
                serverError == "business_permission_denied" ->
                    "Your Business workspace role does not allow this action. Ask the workspace owner or an administrator to update your access."
                serverError == "business_member_role_invalid" || serverError == "business_owner_role_immutable" ->
                    "That workspace role change is not allowed. The owner role cannot be reassigned, and only the owner can promote an administrator."
                serverError == "insufficient_credits" || status == 402 || serverError.contains("insufficient credits") ->
                    "Your current usage allowance is unavailable for this request. Review plans and billing to continue."
                serverError == "consent_required" || status == 403 && serverError.contains("consent") ->
                    "Please review and accept the required BQ Sqwad AI terms to continue."
                serverError == "route_not_migrated" ->
                    "This feature is not connected to the current server yet. Please contact support."
                status >= 500 ->
                    "BQ Sqwad AI is experiencing a temporary issue. The BQ Sqwad AI team is working on it. Please try again shortly."
                serverError == "admin_session_required" ->
                    "The admin unlock session has expired. Open Admin Security and unlock again."
                status == 401 || serverError == "auth_required" ->
                    "Please sign in again to continue."
                status == 402 || serverError.contains("insufficient credits") ->
                    "Your current usage allowance is unavailable for this request. Review plans and billing to continue."
                status == 403 || serverError.contains("consent") ->
                    "Please review and accept the required BQ Sqwad AI terms to continue."
                status == 429 || serverError.contains("rate_limited") ->
                    "BQ Sqwad AI is busy right now. Please wait a moment and try again."
                else -> "BQ Sqwad AI couldn't complete that request. Please try again."
            }
            error(friendly)
        }
        if(body == null) {
            error("BQ Sqwad AI received an unexpected response. The BQ Sqwad AI team is working on it.")
        }
        // Learning renders its redirect inline; all other workspaces use the shared
        // navigation card so dedicated API routes cannot silently drop redirect data.
        if (path != "/learning/session") body.optJSONObject("redirect")?.let { WorkspaceRedirectStore.publish(it) }
        body
    }

    suspend fun search(query:String,deepResearch:Boolean=false):SearchReply{
        val b=request("/web-search",payload=JSONObject().put("query",query).put("deepResearch",deepResearch))
        val a=b.optJSONArray("sources") ?: JSONArray()
        return SearchReply(List(a.length()){i->val x=a.getJSONObject(i);SearchSource(x.optString("title"),x.optString("url"),x.optString("snippet").ifBlank{null})},b.optDouble("creditsCharged",0.0),b.optString("answer").takeIf{it.isNotBlank()})
    }

    suspend fun featureSettings():JSONObject = request("/feature-settings","GET")

    suspend fun updateFeatureSettings(workspaceId:String, settings:JSONObject):JSONObject =
        request("/feature-settings", payload=JSONObject().put("workspaceId", workspaceId).put("settings", settings))

    suspend fun memory():JSONObject = request("/memory","GET")

    suspend fun remember(text:String, source:String="chat"):JSONObject =
        request("/memory",payload=JSONObject().put("text",text.take(4000)).put("source",source.take(40)))

    suspend fun setMemoryEnabled(enabled:Boolean):JSONObject =
        request("/memory/settings",payload=JSONObject().put("enabled",enabled))

    suspend fun companionMemory():JSONObject = request("/companion/memory","GET")

    suspend fun setCompanionMemoryEnabled(enabled:Boolean):JSONObject =
        request("/companion/memory",payload=JSONObject().put("enabled",enabled))

    suspend fun clearMemory():JSONObject = request("/memory","DELETE")

    suspend fun realtimeSession(voice: String? = null, organization: Boolean = false, mode: String = "voice", modelRegistryId: String? = null):JSONObject = request("/realtime/session", payload=JSONObject().apply { voice?.trim()?.takeIf { it.isNotEmpty() }?.let { put("voice", it) }; put("mode", mode); modelRegistryId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("modelRegistryId", it) }; if (organization) put("organization", true) })
    suspend fun realtimeHeartbeat(mode: String = "voice", modelRegistryId: String? = null): JSONObject = request("/realtime/heartbeat", payload=JSONObject().apply { put("mode", mode); modelRegistryId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("modelRegistryId", it) } })
    suspend fun reportLiveSessionTimeline(mode: String, status: String, code: String = ""): JSONObject {
        val route = when (mode.lowercase()) {
            "camera" -> "/live-camera"
            "screen" -> "/screen-share"
            else -> "/live-voice"
        }
        val safeStatus = status.lowercase().takeIf { it in setOf("failed", "short", "error", "stopped") } ?: "failed"
        val safeCode = code.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.take(80)
        return request("/issues/report", payload = JSONObject().apply {
            put("route", route)
            put("status", safeStatus)
            put("code", safeCode)
        })
    }

    suspend fun livePricing(): JSONObject = request("/live/pricing", "GET")

    suspend fun transcribeAudioWav(wavBytes: ByteArray, durationSeconds: Int): String = withContext(Dispatchers.IO) {
        require(wavBytes.size in 44..4_000_000) { "The recording is empty or too large. Record a shorter message and retry." }
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required to transcribe audio.")
        val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable. Sign in again.")
        val connection = (URL(baseUrl + "/ai/transcribe").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 120_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "audio/wav")
            setRequestProperty("X-BQ-Audio-Duration-Seconds", durationSeconds.coerceIn(1, 120).toString())
        }
        val statusAndBody = try {
            connection.outputStream.use { it.write(wavBytes) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            status to raw
        } finally {
            connection.disconnect()
        }
        val (status, raw) = statusAndBody
        val body = runCatching { JSONObject(raw) }.getOrNull()
        if (status !in 200..299) {
            val code = body?.optString("error").orEmpty().lowercase()
            error(when {
                code == "out_of_credits" || code == "insufficient_credits" || status == 402 -> "Your current usage allowance is unavailable for dictation. Review plans and billing to continue."
                code == "consent_required" -> "Please review and accept the required BQ Sqwad AI terms to use dictation."
                code == "dictation_disabled" -> "Dictation is temporarily unavailable. Try again later."
                status >= 500 -> "Transcription is temporarily unavailable. Check your internet connection and retry."
                status == 401 -> "Please sign in again to transcribe your recording."
                else -> "The recording could not be transcribed. Try again."
            })
        }
        val transcript = body?.optString("text").orEmpty().trim()
        if (transcript.isBlank()) error("No speech was heard. Speak clearly and try again.")
        transcript
    }

    suspend fun adminFileBoxRetention(): JSONObject = request("/admin/file-box-retention", "GET")
    suspend fun saveAdminFileBoxRetention(days: Int): JSONObject =
        request("/admin/file-box-retention", payload = JSONObject().put("defaultRetentionDays", days))

    suspend fun adminWorkspaceScopeGuards(): JSONObject = request("/admin/workspace-scope-guards", "GET")

    suspend fun saveAdminWorkspaceScopeGuards(enabledByWorkspace: JSONObject): JSONObject =
        request("/admin/workspace-scope-guards", payload = JSONObject().put("enabledByWorkspace", enabledByWorkspace))

    suspend fun adminLivePricing(feature: String): JSONObject {
        val path = when (feature.lowercase()) {
            "voice", "live_voice", "live-voice" -> "/admin/live-voice-pricing"
            "camera", "live_camera", "live-camera" -> "/admin/live-camera-pricing"
            "dictation" -> "/admin/dictation-pricing"
            else -> throw IllegalArgumentException("Unknown live pricing feature")
        }
        return request(path, "GET")
    }

    suspend fun saveAdminLivePricing(feature: String, enabled: Boolean, killSwitch: Boolean, config: JSONObject): JSONObject {
        val path = when (feature.lowercase()) {
            "voice", "live_voice", "live-voice" -> "/admin/live-voice-pricing"
            "camera", "live_camera", "live-camera" -> "/admin/live-camera-pricing"
            "dictation" -> "/admin/dictation-pricing"
            else -> throw IllegalArgumentException("Unknown live pricing feature")
        }
        return request(path, payload = JSONObject().put("enabled", enabled).put("killSwitch", killSwitch).put("config", config))
    }

    suspend fun chargeChatBackground(uploadId: String): JSONObject =
        request("/chat-background/charge", payload = JSONObject().put("uploadId", uploadId.take(100)))

    suspend fun getChatBackground(): JSONObject = request("/chat-background", "GET")

    suspend fun saveChatBackground(uploadId: String, url: String, brightness: Float): JSONObject =
        request("/chat-background/charge", payload = JSONObject().apply {
            put("uploadId", uploadId.take(100))
            put("url", url)
            put("brightness", brightness.toDouble())
        })

    suspend fun uploadFileBoxAsset(title: String, fileName: String, contentType: String, bytes: ByteArray): JSONObject = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Choose a non-empty file." }
        require(bytes.size <= 25 * 1024 * 1024) { "Files must be 25 MB or smaller." }
        val metadata = request("/file-box/upload-url", payload = JSONObject().apply {
            put("title", title.take(180))
            put("fileName", fileName.take(160))
            put("contentType", contentType.take(120))
            put("size", bytes.size)
        })
        val id = metadata.optString("id")
        if (id.isBlank()) error("Secure upload could not be prepared.")
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
        val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        val path = "/file-box/upload?id=" + java.net.URLEncoder.encode(id, "UTF-8")
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 120000
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-BQ-Content-SHA1", sha1)
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val message = (connection.errorStream?.bufferedReader()?.use { it.readText() }).orEmpty()
                val reason = runCatching { JSONObject(message).optString("error") }.getOrDefault("")
                error(reason.ifBlank { "File upload failed. Please retry." })
            }
        } finally {
            connection.disconnect()
        }
        request("/file-box/finalize", payload = JSONObject().put("id", id))
    }

    suspend fun downloadFileBoxAsset(id: String): ByteArray = withContext(Dispatchers.IO) {
        ensureNetworkAvailable()
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
        val path = "/file-box/download?id=" + java.net.URLEncoder.encode(id, "UTF-8")
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 60000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error(runCatching { JSONObject(message).optString("error") }.getOrDefault("File download failed."))
            }
            if (connection.contentLengthLong > 25L * 1024L * 1024L) error("This file is too large to download in the app.")
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > 25L * 1024L * 1024L) error("This file is too large to download in the app.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun fileBox(): JSONObject = request("/file-box", "GET")
    suspend fun saveFileBoxItem(title: String, content: String, mimeType: String = "text/plain"): JSONObject =
        request("/file-box", payload = JSONObject().put("title", title).put("content", content).put("mimeType", mimeType))
    suspend fun deleteFileBoxItem(id: String): JSONObject =
        request("/file-box?id=" + java.net.URLEncoder.encode(id, "UTF-8"), "DELETE")
    suspend fun setFileBoxRetentionDays(days: Int): JSONObject =
        request("/file-box/settings", payload = JSONObject().put("retentionDays", days))
    suspend fun projects(): JSONObject = request("/projects", "GET")
    suspend fun saveProject(id: String? = null, title: String, description: String = "", content: String = ""): JSONObject =
        request("/projects", payload = JSONObject().apply {
            if (!id.isNullOrBlank()) put("id", id)
            put("title", title); put("description", description); put("content", content)
        })
    suspend fun deleteProject(id: String): JSONObject =
        request("/projects?id=" + java.net.URLEncoder.encode(id, "UTF-8"), "DELETE")

    suspend fun plans():JSONObject = request("/plans","GET")

    suspend fun playCatalog():JSONObject = request("/play/catalog","GET")

    suspend fun nativeCatalog():JSONObject = request("/native/catalog","GET")
    suspend fun adminAccess():JSONObject = request("/admin/access","GET")
    suspend fun registerNotificationToken(token:String):JSONObject = request("/notifications/token", payload=JSONObject().put("token", token).put("platform", "android"))
    suspend fun unregisterNotificationToken(token:String):JSONObject = request("/notifications/token", "DELETE", JSONObject().put("token", token))
    suspend fun adminIssues():JSONObject = request("/admin/issues","GET")
    suspend fun adminDiagnostics():JSONObject = request("/admin/diagnostics","GET")
    suspend fun adminReadinessSelfTest():JSONObject = request("/admin/self-test", payload=JSONObject())
    suspend fun reportIssue(route: String, code: String = "client_runtime_issue"): JSONObject = request("/issues/report", payload = JSONObject().put("route", route).put("code", code))

    suspend fun publicAppConfig():JSONObject = publicRequest("/public/app-config","GET")

    suspend fun adminAppLayout(): JSONObject = request("/admin/app-layout", "GET")

    suspend fun setAdminAppLayout(workspaceOrder: List<String>, workspaceEnabled: JSONObject, themeMode: String = "system"): JSONObject =
        request("/admin/app-layout", payload = JSONObject().apply {
            put("workspaceOrder", JSONArray(workspaceOrder))
            put("workspaceEnabled", workspaceEnabled)
            put("themeMode", themeMode)
        })
    suspend fun setAdminAuthBackgroundVideo(url:String, enabled:Boolean=true):JSONObject =
        request("/admin/auth-background-video", payload=JSONObject().put("url", url).put("enabled", enabled))
    suspend fun setAdminFeatureAnnouncement(id:String, title:String, body:String, feature:String, enabled:Boolean):JSONObject =
        request("/admin/feature-announcement", payload=JSONObject().apply {
            put("id", id); put("title", title); put("body", body); put("feature", feature); put("enabled", enabled)
        })
    suspend fun setAdminChatBackground(url:String, enabled:Boolean=true):JSONObject =
        request("/admin/chat-background", payload=JSONObject().put("url", url).put("enabled", enabled))
    suspend fun adminDailyFreeCredits(): JSONObject = request("/admin/daily-free-credits", "GET")
    suspend fun setAdminDailyFreeCredits(credits: Int, enabled: Boolean): JSONObject =
        request("/admin/daily-free-credits", payload = JSONObject().put("credits", credits).put("enabled", enabled))

    suspend fun adminStaff():JSONObject = request("/admin/staff","GET")
    suspend fun adminStaffRoles():JSONObject = request("/admin/staff/roles","GET")
    suspend fun setAdminStaff(uid:String, role:String, active:Boolean = true, permissions:List<String> = emptyList()):JSONObject =
        request("/admin/staff", payload=JSONObject().apply {
            put("uid", uid); put("role", role); put("active", active); put("permissions", JSONArray(permissions))
        })
    suspend fun removeAdminStaff(uid:String):JSONObject =
        request("/admin/staff", method="DELETE", payload=JSONObject().put("uid", uid))
    suspend fun broadcastAdminNotification(title:String, body:String, data:JSONObject = JSONObject()):JSONObject =
        request("/admin/notifications/broadcast", payload=JSONObject().apply {
            put("title", title); put("body", body); put("data", data)
        })

    suspend fun adminPlanCatalog():JSONObject = request("/admin/plan-catalog","GET")
    suspend fun adminBusinessSubscriptions(): JSONObject = request("/admin/business/subscriptions", "GET")

    suspend fun setAdminBusinessSubscriptionStatus(uid: String, planId: String, active: Boolean, confirmDeactivation: Boolean = false): JSONObject =
        request("/admin/business/subscription-status", payload = JSONObject().apply {
            put("uid", uid)
            put("planId", planId)
            put("active", active)
            put("confirmDeactivation", confirmDeactivation)
        })

    suspend fun adminBusinessTestSession(): JSONObject = request("/admin/business/test-session", "GET")

    suspend fun setAdminBusinessTestSession(planId: String, enabled: Boolean = true, durationMinutes: Int = 120): JSONObject =
        request("/admin/business/test-session", payload = JSONObject().put("planId", planId).put("enabled", enabled).put("durationMinutes", durationMinutes))

    suspend fun runAdminBusinessTestSuite(planId: String): JSONObject =
        request("/admin/business/test-suite", payload = JSONObject().put("planId", planId))

    suspend fun setAdminBusinessPlanStatus(planId: String, active: Boolean, confirmDeactivation: Boolean = false): JSONObject =
        request("/admin/business/plan-status", payload = JSONObject().put("planId", planId).put("active", active).put("confirmDeactivation", confirmDeactivation))


    suspend fun saveAdminPlanCatalog(version:Int, catalog:JSONObject):JSONObject =
        request("/admin/plan-catalog", payload=JSONObject().put("version", version).put("catalog", catalog))

    suspend fun setAdminPlanStatus(planId:String, active:Boolean):JSONObject =
        request("/admin/plan/status", payload=JSONObject().put("planId", planId).put("active", active))

    suspend fun setAdminPlanPopular(planId:String):JSONObject =
        request("/admin/plan/popular", payload=JSONObject().put("planId", planId))


    suspend fun submitContactSales(email:String, companyName:String, companyEmail:String, planId:String, planName:String, features:List<String>, message:String, billingPeriod:String=""):JSONObject =
        request("/contact-sales", payload=JSONObject().apply {
            put("email", email); put("companyName", companyName); put("companyEmail", companyEmail)
            put("planId", planId); put("planName", planName); put("billingPeriod", billingPeriod)
            put("features", JSONArray(features)); put("message", message)
        })

    suspend fun submitSupportMessage(name:String, email:String, subject:String, message:String):JSONObject =
        request("/contact", payload=JSONObject().apply {
            put("name", name.take(160)); put("email", email.take(320))
            put("subject", subject.take(180)); put("message", message.take(6000))
        })

    suspend fun contactSalesStatus():JSONObject = request("/contact-sales/status","GET")

    suspend fun adminContactSales():JSONObject = request("/admin/contact-sales","GET")

    suspend fun adminContactSalesStatus(requestId:String,status:String):JSONObject =
        request("/admin/contact-sales/status", payload=JSONObject().put("requestId",requestId).put("status",status))

    suspend fun entitlement():JSONObject = request("/entitlement","GET")

    suspend fun entitlements():JSONObject = request("/entitlements","GET")

    // The Worker reads/writes the same Firebase users/{uid} credit authority as the website.
    suspend fun credits():JSONObject = request("/credits","GET")

    suspend fun playSubscriptionManagement():JSONObject = request("/play/subscription-management","GET")

    suspend fun usage(limit:Int=50):JSONObject = request("/usage?limit=" + limit.coerceIn(1,100),"GET")

    suspend fun organizationCatalog(): JSONObject = request("/organization/catalog","GET")
    suspend fun organizationAccess(): JSONObject = request("/organization/access","GET")
    suspend fun adminOrganizationControl(): JSONObject = request("/admin/organization","GET")
    suspend fun setAdminOrganizationPlanStatus(planId:String, active:Boolean, confirmDeactivation:Boolean=false): JSONObject =
        request("/admin/organization/plan-status", payload=JSONObject().put("planId",planId).put("active",active).put("confirmDeactivation",confirmDeactivation))
    suspend fun setAdminOrganizationEntitlement(uid:String, planId:String, active:Boolean, confirmDeactivation:Boolean=false): JSONObject =
        request("/admin/organization/entitlement", payload=JSONObject().put("uid",uid).put("planId",planId).put("active",active).put("confirmDeactivation",confirmDeactivation))
    suspend fun adminOrganizationTestSession(): JSONObject = request("/admin/organization/test-session","GET")
    suspend fun adminOrganizationBilling(): JSONObject = request("/admin/organization-billing","GET")
    suspend fun saveAdminOrganizationBilling(payload: JSONObject): JSONObject = request("/admin/organization-billing", payload = payload)
    suspend fun setAdminOrganizationTestSession(planId:String, enabled:Boolean=true, durationMinutes:Int=120): JSONObject =
        request("/admin/organization/test-session", payload=JSONObject().put("planId",planId).put("enabled",enabled).put("durationMinutes",durationMinutes))

    suspend fun organizationScheduledTasks(): JSONObject = request("/organization/scheduled-tasks","GET")

    suspend fun createOrganizationScheduledTask(name:String, prompt:String, schedule:String, enabled:Boolean=true): JSONObject =
        request("/organization/scheduled-tasks", payload=JSONObject().apply {
            put("name", name.take(160)); put("prompt", prompt.take(8000)); put("schedule", schedule.take(40)); put("enabled", enabled)
        })

    suspend fun updateOrganizationScheduledTask(id:String, patch:JSONObject): JSONObject =
        request("/organization/scheduled-tasks/" + java.net.URLEncoder.encode(id, "UTF-8"), "PATCH", patch)

    suspend fun runOrganizationScheduledTask(id:String): JSONObject =
        request("/organization/scheduled-tasks/" + java.net.URLEncoder.encode(id, "UTF-8") + "/run", payload=JSONObject())

    suspend fun organizationBilling(): JSONObject = request("/organization/billing","GET")

    /** Returns organizations the signed-in user can access on the canonical website. */
    suspend fun studioPaygOrganizations(): JSONObject = request("/studio-payg", "GET")

    /** Read-only wallet view backed by the website canonical studioPaygAccounts ledger. */
    suspend fun studioPayg(orgId: String): JSONObject =
        request("/studio-payg?orgId=" + java.net.URLEncoder.encode(orgId, "UTF-8"), "GET")

    suspend fun organizationLiveBillingStart(payload: JSONObject): JSONObject =
        request("/organization/production/live/billing/start", payload = payload)

    suspend fun organizationLiveBillingHeartbeat(sessionId: String, accountId: String = "", broadcastId: String = ""): JSONObject =
        request("/organization/production/live/billing/heartbeat", payload = JSONObject()
            .put("sessionId", sessionId)
            .put("accountId", accountId)
            .put("broadcastId", broadcastId))

    suspend fun organizationLiveBilling(sessionId: String): JSONObject =
        request("/organization/production/live/billing?sessionId=" + java.net.URLEncoder.encode(sessionId, "UTF-8"), "GET")

    suspend fun organizationLiveBillingStop(sessionId: String, accountId: String = "", broadcastId: String = "", reason: String = "operator_stop"): JSONObject =
        request("/organization/production/live/billing/stop", payload = JSONObject()
            .put("sessionId", sessionId)
            .put("accountId", accountId)
            .put("broadcastId", broadcastId)
            .put("reason", reason))

    suspend fun organization(): JSONObject = request("/organization","GET")

    suspend fun updateOrganization(name:String, description:String): JSONObject =
        request("/organization", payload=JSONObject().put("name",name.take(160)).put("description",description.take(2000)))

    suspend fun addOrganizationChannel(name:String, type:String="chat"): JSONObject =
        request("/organization/channels", payload=JSONObject().put("name",name.take(120)).put("type",type.take(40)))

    suspend fun setOrganizationChannel(channelId:String, enabled:Boolean): JSONObject =
        request("/organization/channel-state", payload=JSONObject().put("channelId",channelId).put("enabled",enabled))

    suspend fun updateOrganizationSettings(assistantEnabled:Boolean?=null, sharedMemoryEnabled:Boolean?=null, sharedKnowledgeEnabled:Boolean?=null):JSONObject =
        request("/organization/settings",payload=JSONObject().apply {
            if(assistantEnabled!=null) put("assistantEnabled",assistantEnabled)
            if(sharedMemoryEnabled!=null) put("sharedMemoryEnabled",sharedMemoryEnabled)
            if(sharedKnowledgeEnabled!=null) put("sharedKnowledgeEnabled",sharedKnowledgeEnabled)
        })

    suspend fun organizationAssistant(message:String):JSONObject =
        request("/organization/assistant",payload=JSONObject().put("message",message.take(8000)))

    suspend fun addOrganizationKnowledge(text:String, source:String="manual"):JSONObject =
        request("/organization/knowledge",payload=JSONObject().put("text",text.take(12000)).put("source",source.take(80)))

    suspend fun upsertOrganizationMember(memberId:String, role:String="member"):JSONObject =
        request("/organization/members",payload=JSONObject().put("memberId",memberId.take(200)).put("role",role.take(30)))

    suspend fun setOrganizationMemberStatus(memberId:String, status:String):JSONObject =
        request("/organization/member-state",payload=JSONObject().put("memberId",memberId.take(200)).put("status",status.take(20)))

    suspend fun transitionYouTubeLive(accountId: String, broadcastId: String, status: String): JSONObject =
        request("/organization/production/youtube/live/transition", payload=JSONObject()
            .put("accountId", accountId)
            .put("broadcastId", broadcastId)
            .put("status", status))

    suspend fun createYouTubeLive(payload: JSONObject): JSONObject =
        request("/organization/production/youtube/live", payload=payload)

    suspend fun startOrganizationSocialOAuth(organizationId: String, platform: String, redirectUri: String): JSONObject =
        request("/organization/social/oauth/start", payload=JSONObject()
            .put("organizationId", organizationId)
            .put("platform", platform)
            .put("redirectUri", redirectUri))

    suspend fun organizationSocialAccounts(organizationId: String): JSONObject =
        request("/organization/social/accounts?organizationId=" + java.net.URLEncoder.encode(organizationId, "UTF-8"), "GET")

    suspend fun organizationTikTokProfile(organizationId: String, accountId: String): JSONObject =
        request("/organization/social/tiktok/profile?organizationId=" + java.net.URLEncoder.encode(organizationId, "UTF-8") + "&accountId=" + java.net.URLEncoder.encode(accountId, "UTF-8"), "GET")

    suspend fun organizationTikTokVideos(organizationId: String, accountId: String): JSONObject =
        request("/organization/social/tiktok/videos?organizationId=" + java.net.URLEncoder.encode(organizationId, "UTF-8") + "&accountId=" + java.net.URLEncoder.encode(accountId, "UTF-8"), "GET")

    suspend fun organizationTikTokPublish(organizationId: String, accountId: String, mediaUrl: String, title: String, privacyLevel: String = "SELF_ONLY", isAigc: Boolean = true): JSONObject =
        request("/organization/social/publish", payload=JSONObject()
            .put("organizationId", organizationId)
            .put("accountId", accountId)
            .put("mediaUrl", mediaUrl)
            .put("title", title)
            .put("privacyLevel", privacyLevel)
            .put("isAigc", isAigc))

    suspend fun organizationYouTubeAccounts(organizationId: String): JSONObject =
        request("/organization/social/accounts?organizationId=" + java.net.URLEncoder.encode(organizationId, "UTF-8"), "GET")

    suspend fun startOrganizationYouTubeOAuth(organizationId: String, redirectUri: String): JSONObject =
        request("/organization/social/oauth/start", payload=JSONObject()
            .put("organizationId", organizationId)
            .put("platform", "youtube")
            .put("redirectUri", redirectUri.take(1000)))

    suspend fun organizationYouTubeLiveChat(organizationId: String, accountId: String, liveChatId: String): JSONObject =
        request("/organization/production/youtube/live/chat?organizationId=" +
            java.net.URLEncoder.encode(organizationId, "UTF-8") +
            "&accountId=" + java.net.URLEncoder.encode(accountId, "UTF-8") +
            "&liveChatId=" + java.net.URLEncoder.encode(liveChatId, "UTF-8"), "GET")

    suspend fun replyToOrganizationYouTubeChat(organizationId: String, accountId: String, liveChatId: String, viewerText: String, authorChannelId: String = ""): JSONObject =
        request("/organization/production/youtube/live/chat/reply", payload=JSONObject()
            .put("organizationId", organizationId)
            .put("accountId", accountId)
            .put("liveChatId", liveChatId)
            .put("viewerText", viewerText.take(4000))
            .put("authorChannelId", authorChannelId.take(200)))

    suspend fun organizationAiLiveConfig(): JSONObject =
        request("/organization/production/ai-live", "GET")

    suspend fun updateOrganizationAiLiveConfig(payload: JSONObject): JSONObject =
        request("/organization/production/ai-live", payload=payload)

    suspend fun organizationProduction(): JSONObject = request("/organization/production","GET")

    suspend fun updateOrganizationProductionConfig(payload: JSONObject): JSONObject =
        request("/organization/production/config", payload=payload)

    suspend fun upsertOrganizationProductionSource(payload: JSONObject): JSONObject =
        request("/organization/production/source", payload=payload)

    suspend fun removeOrganizationProductionSource(sourceId: String): JSONObject =
        request("/organization/production/source/remove", payload=JSONObject().put("sourceId", sourceId.take(120)))

    suspend fun upsertOrganizationProductionScene(payload: JSONObject): JSONObject =
        request("/organization/production/scene", payload=payload)

    suspend fun upsertOrganizationProductionDestination(payload: JSONObject): JSONObject =
        request("/organization/production/destination", payload=payload)

    suspend fun setOrganizationProductionState(payload: JSONObject): JSONObject =
        request("/organization/production/state", payload=payload)

    suspend fun enforceLiveContentPolicy(payload: JSONObject): JSONObject =
        request("/organization/production/live/content-policy", payload=payload)

    suspend fun updateOrganizationProductionHealth(payload: JSONObject): JSONObject =
        request("/organization/production/health", payload=payload)

    suspend fun addOrganizationProductionEvent(payload: JSONObject): JSONObject =
        request("/organization/production/event", payload=payload)

    suspend fun businessWorkspace(): JSONObject = request("/business/workspace","GET")

    suspend fun createBusinessWorkspace(name:String, planId:String="bizstarter"): JSONObject =
        request("/business/workspace", payload=JSONObject().put("name", name.take(180)).put("planId", planId.take(100)))

    suspend fun businessRecords(type:String, workspaceId:String?=null): JSONObject =
        request("/business/records?type=" + type + (workspaceId?.let { "&workspaceId=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""), "GET")

    suspend fun createBusinessRecord(type:String, payload:JSONObject): JSONObject =
        request("/business/records", payload=JSONObject(payload.toString()).put("type", type))

    suspend fun updateBusinessRecord(type:String, id:String, payload:JSONObject): JSONObject =
        request("/business/records/" + type + "/" + id, "PATCH", payload)

    suspend fun deleteBusinessRecord(type:String, id:String): JSONObject =
        request("/business/records/" + type + "/" + id, "DELETE")

    suspend fun addBusinessMember(workspaceId:String, uid:String, role:String="member", permissions:List<String> = emptyList()): JSONObject =
        request("/business/members", payload=JSONObject().apply {
            put("workspaceId", workspaceId); put("uid", uid); put("role", role); put("permissions", JSONArray(permissions))
        })

    suspend fun updateBusinessSettings(workspaceId:String, assistantEnabled:Boolean?=null, automationEnabled:Boolean?=null, notificationsEnabled:Boolean?=null, webSearchEnabled:Boolean?=null, timezone:String?=null): JSONObject =
        request("/business/settings", payload=JSONObject().apply {
            put("workspaceId", workspaceId)
            if (assistantEnabled != null) put("assistantEnabled", assistantEnabled)
            if (automationEnabled != null) put("automationEnabled", automationEnabled)
            if (notificationsEnabled != null) put("notificationsEnabled", notificationsEnabled)
            if (webSearchEnabled != null) put("webSearchEnabled", webSearchEnabled)
            if (timezone != null) put("timezone", timezone.take(80))
        })

    suspend fun businessExtended(workspaceId:String?=null): JSONObject =
        request("/business/extended" + (workspaceId?.let { "?workspaceId=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""), "GET")

    suspend fun createBusinessExtended(type:String, payload:JSONObject): JSONObject =
        request("/business/extended", payload=JSONObject(payload.toString()).put("type", type))

    suspend fun updateBusinessExtended(type:String, id:String, payload:JSONObject): JSONObject =
        request("/business/extended/" + type + "/" + id, "PATCH", payload)

    suspend fun runBusinessAutomation(automationId:String, event:JSONObject = JSONObject()): JSONObject =
        request("/business/automations/run", payload=JSONObject().put("automationId", automationId.take(160)).put("event", event))

    suspend fun disconnectSocialAccount(accountId: String): JSONObject =
        request("/social/accounts/" + java.net.URLEncoder.encode(accountId, "UTF-8"), "DELETE")

    suspend fun socialAccounts(workspaceId: String): JSONObject = request("/social/accounts?workspaceId=" + java.net.URLEncoder.encode(workspaceId, "UTF-8"), "GET")

    suspend fun startSocialOAuth(platform:String, redirectUri:String, workspaceId:String): JSONObject =
        request("/social/oauth/start", payload=JSONObject()
            .put("platform", platform.take(40))
            .put("redirectUri", redirectUri.take(1000))
            .put("workspaceId", workspaceId.take(120)))

    suspend fun xAction(accountId:String, workspaceId:String, action:String, postId:String? = null, text:String? = null): JSONObject =
        request("/social/x/action", payload=JSONObject().apply {
            put("accountId", accountId.take(160))
            put("workspaceId", workspaceId.take(120))
            put("action", action.take(40))
            if (postId != null) put("postId", postId.take(80))
            if (text != null) put("text", text.take(5000))
        })

    suspend fun publishSocial(accountId:String, text:String, mediaUrl:String? = null, pageId:String? = null, instagramAccountId:String? = null): JSONObject =
        request("/social/publish", payload=JSONObject().apply {
            put("accountId", accountId.take(160)); put("text", text.take(5000))
            if (mediaUrl != null) put("mediaUrl", mediaUrl.take(2000))
            if (pageId != null) put("pageId", pageId.take(120))
            if (instagramAccountId != null) put("instagramAccountId", instagramAccountId.take(120))
        })

    suspend fun tiktokProfile(accountId:String, workspaceId:String): JSONObject =
        request("/social/tiktok/profile?accountId=" + java.net.URLEncoder.encode(accountId, "UTF-8") + "&workspaceId=" + java.net.URLEncoder.encode(workspaceId, "UTF-8"), "GET")

    suspend fun tiktokVideos(accountId:String, workspaceId:String): JSONObject =
        request("/social/tiktok/videos?accountId=" + java.net.URLEncoder.encode(accountId, "UTF-8") + "&workspaceId=" + java.net.URLEncoder.encode(workspaceId, "UTF-8"), "GET")

    suspend fun publishTikTok(accountId:String, workspaceId:String, mediaUrl:String, title:String, privacyLevel:String = "SELF_ONLY", isAigc:Boolean = true): JSONObject =
        request("/social/publish", payload=JSONObject().apply {
            put("accountId", accountId.take(160)); put("workspaceId", workspaceId.take(120)); put("platform", "tiktok")
            put("mediaUrl", mediaUrl.take(4000)); put("text", title.take(5000))
            put("title", title.take(2200)); put("privacyLevel", privacyLevel.take(40)); put("isAigc", isAigc)
        })

    suspend fun businessAdvancedAi(payload: JSONObject): JSONObject =
        request("/business/advanced-ai", payload=payload)

    suspend fun businessPlanBuilder(workspaceId:String, brief:String): JSONObject =
        request("/business/plan-builder", payload=JSONObject().put("workspaceId", workspaceId).put("brief", brief.take(12000)))


    suspend fun businessAdvancedAiJobs(workspaceId:String? = null): JSONObject =
        request("/business/advanced-ai/jobs" + (workspaceId?.let { "?workspaceId=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""), "GET")

    suspend fun businessIntegrations(workspaceId:String): JSONObject =
        request("/business/integrations?workspaceId=" + java.net.URLEncoder.encode(workspaceId, "UTF-8"), "GET")

    suspend fun configureBusinessIntegration(payload:JSONObject): JSONObject =
        request("/business/integrations", payload=payload)

    suspend fun businessEnterprise(workspaceId:String): JSONObject =
        request("/business/enterprise?workspaceId=" + java.net.URLEncoder.encode(workspaceId, "UTF-8"), "GET")

    suspend fun updateBusinessEnterprise(workspaceId:String, config:JSONObject): JSONObject =
        request("/business/enterprise", "PATCH", JSONObject(config.toString()).put("workspaceId", workspaceId))

    suspend fun businessAssistant(message:String): JSONObject =
        request("/business/assistant", payload=JSONObject().put("message", message.take(8000)))

    suspend fun createBusinessTrainingUpload(
        type:String,
        title:String,
        purpose:String,
        fileName:String,
        contentType:String,
        size:Long
    ): JSONObject = request("/business/training-assets/upload-url", payload=JSONObject().apply {
        put("type", type.take(40))
        put("title", title.take(180))
        put("purpose", purpose.take(1000))
        put("fileName", fileName.take(160))
        put("contentType", contentType.take(120))
        put("size", size)
    })

    suspend fun finalizeBusinessTrainingUpload(assetId:String): JSONObject =
        request("/business/training-assets/finalize", payload=JSONObject().put("assetId", assetId.take(120)))

    suspend fun addBusinessTrainingAsset(type:String, title:String, purpose:String): JSONObject =
        request("/business/training-assets", payload=JSONObject().apply {
            put("type", type.take(40)); put("title", title.take(180)); put("purpose", purpose.take(1000))
        })

    suspend fun addBusinessKnowledge(note:String): JSONObject =
        request("/business/knowledge", payload=JSONObject().put("text", note.take(12000)))

    suspend fun addBusinessLead(name:String, contact:String, note:String): JSONObject =
        request("/business/leads", payload=JSONObject().apply {
            put("name", name.take(160)); put("contact", contact.take(320)); put("note", note.take(2000))
        })

    suspend fun addBusinessInboxMessage(message:String, contact:String? = null): JSONObject =
        request("/business/inbox", payload=JSONObject().apply {
            put("message", message.take(4000)); if (contact != null) put("contact", contact.take(320))
        })

    suspend fun reportMessage(message:String, category:String="response", messageId:String?=null, sentiment:String?=null, reason:String?=null, comment:String?=null):JSONObject = request("/feedback",payload=JSONObject().apply {
        put("message", message)
        put("category", category)
        if(messageId!=null)put("messageId",messageId)
        if(sentiment!=null)put("sentiment",sentiment)
        if(reason!=null)put("reason",reason)
        if(comment!=null)put("comment",comment)
    })

    suspend fun saveConsent(policyVersion:String, trainingConsent:Boolean):JSONObject =
        request("/consent",payload=JSONObject().put("requiredAccepted",true).put("policyVersion",policyVersion).put("trainingConsent",trainingConsent))

    suspend fun consent():JSONObject = request("/consent","GET")

    suspend fun updateTrainingConsent(trainingConsent:Boolean):JSONObject =
        request("/consent/training",payload=JSONObject().put("trainingConsent",trainingConsent))

    suspend fun submitTrainingContribution(prompt:String, response:String, workspace:String, privateMode:Boolean=false):JSONObject =
        request("/training/contributions", payload=JSONObject().apply {
            put("prompt", prompt.take(4000))
            put("response", response.take(4000))
            put("workspace", workspace.take(60))
            put("privateMode", privateMode)
        })

    suspend fun adminTrainingLibrary():JSONObject = request("/admin/training-library", "GET")

    suspend fun adminFeedback():JSONObject = request("/admin/feedback", "GET")
    suspend fun adminFeedbackStatus(id: String, source: String, status: String): JSONObject =
        request("/admin/feedback/status", payload=JSONObject()
            .put("id", id.take(180))
            .put("source", source.take(40))
            .put("status", status.take(24)))


    suspend fun truthGuardHistory(): List<JSONObject> {
        val body = request("/truthguard/history", "GET")
        val items = body.optJSONArray("items") ?: JSONArray()
        return List(items.length()) { i -> items.getJSONObject(i) }
    }

    suspend fun truthGuardCatalog(): JSONObject = request("/truthguard/catalog", "GET")

    suspend fun truthGuardSettings(): JSONObject = request("/truthguard/settings", "GET")

    suspend fun saveTruthGuardSettings(settings: JSONObject): JSONObject =
        request("/truthguard/settings", "PUT", settings)

    suspend fun truthGuardCheck(claim:String, deepResearch:Boolean): JSONObject =
        request("/truthguard/check", payload=JSONObject()
            .put("claim", claim.take(12000))
            .put("deepResearch", deepResearch))

    suspend fun workspaceAi(capability:String, message:String, system:String? = null):JSONObject =
        request("/ai", payload=JSONObject().apply {
            put("capability", capability.take(64))
            put("message", message.take(12000))
            if (!system.isNullOrBlank()) put("system", system.take(12000))
        })

    suspend fun companionCatalog(): JSONObject = request("/companion/catalog", "GET")
    suspend fun companionProfile(): JSONObject = request("/companion/profile", "GET")
    suspend fun saveCompanionProfile(profile: JSONObject): JSONObject = request("/companion/profile", "PUT", profile)
    suspend fun companionHistory(): JSONObject = request("/companion/history", "GET")
    suspend fun companionChat(message: String, personality: String? = null): JSONObject =
        request("/companion/chat", payload=JSONObject().apply {
            put("message", message.take(12000))
            if (!personality.isNullOrBlank()) put("personality", personality.take(40))
        })
    suspend fun companionCheckins(): JSONObject = request("/companion/checkins", "GET")
    suspend fun companionCheckin(prompt: String, personality: String? = null): JSONObject =
        request("/companion/checkin", payload=JSONObject().apply {
            put("prompt", prompt.take(500))
            if (!personality.isNullOrBlank()) put("personality", personality.take(40))
        })
    suspend fun companionSettings(): JSONObject = request("/companion/settings", "GET")
    suspend fun saveCompanionSettings(settings: JSONObject): JSONObject = request("/companion/settings", "PUT", settings)

    suspend fun learningCatalog(): JSONObject = request("/learning/catalog", "GET")

    suspend fun learningProgress(): JSONObject = request("/learning/progress", "GET")

    suspend fun learningSession(
        mode: String,
        subject: String,
        goal: String,
        feature: String? = null
    ): JSONObject = request("/learning/session", payload=JSONObject().apply {
        put("mode", mode.take(32))
        put("subject", subject.take(80))
        put("goal", goal.take(12000))
        if (!feature.isNullOrBlank()) put("feature", feature.take(40))
    })


    suspend fun adminDynamicBillingConfig(): JSONObject = request("/admin/ai-billing/config", "GET")

    suspend fun saveAdminDynamicBillingConfig(config: JSONObject): JSONObject =
        request("/admin/ai-billing/config", "POST", config)

    suspend fun adminDynamicBillingAnalytics(limit: Int = 500): JSONObject =
        request("/admin/ai-billing/analytics?limit=${limit.coerceIn(1, 1000)}", "GET")

    suspend fun rollbackAdminDynamicBillingConfig(): JSONObject =
        request("/admin/ai-billing/rollback", "POST", JSONObject())

    suspend fun adminAiModels(): JSONObject = request("/admin/ai-models", "GET")
    suspend fun adminAiUsage(limit: Int = 100): JSONObject = request("/admin/ai-usage?limit=${limit.coerceIn(1, 500)}", "GET")
    suspend fun validateAdminAiModel(model: JSONObject): JSONObject = request("/admin/ai-models/validate", "POST", JSONObject().put("model", model))
    suspend fun checkAdminAiModelStatus(id: String): JSONObject =
        request("/admin/ai-models/status", payload = JSONObject().put("id", id))
    suspend fun saveAdminAiModel(model: JSONObject): JSONObject = request("/admin/ai-models", "POST", JSONObject().put("model", model))
    suspend fun publishedAiModels(): JSONObject = publicRequest("/ai/models", "GET")

    suspend fun workspaceResearch(query:String, deepResearch:Boolean = false):SearchReply =
        search(query, deepResearch)
}