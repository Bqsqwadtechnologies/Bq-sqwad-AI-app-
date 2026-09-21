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

    suspend fun plans():JSONObject = request("/plans","GET")

    suspend fun playCatalog():JSONObject = request("/play/catalog","GET")

    suspend fun nativeCatalog():JSONObject = request("/native/catalog","GET")

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
