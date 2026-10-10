@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class AdminCatalogRow(
    val module:String,
    val name:String,
    val id:String,
    val productId:String?,
    val productType:String,
    val billingType:String,
    val basePlans:String,
    val offerId:String?,
    val prices:String,
    val trial:String,
    val grace:String,
    val accountHold:String,
    val resubscribe:String,
    val planChange:String,
    val playStatus:String,
    val contactSales:Boolean,
    val active:Boolean,
    val popular:Boolean,
    val limits:String,
    val features:List<String>,
    val tiers:String,
    val notes:List<String>
)

@Composable
fun AdminPlanCatalogScreen(onBack:()->Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }
    var catalog by remember { mutableStateOf(JSONObject()) }
    var editor by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<AdminCatalogRow>()) }
    var expandedEditor by remember { mutableStateOf(false) }

    fun parseRows(c:JSONObject):List<AdminCatalogRow> {
        val a = c.optJSONArray("plans") ?: run {
            val flattened = JSONArray()
            val sections = c.optJSONObject("sections") ?: JSONObject()
            val keys = sections.keys()
            while (keys.hasNext()) {
                val section = sections.optJSONArray(keys.next()) ?: continue
                for (index in 0 until section.length()) {
                    section.optJSONObject(index)?.let { flattened.put(it) }
                }
            }
            flattened
        }
        return List(a.length()) { i ->
            val p=a.getJSONObject(i)
            val price=p.optJSONObject("price") ?: JSONObject()
            val priceText=price.keys().asSequence().map { k -> "$k="+price.opt(k) }.joinToString(" • ")
            val bases=p.optJSONArray("basePlans") ?: JSONArray()
            val features=p.optJSONArray("features") ?: JSONArray()
            val tiers=p.optJSONArray("quantityTiers") ?: JSONArray()
            val notes=p.optJSONArray("configurationNotes") ?: JSONArray()
            AdminCatalogRow(
                module=p.optString("module",""),
                name=p.optString("name",p.optString("id")),
                id=p.optString("id"),
                productId=p.optString("productId").ifBlank { null },
                productType=p.optString("productType"),
                billingType=p.optString("billingType",p.optString("productType")),
                basePlans=List(bases.length()){n->bases.optString(n)}.joinToString(", "),
                offerId=p.optString("offerId").ifBlank{null},
                prices=priceText.ifBlank{"Custom / Contact Sales"},
                trial=if(p.optInt("trialDays",0)>0) p.optString("offerId")+" • "+p.optInt("trialDays")+" days" else "None",
                grace=p.optInt("gracePeriodDays",7).toString()+" days",
                accountHold=p.optString("accountHold"),
                resubscribe=p.optString("resubscribe"),
                planChange=p.optString("planChange"),
                playStatus=p.optString("playBillingStatus"),
                contactSales=p.optBoolean("contactSales",false),
                active=p.optBoolean("active",true),
                popular=p.optBoolean("popular",false),
                limits=p.optJSONObject("limits")?.toString() ?: "{}",
                features=List(features.length()){n->features.optString(n)}.filter{it.isNotBlank()},
                tiers=tiers.toString(),
                notes=List(notes.length()){n->notes.optString(n)}.filter{it.isNotBlank()}
            )
        }
    }

    fun load() {
        loading=true; error=null; notice=null
        scope.launch {
            runCatching {
                val response=WorkspaceApiClient().adminPlanCatalog()
                version=response.optInt("version",0)
                catalog=response.optJSONObject("catalog") ?: error("Admin catalog payload missing")
                editor=catalog.toString(2)
                rows=parseRows(catalog)
            }.onFailure { error=it.message ?: "Could not load the admin catalog." }
            loading=false
        }
    }

    fun setPlanActive(planId:String, active:Boolean) {
        scope.launch {
            runCatching { WorkspaceApiClient().setAdminPlanStatus(planId, active) }
                .onSuccess {
                    notice = if (active) "Plan activated." else "Plan deactivated. Existing verified subscribers are not revoked."
                    load()
                }
                .onFailure { error = it.message ?: "Could not change plan availability." }
        }
    }

    fun setPlanPopular(planId:String) {
        scope.launch {
            runCatching { WorkspaceApiClient().setAdminPlanPopular(planId) }
                .onSuccess {
                    notice = "Popular plan updated."
                    load()
                }
                .onFailure { error = it.message ?: "Could not update Popular plan." }
        }
    }

    fun save() {
        saving=true; error=null; notice=null
        scope.launch {
            runCatching {
                val parsed=JSONObject(editor)
                val result=WorkspaceApiClient().saveAdminPlanCatalog(version,parsed)
                version=result.optInt("version",version+1)
                catalog=result.optJSONObject("catalog") ?: parsed
                editor=catalog.toString(2)
                rows=parseRows(catalog)
                notice="Catalog saved as version $version."
            }.onFailure { error=it.message ?: "Catalog save failed." }
            saving=false
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar={
            TopAppBar(
                title={Text("Admin Plan Catalog",maxLines=1)},
                navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}},
                actions={
                    IconButton(onClick=::load,enabled=!loading&&!saving){Icon(Icons.Default.Refresh,"Refresh")}
                    IconButton(onClick=::save,enabled=!loading&&!saving&&editor.isNotBlank()){Icon(Icons.Default.Save,"Save catalog")}
                }
            )
        }
    ){ padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),
            contentPadding=PaddingValues(top=16.dp,bottom=32.dp)
        ) {
            item {
                Surface(shape=RoundedCornerShape(18.dp),tonalElevation=2.dp,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Canonical commercial configuration",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Text("Version $version • Admin-only • Server authorization is required. Internal IDs are never shown to ordinary users.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("USD list prices are configuration values; Google Play supplies the localized transaction price at checkout.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if(loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            notice?.let { item { Text(it,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold) } }
            error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }

            item {
                OutlinedButton(onClick={expandedEditor=!expandedEditor},modifier=Modifier.fillMaxWidth()) {
                    Text(if(expandedEditor)"Hide full catalog JSON editor" else "Configure full catalog")
                }
            }
            if(expandedEditor) {
                item {
                    OutlinedTextField(
                        value=editor,
                        onValueChange={editor=it},
                        modifier=Modifier.fillMaxWidth().heightIn(min=420.dp),
                        label={Text("Admin catalog JSON")},
                        supportingText={Text("Edit plan IDs, Play mappings, billing periods, prices, trial/grace settings, limits, entitlements, tiers, availability and active state. IDs must use lowercase letters, numbers and hyphens only.")},
                        maxLines=28
                    )
                }
            }

            item { Text("Catalog entries",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
            items(rows,key={it.module+":"+it.id+":"+it.productId.orEmpty()}) { row ->
                Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(row.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text(row.module,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment=androidx.compose.ui.Alignment.End) {
                                Text(if(row.active)"Active" else "Inactive",color=if(row.active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)
                                Switch(
                                    checked=row.active,
                                    enabled=!saving,
                                    onCheckedChange={setPlanActive(row.id,it)}
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(row.popular) {
                                AssistChip(onClick={},label={Text("POPULAR")})
                            }
                            OutlinedButton(
                                onClick={setPlanPopular(row.id)},
                                enabled=!saving
                            ) { Text(if(row.popular)"Popular" else "Set Popular") }
                        }
                        Text("Internal ID: "+row.id,fontWeight=FontWeight.SemiBold)
                        Text("Google Play product: "+(row.productId ?: "None"))
                        Text("Product type: "+row.productType+" • Billing: "+row.billingType)
                        Text("Base plans: "+(row.basePlans.ifBlank{"None"})+" • Offer: "+(row.offerId ?: "None"))
                        Text("Price: "+row.prices)
                        Text("Trial: "+row.trial+" • Grace: "+row.grace+" • Hold: "+row.accountHold)
                        Text("Resubscribe: "+row.resubscribe+" • Plan change: "+row.planChange)
                        Text("Play status: "+row.playStatus+" • Contact Sales: "+row.contactSales)
                        if(row.limits!="{}") { Text("Limits",fontWeight=FontWeight.SemiBold); Text(row.limits) }
                        if(row.features.isNotEmpty()) { Text("Features",fontWeight=FontWeight.SemiBold); row.features.forEach{Text("• "+it,style=MaterialTheme.typography.bodySmall)} }
                        if(row.tiers!="[]") { Text("Tier configuration",fontWeight=FontWeight.SemiBold); Text(row.tiers,style=MaterialTheme.typography.bodySmall) }
                        row.notes.forEach{Text("Note: "+it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
        }
    }
}
