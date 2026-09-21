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

    suspend fun plans():JSONObject = request("/plans","GET")

    suspend fun playCatalog():JSONObject = request("/play/catalog","GET")

    suspend fun nativeCatalog():JSONObject = request("/native/catalog","GET")

    suspend fun entitlement():JSONObject = request("/entitlement","GET")

    suspend fun credits():JSONObject = request("/credits","GET")

    suspend fun usage(limit:Int=50):JSONObject = request("/usage?limit=" + limit.coerceIn(1,100),"GET")

    suspend fun organization():JSONObject = request("/organization","GET")

    suspend fun updateOrganization(name:String, description:String):JSONObject =
        request("/organization",payload=JSONObject().put("name",name.take(160)).put("description",description.take(2000)))

    suspend fun addOrganizationChannel(name:String, type:String="chat"):JSONObject =
        request("/organization/channels",payload=JSONObject().put("name",name.take(120)).put("type",type.take(40)))

    suspend fun setOrganizationChannel(channelId:String, enabled:Boolean):JSONObject =
        request("/organization/channel-state",payload=JSONObject().put("channelId",channelId).put("enabled",enabled))

    suspend fun organization(): JSONObject = request("/organization","GET")

    suspend fun updateOrganization(name:String, description:String): JSONObject =
        request("/organization", payload=JSONObject().put("name",name.take(160)).put("description",description.take(2000)))

    suspend fun addOrganizationChannel(name:String, type:String="chat"): JSONObject =
        request("/organization/channels", payload=JSONObject().put("name",name.take(120)).put("type",type.take(40)))

    suspend fun setOrganizationChannel(channelId:String, enabled:Boolean): JSONObject =
        request("/organization/channel-state", payload=JSONObject().put("channelId",channelId).put("enabled",enabled))

    suspend fun businessWorkspace(): JSONObject = request("/business/workspace","GET")

    suspend fun businessAssistant(message:String): JSONObject =
        request("/business/assistant", payload=JSONObject().put("message", message.take(8000)))

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