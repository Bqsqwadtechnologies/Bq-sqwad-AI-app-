package com.bqsqwad.ai.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.ProductDetails
import com.bqsqwad.ai.core.PlayBillingClient
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class PlayCatalogEntry(val planId:String,val productId:String,val basePlanId:String?,val productType:String,val enabled:Boolean)
private data class LivePlan(val id:String,val name:String,val features:List<String>,val limits:Map<String,String>)
private data class CatalogFeature(val id:String,val name:String,val description:String,val group:String,val comingSoon:Boolean)
private data class FeatureBinding(val planId:String,val featureId:String,val state:String,val quota:String?)

@Composable
fun ProductCatalogScreen(onBack:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val activity=context as? Activity
    val scope=rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(emptyList<LivePlan>()) }
    var catalog by remember { mutableStateOf(emptyList<PlayCatalogEntry>()) }
    var features by remember { mutableStateOf(emptyList<CatalogFeature>()) }
    var bindings by remember { mutableStateOf(emptyList<FeatureBinding>()) }
    var playProducts by remember { mutableStateOf(emptyList<ProductDetails>()) }
    var billing by remember { mutableStateOf<PlayBillingClient?>(null) }
    var status by remember { mutableStateOf("") }

    fun load() {
        loading=true; error=null; offline=false
        scope.launch {
            try {
                val api=WorkspaceApiClient()
                val live=api.nativeCatalog()
                plans=parsePlans(live); catalog=parseCatalog(live); features=parseFeatures(live); bindings=parseBindings(live)
                context.getSharedPreferences("bq_product_catalog",Context.MODE_PRIVATE).edit()
                    .putString("catalog",live.toString()).apply()
            } catch(t:Throwable) {
                val cache=context.getSharedPreferences("bq_product_catalog",Context.MODE_PRIVATE)
                val c=cache.getString("catalog",null)
                if(c!=null){plans=parsePlans(JSONObject(c));catalog=parseCatalog(JSONObject(c));features=parseFeatures(JSONObject(c));bindings=parseBindings(JSONObject(c));offline=true}
                else error=t.message?:"Product configuration could not be loaded."
            } finally { loading=false }
        }
    }

    DisposableEffect(activity) {
        if(activity!=null){
            val manager=PlayBillingClient(activity){state->status=state.message}
            billing=manager; manager.start()
            onDispose{manager.close()}
        } else onDispose{}
    }
    LaunchedEffect(billing){
        repeat(20){
            val manager=billing
            if(manager?.isReady()==true){playProducts=manager.availableProducts();return@LaunchedEffect}
            kotlinx.coroutines.delay(400)
        }
        if(playProducts.isEmpty()) status="Google Play products are not available on this device yet."
    }
    LaunchedEffect(Unit){load()}

    Scaffold(topBar={
        TopAppBar(
            title={Column{Text("Plans & Products",maxLines=1,fontWeight=FontWeight.Bold);Text("BQ Sqwad AI",style=MaterialTheme.typography.labelSmall)}},
            navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,contentDescription="Back")}},
            actions={IconButton(onClick={::load},enabled=!loading){Icon(Icons.Default.Refresh,contentDescription="Refresh products")}}
        )
    }){padding->
        when{
            loading->ProductLoading(Modifier.padding(padding))
            error!=null->ProductError(error!!,Modifier.padding(padding),::load)
            else->LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),contentPadding=PaddingValues(top=16.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                if(offline)item{ProductNotice("Offline mode","Showing the last successfully synced catalog. Purchase actions remain controlled by Google Play.")}
                item{Surface(shape=RoundedCornerShape(20.dp),tonalElevation=3.dp,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){BqLogo(56.dp);Text("Choose what you need",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Features and limits come from the live catalog. Google Play supplies the purchase price shown below.",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                if(status.isNotBlank())item{Text(status,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(features.isNotEmpty()) item {
                    Surface(shape=RoundedCornerShape(18.dp),tonalElevation=2.dp,modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("What is included",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                            Text("Feature availability is read from the backend configuration and entitlement matrix.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            features.take(40).forEach { feature ->
                                val state=bindings.firstOrNull{it.featureId==feature.id}?.state ?: "unavailable"
                                val label=when(state){"included"->"Included";"add-on"->"Add-on";"coming_soon"->"Coming soon";else->"Unavailable"}
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                    Text(if(state=="included")"✓" else "•",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
                                    Column(Modifier.weight(1f)){Text(feature.name,fontWeight=FontWeight.SemiBold);if(feature.description.isNotBlank())Text(feature.description,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)}
                                    Text(label,style=MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                val groups=listOf(
                    "Business AI" to catalog.filter{it.planId.startsWith("business_")||it.productId.startsWith("biz_")},
                    "AI Companion" to catalog.filter{it.planId.startsWith("companion_")||it.productId.startsWith("comp_")},
                    "Learning AI" to catalog.filter{it.planId.startsWith("learn_")||it.productId.startsWith("learn_")},
                    "TruthGuard AI" to catalog.filter{it.planId.startsWith("tg_")||it.productId.startsWith("tg_")},
                    "AI Life Mathis" to catalog.filter{it.planId.startsWith("lm_")||it.productId.startsWith("lm_")},
                    "Credits & Add-ons" to catalog.filter{it.productId.startsWith("cred_")||it.productId.startsWith("biz_ao_")}
                )
                groups.forEach{(title,entries)->
                    if(entries.isNotEmpty()){
                        item{Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
                        items(entries,key={it.productId+":"+(it.basePlanId?:"")}){entry->
                            val plan=plans.firstOrNull{it.id==entry.planId}
                            val product=playProducts.firstOrNull{it.productId==entry.productId}
                            ProductCard(entry,plan,product,billing){status=it}
                        }
                    }
                }
                if(catalog.isEmpty())item{ProductNotice("Play catalog not configured","No approved native product mappings were returned by the backend. Nothing is being invented or offered for sale.")}
                item{ProductNotice("Contact Sales","Custom-priced products do not have a Google Play checkout. No numeric price is shown until the live configuration provides one.")}
            }
        }
    }
}

@Composable
private fun ProductCard(entry:PlayCatalogEntry,plan:LivePlan?,product:ProductDetails?,billing:PlayBillingClient?,onStatus:(String)->Unit){
    val price=product?.subscriptionOfferDetails?.firstOrNull{entry.basePlanId==null||it.basePlanId==entry.basePlanId}?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
        ?:product?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
    val title=plan?.name?.ifBlank{null}?:product?.name?.ifBlank{null}?:entry.planId
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){
                Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Text(entry.planId,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                BqStatusBadge(if(entry.enabled)"Available" else "Unavailable")
            }
            plan?.features?.take(4)?.forEach{feature->Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top){Text("•",color=MaterialTheme.colorScheme.primary);Text(feature,Modifier.weight(1f))}}
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text(price?:"Price unavailable",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(if(entry.basePlanId!=null)"Google Play • ${entry.basePlanId}" else "Google Play",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Button(onClick={
                    if(product==null||price==null||billing?.isReady()!=true)onStatus("This product is not currently available through Google Play.")
                    else{val result=billing.launchPurchase(product.productId, entry.basePlanId);onStatus(if(result.responseCode==com.android.billingclient.api.BillingClient.BillingResponseCode.OK)"Purchase started. Access unlocks only after server verification." else result.debugMessage.ifBlank{"Purchase could not be started."})}
                },enabled=entry.enabled&&product!=null&&price!=null&&billing?.isReady()==true){Text(if(price==null)"Unavailable" else "Get")}
            }
        }
    }
}

private fun parseCatalog(body:JSONObject):List<PlayCatalogEntry>{
    val a=body.optJSONArray("products")?:JSONArray()
    return List(a.length()){i->{val p=a.getJSONObject(i);PlayCatalogEntry(p.optString("planId"),p.optString("productId"),p.optString("basePlanId").ifBlank{null},p.optString("productType"),p.optBoolean("enabled",false))}}.filter{it.productId.isNotBlank()}
}
private fun parsePlans(body:JSONObject):List<LivePlan>{
    val a=body.optJSONArray("plans")?:JSONArray()
    return List(a.length()){i->{val p=a.getJSONObject(i);val f=p.optJSONArray("features")?:JSONArray();val l=p.optJSONObject("limits")?:JSONObject();val limits=mutableMapOf<String,String>();l.keys().forEach{key->limits[key]=l.optString(key)};LivePlan(p.optString("id"),p.optString("name"),List(f.length()){n->f.optString(n)}.filter{it.isNotBlank()},limits)}}.filter{it.id.isNotBlank()}
}
@Composable private fun ProductLoading(modifier:Modifier){Column(modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){repeat(4){Card(Modifier.fillMaxWidth().height(132.dp),shape=RoundedCornerShape(18.dp)){}}}}
@Composable private fun ProductError(message:String,modifier:Modifier,retry:()->Unit){Column(modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){BqLogo(64.dp);Spacer(Modifier.height(16.dp));Text("Products unavailable",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(message,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(16.dp));Button(onClick=retry){Text("Retry")}}}
@Composable private fun ProductNotice(title:String,body:String){Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

private fun parseFeatures(body:JSONObject):List<CatalogFeature>{
    val a=body.optJSONArray("features")?:JSONArray()
    return List(a.length()){i->{val x=a.getJSONObject(i);CatalogFeature(x.optString("id"),x.optString("name"),x.optString("description"),x.optString("group"),x.optBoolean("comingSoon",false))}}.filter{it.id.isNotBlank()&&!it.comingSoon}
}
private fun parseBindings(body:JSONObject):List<FeatureBinding>{
    val a=body.optJSONArray("bindings")?:JSONArray()
    return List(a.length()){i->{val x=a.getJSONObject(i);FeatureBinding(x.optString("planId"),x.optString("featureId"),x.optString("state","unavailable"),if(x.isNull("quota"))null else x.optString("quota"))}}.filter{it.featureId.isNotBlank()}
}
