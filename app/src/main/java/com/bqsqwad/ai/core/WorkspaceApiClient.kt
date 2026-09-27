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

data class SearchSource(val title:String,val url:String,val snippet:String?)
data class SearchReply(val sources:List<SearchSource>,val creditsCharged:Double,val answer:String? = null)

class WorkspaceApiClient {
    private val baseUrl = "https://europe-west1-" + FirebaseApp.getInstance().options.projectId + ".cloudfunctions.net/bqSqwadApi"

    private suspend fun request(path:String, method:String="POST", payload:JSONObject=JSONObject()):JSONObject = withContext(Dispatchers.IO) {
        val user=FirebaseAuth.getInstance().currentUser ?: error("Sign in is required")
        val token=Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable")
        val c=(URL(baseUrl+path).openConnection() as HttpURLConnection).apply {
            requestMethod=method; connectTimeout=15000; readTimeout=120000; doInput=true
            if(method!="GET"){doOutput=true;setRequestProperty("Content-Type","application/json")}
            setRequestProperty("Authorization","Bearer $token")
        }
        if(method!="GET") c.outputStream.use{it.write(payload.toString().toByteArray())}
        val status=c.responseCode
        val stream=if(status in 200..299)c.inputStream else c.errorStream
        val raw=stream?.bufferedReader()?.use{it.readText()}.orEmpty()
        val body=runCatching{JSONObject(raw)}.getOrNull()
        if(status !in 200..299) {
            val serverError=body?.optString("error").orEmpty()
            val trimmed = raw.trim()
            val detail = serverError.ifBlank {
                if (trimmed.startsWith("<html", ignoreCase = true) || trimmed.startsWith("<!doctype", ignoreCase = true)) {
                    "BQ Sqwad AI's secure backend is unavailable right now. Please try again shortly."
                } else {
                    trimmed.take(2000).ifBlank { "No response body" }
                }
            }
            error("HTTP $status: $detail")
        }
        if(body == null) {
            error("HTTP $status: Invalid JSON response: " + raw.trim().take(2000).ifBlank { "No response body" })
        }
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

    suspend fun realtimeSession():JSONObject = request("/realtime/session", payload=JSONObject())

    suspend fun plans():JSONObject = request("/plans","GET")

    suspend fun playCatalog():JSONObject = request("/play/catalog","GET")

    suspend fun nativeCatalog():JSONObject = request("/native/catalog","GET")
    suspend fun adminAccess():JSONObject = request("/admin/access","GET")

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

    suspend fun contactSalesStatus():JSONObject = request("/contact-sales/status","GET")

    suspend fun adminContactSales():JSONObject = request("/admin/contact-sales","GET")

    suspend fun adminContactSalesStatus(requestId:String,status:String):JSONObject =
        request("/admin/contact-sales/status", payload=JSONObject().put("requestId",requestId).put("status",status))

    suspend fun entitlement():JSONObject = request("/entitlement","GET")

    suspend fun entitlements():JSONObject = request("/entitlements","GET")

    suspend fun credits():JSONObject = request("/credits","GET")

    suspend fun playSubscriptionManagement():JSONObject = request("/play/subscription-management","GET")

    suspend fun usage(limit:Int=50):JSONObject = request("/usage?limit=" + limit.coerceIn(1,100),"GET")

    suspend fun organizationScheduledTasks(): JSONObject = request("/organization/scheduled-tasks","GET")

    suspend fun createOrganizationScheduledTask(name:String, prompt:String, schedule:String, enabled:Boolean=true): JSONObject =
        request("/organization/scheduled-tasks", payload=JSONObject().apply {
            put("name", name.take(160)); put("prompt", prompt.take(8000)); put("schedule", schedule.take(40)); put("enabled", enabled)
        })

    suspend fun updateOrganizationScheduledTask(id:String, patch:JSONObject): JSONObject =
        request("/organization/scheduled-tasks/" + java.net.URLEncoder.encode(id, "UTF-8"), "PATCH", patch)

    suspend fun runOrganizationScheduledTask(id:String): JSONObject =
        request("/organization/scheduled-tasks/" + java.net.URLEncoder.encode(id, "UTF-8") + "/run", payload=JSONObject())

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

    suspend fun updateBusinessSettings(workspaceId:String, assistantEnabled:Boolean?=null, automationEnabled:Boolean?=null, notificationsEnabled:Boolean?=null, webSearchEnabled:Boolean?=null): JSONObject =
        request("/business/settings", payload=JSONObject().apply {
            put("workspaceId", workspaceId)
            if (assistantEnabled != null) put("assistantEnabled", assistantEnabled)
            if (automationEnabled != null) put("automationEnabled", automationEnabled)
            if (notificationsEnabled != null) put("notificationsEnabled", notificationsEnabled)
            if (webSearchEnabled != null) put("webSearchEnabled", webSearchEnabled)
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

    suspend fun truthGuardHistory(): List<JSONObject> {
        val body = request("/truthguard/history", "GET")
        val items = body.optJSONArray("items") ?: JSONArray()
        return List(items.length()) { i -> items.getJSONObject(i) }
    }

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

    suspend fun workspaceResearch(query:String, deepResearch:Boolean = false):SearchReply =
        search(query, deepResearch)
}