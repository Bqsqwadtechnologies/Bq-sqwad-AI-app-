package com.bqsqwad.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AdminContactSalesScreen(onBack:()->Unit){
    val scope=rememberCoroutineScope()
    val context=androidx.compose.ui.platform.LocalContext.current
    var requests by remember { mutableStateOf(emptyList<JSONObject>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(){
        loading=true
        scope.launch{
            runCatching{
                val a=WorkspaceApiClient().adminContactSales().optJSONArray("requests")?:JSONArray()
                List(a.length()){i->a.getJSONObject(i)}
            }.onSuccess{requests=it;error=null}.onFailure{error=it.message?:"Could not load Contact Sales requests."}
            loading=false
        }
    }
    LaunchedEffect(Unit){load()}

    Scaffold(topBar={TopAppBar(title={Text("Contact Sales Requests")},navigationIcon={IconButton(onClick=onBack){Text("‹")}},actions={TextButton(onClick=::load){Text("Refresh")}})}){p->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=32.dp)){
            if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
            items(requests,key={it.optString("id")}){r->
                val email=r.optString("email")
                val status=r.optString("status","new")
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                        Text(r.optString("planName").ifBlank{"Contact request"},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                        Text("Plan ID: "+r.optString("planId"),style=MaterialTheme.typography.labelMedium)
                        Text("User: $email")
                        r.optString("companyName").takeIf{it.isNotBlank()}?.let{Text("Company: $it")}
                        r.optString("companyEmail").takeIf{it.isNotBlank()}?.let{Text("Company email: $it")}
                        Text("Status: "+status)
                        Text(r.optString("message"))
                        val fs=r.optJSONArray("features")
                        if(fs!=null && fs.length()>0){
                            Text("Requested features",fontWeight=FontWeight.SemiBold)
                            repeat(fs.length()){i->Text("• "+fs.optString(i),style=MaterialTheme.typography.bodySmall)}
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            TextButton(onClick={scope.launch{WorkspaceApiClient().adminContactSalesStatus(r.optString("id"),"seen");load()}}){Text("Seen")}
                            TextButton(onClick={
                                scope.launch{WorkspaceApiClient().adminContactSalesStatus(r.optString("id"),"contacted");load()}
                            }){Text("Contacted")}
                            TextButton(onClick={
                                val intent=Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"+Uri.encode(email)+"?subject="+Uri.encode("BQ Sqwad AI — "+r.optString("planName"))))
                                runCatching{context.startActivity(intent)}
                            }){Text("Email")}
                        }
                    }
                }
            }
            if(!loading && requests.isEmpty())item{Text("No Contact Sales requests yet.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}
