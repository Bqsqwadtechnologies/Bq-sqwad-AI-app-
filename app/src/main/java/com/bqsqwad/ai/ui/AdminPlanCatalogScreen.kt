package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import org.json.JSONObject

private data class AdminPlanRow(
    val id:String,
    val name:String,
    val monthly:String,
    val yearly:String,
    val features:List<String>,
    val limits:String = ""
)
private data class AdminAddOnRow(val id:String,val name:String,val price:String,val unit:String)

@Composable
fun AdminPlanCatalogScreen(onBack:()->Unit) {
    val scope=rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var business=remember { mutableStateListOf<AdminPlanRow>() }
    var companion=remember { mutableStateListOf<AdminPlanRow>() }
    var learning=remember { mutableStateListOf<AdminPlanRow>() }
    var capability=remember { mutableStateListOf<AdminPlanRow>() }
    var truth=remember { mutableStateListOf<AdminPlanRow>() }
    var life=remember { mutableStateListOf<AdminPlanRow>() }
    var credits=remember { mutableStateListOf<AdminPlanRow>() }
    var addOns=remember { mutableStateListOf<AdminAddOnRow>() }
    var exams=remember { mutableStateListOf<AdminPlanRow>() }
    var trialId by remember { mutableStateOf("trial-3d") }

    fun load() {
        loading=true; error=null
        scope.launch {
            runCatching {
                val body=WorkspaceApiClient().adminPlanCatalog()
                trialId=body.optJSONObject("catalog")?.optString("trialOfferId","trial-3d") ?: "trial-3d"
                val cat=body.optJSONObject("catalog") ?: JSONObject()
                replace(business, parsePlans(cat.optJSONArray("business")))
                replace(companion, parsePlans(cat.optJSONArray("companion")))
                replace(learning, parsePlans(cat.optJSONArray("learning")))
                replace(capability, parsePlans(cat.optJSONArray("learningCapability")))
                replace(truth, parsePlans(cat.optJSONArray("truthGuard")))
                replace(life, parsePlans(cat.optJSONArray("lifeMathis")))
                replace(credits, parsePlans(cat.optJSONArray("credits")))
                replace(exams, parseExamPlans(cat.optJSONArray("examAddOns")))
                replaceAddOns(addOns, parseAddOns(cat.optJSONArray("addOns")))
            }.onFailure { error=it.message ?: "Admin plan catalog could not be loaded." }
            loading=false
        }
    }
    LaunchedEffect(Unit){load()}

    Scaffold(topBar={
        TopAppBar(
            title={Text("Admin Plan Catalog")},
            navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}},
            actions={TextButton(onClick=::load,enabled=!loading){Text(if(loading)"Loading…" else "Refresh")}}
        )
    }) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=32.dp)){
            item{
                Surface(shape=RoundedCornerShape(18.dp),tonalElevation=2.dp,modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text("Admin-only commercial configuration",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Text("Plan/product IDs are internal configuration. They are not rendered to ordinary users.")
                        Text("3-day trial offer ID: $trialId")
                        Text("Base plans: monthly / yearly • Add-ons: no trial",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            section("Business",business)
            section("Companion",companion)
            section("Learning AI",learning)
            section("Learning capability tiers",capability)
            section("TruthGuard AI",truth)
            section("AI Life Mathis",life)
            section("Credits",credits)
            item{Text("Learning exam add-ons",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
            items(exams,key={it.id}){PlanRow(it)}
            item{Text("Business/general add-ons",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
            items(addOns,key={it.id}){a->
                Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(a.name,fontWeight=FontWeight.Bold)
                        Text("ID: "+a.id,style=MaterialTheme.typography.labelMedium)
                        Text("Price: "+a.price+" • "+a.unit)
                    }
                }
            }
            error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        }
    }
}

private fun parsePlans(a:JSONArray?):List<AdminPlanRow>{
    if(a==null)return emptyList()
    return List(a.length()){i->
        val x=a.getJSONArray(i)
        val id=x.optString(0)
        val name=x.optString(1)
        val monthly=x.optString(2).takeIf{it.isNotBlank()&&it!="null"}?.let{if(it=="0")"$0" else "$"+it} ?: "Contact Sales"
        val yearly=x.optString(3).takeIf{it.isNotBlank()&&it!="null"}?.let{if(it=="0")"$0" else "$"+it} ?: "Contact Sales"
        val f=x.optJSONArray(4) ?: JSONArray()
        val features=List(f.length()){n->f.optString(n)}.filter{it.isNotBlank()}
        val limits=if(x.length()>5) x.opt(5)?.toString().orEmpty() else ""
        AdminPlanRow(id,name,monthly,yearly,features,limits)
    }
}
private fun parseExamPlans(a:JSONArray?):List<AdminPlanRow>{
    if(a==null)return emptyList()
    return List(a.length()){i->
        val x=a.getJSONArray(i)
        AdminPlanRow(x.optString(0),x.optString(1),"$"+x.optString(2),"$"+x.optString(3),emptyList(),"Monthly / Term")
    }
}
private fun parseAddOns(a:JSONArray?):List<AdminAddOnRow>{
    if(a==null)return emptyList()
    return List(a.length()){i->
        val x=a.getJSONArray(i)
        AdminAddOnRow(x.optString(0),x.optString(1),x.optString(2),x.optString(3))
    }
}
private fun replace(target:MutableList<AdminPlanRow>,items:List<AdminPlanRow>){target.clear();target.addAll(items)}
private fun replaceAddOns(target:MutableList<AdminAddOnRow>,items:List<AdminAddOnRow>){target.clear();target.addAll(items)}

private fun LazyListScope.section(title:String,rows:List<AdminPlanRow>){
    item{Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
    items(rows,key={it.id}){PlanRow(it)}
}
@Composable private fun PlanRow(p:AdminPlanRow){
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text(p.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            Text("Plan ID: "+p.id,style=MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text("Monthly: "+p.monthly)
                Text("Yearly: "+p.yearly)
            }
            if(p.limits.isNotBlank()) Text("Limits: "+p.limits,style=MaterialTheme.typography.bodySmall)
            Text("Features",fontWeight=FontWeight.SemiBold)
            p.features.forEach{Text("• "+it,style=MaterialTheme.typography.bodySmall)}
        }
    }
}
