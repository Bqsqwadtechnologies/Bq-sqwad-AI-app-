package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray

private data class AdminPlanRow(val id:String,val name:String,val monthly:String,val yearly:String,val features:List<String>,val limits:String="")
private data class AdminAddOnRow(val id:String,val name:String,val price:String,val unit:String)

@Composable
fun AdminPlanCatalogScreen(onBack:()->Unit){
 var loading by remember{mutableStateOf(true)}
 var error by remember{mutableStateOf<String?>(null)}
 val groups=remember{mutableStateMapOf<String,List<AdminPlanRow>>()}
 val addOns=remember{mutableStateListOf<AdminAddOnRow>()}
 val scope=rememberCoroutineScope()
 fun load(){loading=true;scope.launch{runCatching{val c=WorkspaceApiClient().adminPlanCatalog().optJSONObject("catalog")?:error("Missing catalog"); listOf("business","companion","learning","learningCapability","truthGuard","lifeMathis","credits").forEach{groups[it]=parsePlans(c.optJSONArray(it))};addOns.clear();addOns.addAll(parseAddOns(c.optJSONArray("addOns")))}.onFailure{error=it.message?: "Could not load catalog"};loading=false}}
 LaunchedEffect(Unit){load()}
 Scaffold(topBar={TopAppBar(title={Text("Admin Plan Catalog")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}},actions={TextButton(onClick=::load){Text(if(loading)"Loading…" else "Refresh")}})}){p->
  LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=32.dp)){
   item{Text("Admin-only IDs and commercial configuration",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("IDs are internal and are not displayed to ordinary users.");Text("Trial: trial-3d • Base plans: monthly / yearly • Add-ons: no trial")}
   listOf("business" to "Business","companion" to "Companion","learning" to "Learning AI","learningCapability" to "Learning capability tiers","truthGuard" to "TruthGuard AI","lifeMathis" to "AI Life Mathis","credits" to "Credits").forEach{(k,t)->section(t,groups[k].orEmpty())}
   item{Text("Business / general add-ons",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
   items(addOns,key={it.id}){a->Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp)){Text(a.name,fontWeight=FontWeight.Bold);Text("ID: "+a.id);Text("Price: "+a.price+" • "+a.unit)}}}
   error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
  }
 }
}
private fun parsePlans(a:JSONArray?):List<AdminPlanRow>{if(a==null)return emptyList();return List(a.length()){i->val x=a.getJSONArray(i);val m=x.opt(2)?.toString()?.let{if(it=="null")"Contact Sales" else if(it=="0")"$0" else "$"+it}?:"Contact Sales";val y=x.opt(3)?.toString()?.let{if(it=="null")"Contact Sales" else if(it=="0")"$0" else "$"+it}?:"Contact Sales";val f=x.optJSONArray(4)?:JSONArray();AdminPlanRow(x.optString(0),x.optString(1),m,y,List(f.length()){n->f.optString(n)},if(x.length()>5)x.opt(5).toString() else "")}}
private fun parseAddOns(a:JSONArray?):List<AdminAddOnRow>{if(a==null)return emptyList();return List(a.length()){i->val x=a.getJSONArray(i);AdminAddOnRow(x.optString(0),x.optString(1),x.optString(2),x.optString(3))}}
private fun LazyListScope.section(title:String,rows:List<AdminPlanRow>){item{Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)};items(rows,key={it.id}){p->Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(p.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Text("Plan ID: "+p.id,fontWeight=FontWeight.SemiBold);Text("Monthly: "+p.monthly+" • Yearly: "+p.yearly);if(p.limits.isNotBlank())Text("Limits: "+p.limits);Text("Features",fontWeight=FontWeight.SemiBold);p.features.forEach{Text("• "+it,style=MaterialTheme.typography.bodySmall)}}}}}}
