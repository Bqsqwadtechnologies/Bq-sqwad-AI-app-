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
data class SearchReply(val sources:List<SearchSource>,val creditsCharged:Double)

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
        val raw=stream.bufferedReader().use{it.readText()}
        val body=runCatching{JSONObject(raw)}.getOrElse{JSONObject().put("error","Invalid server response")}
        if(status !in 200..299) error(body.optString("error","Request failed"))
        body
    }

    suspend fun search(query:String,deepResearch:Boolean=false):SearchReply{
        val b=request("/web-search",payload=JSONObject().put("query",query).put("deepResearch",deepResearch))
        val a=b.optJSONArray("sources") ?: JSONArray()
        return SearchReply(List(a.length()){i->val x=a.getJSONObject(i);SearchSource(x.optString("title"),x.optString("url"),x.optString("snippet").ifBlank{null})},b.optDouble("creditsCharged",0.0))
    }

    suspend fun memory():JSONObject = request("/memory","GET")

    suspend fun remember(text:String, source:String="chat"):JSONObject =
        request("/memory",payload=JSONObject().put("text",text.take(4000)).put("source",source.take(40)))

    suspend fun setMemoryEnabled(enabled:Boolean):JSONObject =
        request("/memory/settings",payload=JSONObject().put("enabled",enabled))

    suspend fun clearMemory():JSONObject = request("/memory","DELETE")

    suspend fun realtimeSession():JSONObject = request("/realtime/session", payload=JSONObject())

    suspend fun plans():JSONObject = request("/plans","GET")

    suspend fun playCatalog():JSONObject = request("/play/catalog","GET")

    suspend fun nativeCatalog():JSONObject = request("/native/catalog","GET")
    suspend fun adminPlanCatalog():JSONObject = request("/admin/plan-catalog","GET")

    suspend fun saveAdminPlanCatalog(version:Int, catalog:JSONObject):JSONObject =
        request("/admin/plan-catalog", payload=JSONObject().put("version", version).put("catalog", catalog))

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

    suspend fun credits():JSONObject = request("/credits","GET")

    suspend fun usage(limit:Int=50):JSONObject = request("/usage?limit=" + limit.coerceIn(1,100),"GET")

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

    suspend fun businessExtended(workspaceId:String?=null): JSONObject =
        request("/business/extended" + (workspaceId?.let { "?workspaceId=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""), "GET")

    suspend fun createBusinessExtended(type:String, payload:JSONObject): JSONObject =
        request("/business/extended", payload=JSONObject(payload.toString()).put("type", type))

    suspend fun updateBusinessExtended(type:String, id:String, payload:JSONObject): JSONObject =
        request("/business/extended/" + type + "/" + id, "PATCH", payload)

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
}