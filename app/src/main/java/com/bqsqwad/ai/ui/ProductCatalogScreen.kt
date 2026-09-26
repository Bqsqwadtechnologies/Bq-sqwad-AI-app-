@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bqsqwad.ai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import java.time.Instant
import java.time.temporal.ChronoUnit

private data class PlayCatalogEntry(val planId:String,val productId:String,val basePlanId:String?,val productType:String,val enabled:Boolean,val priceUsd:Double?,val billingPeriod:String?)
private data class LivePlan(val id:String,val name:String,val module:String,val features:List<String>,val limits:Map<String,String>,val price:Map<String,String> = emptyMap(),val productId:String? = null,val basePlans:List<String> = emptyList(),val trialDays:Int = 0,val trialEligible:Boolean = false,val offerId:String? = null,val paymentChannel:String = "play",val playEligible:Boolean = false,val contactSales:Boolean = false,val popular:Boolean = false)
private data class CatalogFeature(val id:String,val name:String,val description:String,val group:String,val comingSoon:Boolean)
private data class FeatureBinding(val planId:String,val featureId:String,val state:String,val quota:String?)
private data class PublicContactOption(val name:String,val module:String,val billingPeriod:String,val price:String,val features:List<String>,val limits:Map<String,String>)
private data class ContactSelection(val planId:String,val planName:String,val billingPeriod:String,val features:List<String>)
private data class UserEntitlement(val id:String,val planId:String,val productId:String,val product:String,val basePlanId:String?,val state:String,val renewalState:String,val endDate:String?)

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
    var entitlement by remember { mutableStateOf<JSONObject?>(null) }
    var entitlements by remember { mutableStateOf(emptyList<UserEntitlement>()) }
    var contactPlan by remember { mutableStateOf<ContactSelection?>(null) }
    var salesNotice by remember { mutableStateOf<String?>(null) }
    var credits by remember { mutableStateOf<JSONObject?>(null) }
    var contactOptions by remember { mutableStateOf(emptyList<PublicContactOption>()) }
    var playManagement by remember { mutableStateOf<JSONObject?>(null) }

    fun load() {
        loading=true; error=null; offline=false
        scope.launch {
            try {
                val api=WorkspaceApiClient()
                val live=api.nativeCatalog()
                plans=parsePlans(live); catalog=parseCatalog(live); features=parseFeatures(live); bindings=parseBindings(live); contactOptions=parseContactOptions(live); entitlement=runCatching{api.entitlement()}.getOrNull()
                entitlements=runCatching{parseUserEntitlements(api.entitlements())}.getOrDefault(emptyList())
                credits=runCatching{api.credits()}.getOrNull()
                playManagement=runCatching{api.playSubscriptionManagement()}.getOrNull()
                salesNotice=runCatching {
                    val a=api.contactSalesStatus().optJSONArray("requests")
                    val rows=a?.let { (0 until it.length()).map { i -> it.getJSONObject(i) } } ?: emptyList()
                    val contacted=rows.firstOrNull { it.optString("status")=="contacted" }
                    val seen=rows.firstOrNull { it.optString("status")=="seen" }
                    contacted?.let { "Sales has contacted you about "+it.optString("planName").ifBlank{"your request"}+". Check your email." }
                        ?: seen?.let { "Your Contact Sales request for "+it.optString("planName").ifBlank{"your request"}+" has been seen by the admin team." }
                }.getOrNull()
                context.getSharedPreferences("bq_product_catalog",Context.MODE_PRIVATE).edit()
                    .putString("catalog",live.toString()).apply()
            } catch(t:Throwable) {
                val cache=context.getSharedPreferences("bq_product_catalog",Context.MODE_PRIVATE)
                val c=cache.getString("catalog",null)
                if(c!=null){val cached=JSONObject(c);plans=parsePlans(cached);catalog=parseCatalog(cached);features=parseFeatures(cached);bindings=parseBindings(cached);contactOptions=parseContactOptions(cached);offline=true}
                else error=t.message?:"Product configuration could not be loaded."
            } finally { loading=false }
        }
    }

    DisposableEffect(activity) {
        if(activity!=null){
            val manager=PlayBillingClient(activity){state->status=state.message; if(state.status=="success") load()}
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
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(30000); runCatching { load() } }
    }

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
                item{Surface(shape=RoundedCornerShape(20.dp),tonalElevation=3.dp,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){BqLogo(56.dp);Text("Choose what you need",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Plans and USD list prices come from the live admin catalog. Google Play supplies the final localized checkout price.",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                if(status.isNotBlank())item{Text(status,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                salesNotice?.let { notice -> item { ProductNotice("Sales update", notice) } }
                item {
                    OutlinedButton(
                        onClick = {
                            billing?.restorePurchases()
                            status = "Checking your Google Play purchases and restoring verified access…"
                        },
                        enabled = billing?.isReady() == true,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Restore purchases") }
                }
                credits?.let { balance ->
                    item {
                        Surface(
                            shape=RoundedCornerShape(24.dp),
                            tonalElevation=6.dp,
                            modifier=Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Live credit balance", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                                        Text("Shared across eligible AI features", color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    BqStatusBadge("LIVE")
                                }
                                Text(
                                    String.format(java.util.Locale.US, "%.2f", balance.optDouble("available", 0.0)),
                                    style=MaterialTheme.typography.displaySmall,
                                    fontWeight=FontWeight.Bold,
                                    color=MaterialTheme.colorScheme.primary
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Surface(Modifier.weight(1f), shape=RoundedCornerShape(16.dp), color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Column(Modifier.padding(14.dp)) {
                                            Text("Available", style=MaterialTheme.typography.labelLarge)
                                            Text(String.format(java.util.Locale.US, "%.2f", balance.optDouble("available", 0.0)), fontWeight=FontWeight.Bold)
                                        }
                                    }
                                    Surface(Modifier.weight(1f), shape=RoundedCornerShape(16.dp), color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Column(Modifier.padding(14.dp)) {
                                            Text("Reserved", style=MaterialTheme.typography.labelLarge)
                                            Text(String.format(java.util.Locale.US, "%.2f", balance.optDouble("reserved", 0.0)), fontWeight=FontWeight.Bold)
                                        }
                                    }
                                }
                                Text("The backend is authoritative for balance, reservations and credit deductions.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (playManagement?.optBoolean("hasSubscription", false) == true) {
                    item {
                        val autoRenew = playManagement?.optBoolean("autoRenewEnabled", false) == true
                        Surface(
                            shape=RoundedCornerShape(22.dp),
                            tonalElevation=4.dp,
                            modifier=Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Text("Google Play subscription", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                                Text(
                                    if (autoRenew) "Auto-renewal is ON. Google Play will renew this subscription unless you turn it off there."
                                    else "Auto-renewal is OFF. Access remains available until the current paid period ends.",
                                    color=if (autoRenew) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                    fontWeight=FontWeight.SemiBold
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = {
                                            val url = playManagement?.optString("managementUrl").orEmpty()
                                            if (url.isNotBlank()) {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                status = "Google Play opened. Turn off auto-renewal there; your access remains until the paid period ends."
                                            }
                                        },
                                        modifier=Modifier.weight(1f)
                                    ) {
                                        Text(if (autoRenew) "Turn off auto-renewal" else "Manage subscription")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val url = playManagement?.optString("paymentMethodManagementUrl").orEmpty()
                                            if (url.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        },
                                        modifier=Modifier.weight(1f)
                                    ) { Text("Payment method") }
                                }
                                Text(
                                    if (autoRenew) "Turn off auto-renewal opens the Google Play subscription controls. Google Play remains authoritative; BQ Sqwad AI will refresh the backend entitlement after the change." else "Auto-renewal is currently off. You can manage or resubscribe through Google Play. BQ Sqwad AI will refresh the backend entitlement after the change.",
                                    style=MaterialTheme.typography.bodySmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (entitlements.isNotEmpty()) {
                    val expiring = entitlements.filter { daysUntil(it.endDate) in 0L..7L }
                    if (expiring.isNotEmpty()) item {
                        Surface(shape=RoundedCornerShape(18.dp), color=MaterialTheme.colorScheme.errorContainer, modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text("Plan expiry notice", fontWeight=FontWeight.Bold, color=MaterialTheme.colorScheme.onErrorContainer)
                                Text(if (expiring.size == 1) "A paid feature plan expires within 7 days." else expiring.size.toString() + " feature plans expire within 7 days.", color=MaterialTheme.colorScheme.onErrorContainer)
                                expiring.forEach { row ->
                                    val name = plans.firstOrNull { it.id == row.planId }?.name ?: row.product.ifBlank { row.planId }
                                    val days = daysUntil(row.endDate)
                                    Text(name + " • " + if (days <= 0) "expires today" else days.toString() + " days to expire", fontWeight=FontWeight.SemiBold, color=MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    item {
                        Surface(shape=RoundedCornerShape(20.dp), tonalElevation=3.dp, modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text("Current plans", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                                Text("Your verified feature access and renewal dates.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                                entitlements.forEach { row ->
                                    val plan = plans.firstOrNull { it.id == row.planId }
                                    val days = daysUntil(row.endDate)
                                    val warning = days in 0L..7L
                                    Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh), shape=RoundedCornerShape(16.dp)) {
                                        Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(plan?.name ?: row.product.ifBlank { row.planId.ifBlank { "Feature plan" } }, fontWeight=FontWeight.Bold)
                                                    Text(row.basePlanId?.replace('-', ' ')?.replaceFirstChar { it.uppercase() } ?: "Active access", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Text(row.state.replace('_',' ').replaceFirstChar{it.uppercase()}, style=MaterialTheme.typography.labelLarge, color=if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                            }
                                            row.endDate?.let { date ->
                                                val expiryText = when {
                                                    days < 0 -> "Expired"
                                                    days == 0L -> "Expires today"
                                                    days <= 7L -> days.toString() + " days to expire"
                                                    else -> "Renews / access until " + formatEntitlementDate(date)
                                                }
                                                Text(expiryText, fontWeight=if (warning) FontWeight.Bold else FontWeight.Medium, color=if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else entitlement?.let { ent ->
                    if (ent.optBoolean("exists", false)) item {
                        Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.fillMaxWidth()){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                Text("Current plan",fontWeight=FontWeight.Bold)
                                Text(plans.firstOrNull { it.id == ent.optString("planId") }?.name?.ifBlank { null } ?: "Current plan")
                                Text(ent.optString("state").ifBlank{ent.optString("status").ifBlank{"Active"}}.replace('_',' ').replaceFirstChar{it.uppercase()},style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                                ent.optString("endDate").takeIf{it.isNotBlank()}?.let{Text("Access end: "+it.take(10),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                        }
                    }
                }
                if(features.isNotEmpty() && plans.isNotEmpty()) item {
                    Surface(shape=RoundedCornerShape(18.dp),tonalElevation=2.dp,modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("Feature matrix",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                            Text("Every row is resolved against the live plan-feature bindings. No plan is treated as included unless its backend binding says so.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            plans.take(8).forEach { plan ->
                                Text(plan.name.ifBlank { "Plan" },style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                features.take(24).forEach { feature ->
                                    val binding=bindings.firstOrNull{it.planId==plan.id && it.featureId==feature.id}
                                    val state=binding?.state ?: if(feature.comingSoon) "coming_soon" else "unavailable"
                                    val label=when(state){"included"->"Included";"add-on"->"Add-on";"coming_soon"->"Coming soon";else->"Unavailable"}
                                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                        Text(if(state=="included")"✓" else "•",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
                                        Column(Modifier.weight(1f)){Text(feature.name,fontWeight=FontWeight.SemiBold);binding?.quota?.takeIf{it.isNotBlank()}?.let{Text("Quota: $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                                        Text(label,style=MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                if (plans.isNotEmpty()) {
                    item { Text("Plans by workspace", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) }
                    val moduleOrder = listOf("business","learning","truthguard","life-mathis","companion","learning-capability","frontier","data-analysis","organization","credits")
                    val grouped = plans.groupBy { it.module }
                    moduleOrder.filter { grouped.containsKey(it) }.forEach { module ->
                        item(key="module:"+module) {
                            val title = when (module) {
                                "business" -> "Business AI"
                                "life-mathis" -> "Life Mathis"
                                "truthguard" -> "TruthGuard AI"
                                "learning" -> "Learning Studio"
                                "companion" -> "AI Companion"
                                "organization" -> "Organization AI / Studio"
                                else -> module.replace('-', ' ').replaceFirstChar { it.uppercase() }
                            }
                            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(title, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                                Text(if (module == "life-mathis") "Life Mathis plans, monthly/yearly pricing and included guidance features." else "Plans, pricing and features for this workspace.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        grouped.getValue(module).forEach { plan ->
                            item(key="plan:"+plan.id) {
                                LivePlanCard(
                                    plan=plan,
                                    playEntries=catalog.filter { it.planId == plan.id && it.enabled },
                                    playProducts=playProducts,
                                    billing=billing,
                                    onContact={ contactPlan=ContactSelection(plan.id, plan.name, "custom", plan.features) },
                                    onStatus={ status=it }
                                )
                            }
                        }
                    }
                    plans.filter { it.module !in moduleOrder }.groupBy { it.module }.forEach { (module, modulePlans) ->
                        item(key="module:"+module) {
                            Text(module.replace('-', ' ').replaceFirstChar { it.uppercase() }, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                        }
                        modulePlans.forEach { plan ->
                            item(key="plan:"+plan.id) {
                                LivePlanCard(
                                    plan=plan,
                                    playEntry=catalog.firstOrNull { it.planId == plan.id && it.enabled },
                                    playProducts=playProducts,
                                    billing=billing,
                                    onContact={ contactPlan=ContactSelection(plan.id, plan.name, "custom", plan.features) },
                                    onStatus={ status=it }
                                )
                            }
                        }
                    }
                }

                // Google Play's product/base-plan projection is intentionally not rendered
                // as a second catalog. It is an internal checkout mapping only. Users see
                // the BQ Sqwad AI catalog above; Play details are queried only to resolve
                // the selected product/base plan and launch secure checkout.
                if(contactOptions.isNotEmpty()){
                    item{Text("Contact Sales",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
                    items(contactOptions,key={it.name+":"+it.billingPeriod}){option->
                        Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){
                            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(option.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text(option.billingPeriod.replaceFirstChar{it.uppercase()}+" • Contact Sales",color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick={contactPlan=ContactSelection("",option.name,option.billingPeriod,option.features)},modifier=Modifier.fillMaxWidth()){Text("Contact Sales")}
                            }
                        }
                    }
                }
            }
        }
    }
    contactPlan?.let { selection ->
        ContactSalesDialog(selection.planId, selection.planName, selection.features, selection.billingPeriod, onDismiss = { contactPlan = null }) { notice ->
            salesNotice = notice
            contactPlan = null
        }
    }
}

@Composable
private fun LivePlanCard(
    plan: LivePlan,
    playEntries: List<PlayCatalogEntry>,
    playProducts: List<ProductDetails>,
    billing: PlayBillingClient?,
    onContact: () -> Unit,
    onStatus: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val supportedPeriods = plan.price.keys.filter { it in setOf("monthly", "yearly", "term", "weekly") }
    var selectedPeriod by remember(plan.id) { mutableStateOf(supportedPeriods.firstOrNull { it == "monthly" } ?: supportedPeriods.firstOrNull { it == "yearly" } ?: supportedPeriods.firstOrNull().orEmpty()) }
    val selectedEntry = playEntries.firstOrNull { it.basePlanId == selectedPeriod || it.billingPeriod == selectedPeriod } ?: playEntries.firstOrNull()
    val selectedProduct = selectedEntry?.let { entry -> playProducts.firstOrNull { it.productId == entry.productId } }
    val priceText = plan.price.entries.joinToString(" • ") { (period, value) ->
        val numeric = value.toDoubleOrNull()
        val shown = numeric?.let { String.format(java.util.Locale.US, "$%.2f", it) } ?: value
        period.replaceFirstChar { it.uppercase() } + ": " + shown
    }.ifBlank { if (plan.contactSales) "Contact Sales" else "Price not configured" }
    val truthGuard = plan.module.equals("truthguard", ignoreCase = true)
    val tierContainer = when (plan.id) {
        "truthguardfree" -> MaterialTheme.colorScheme.surfaceVariant
        "tgplus" -> MaterialTheme.colorScheme.primaryContainer
        "tgpro" -> MaterialTheme.colorScheme.secondaryContainer
        "tgteam" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val tierLabel = when (plan.id) {
        "truthguardfree" -> "STARTER"
        "tgplus" -> "PLUS"
        "tgpro" -> "PRO"
        "tgteam" -> "TEAM"
        else -> null
    }
    Card(
        Modifier.fillMaxWidth(),
        shape=RoundedCornerShape(22.dp),
        border=if(plan.popular) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if (truthGuard) {
                Surface(
                    color=tierContainer,
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("TruthGuard AI", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                            Text(
                                when(plan.id) {
                                    "truthguardfree" -> "Essential claim checks"
                                    "tgplus" -> "Live evidence intelligence"
                                    "tgpro" -> "Deep verification suite"
                                    "tgteam" -> "Collaborative verification"
                                    else -> "Evidence-first verification"
                                },
                                style=MaterialTheme.typography.bodySmall
                            )
                        }
                        tierLabel?.let { Text(it, style=MaterialTheme.typography.labelMedium, fontWeight=FontWeight.Bold) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plan.name.ifBlank{"Plan"}, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                    Text(plan.module.ifBlank{"General"}, style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(plan.popular) BqStatusBadge("POPULAR")
            }
            Text("Plan ID: " + plan.id, style=MaterialTheme.typography.bodySmall, fontWeight=FontWeight.SemiBold)
            plan.productId?.let { Text("Subscription ID: " + it, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if (plan.basePlans.isNotEmpty()) {
                Text("Base plans: " + plan.basePlans.joinToString(" • "), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(priceText, style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold, color=MaterialTheme.colorScheme.primary)
            if(plan.trialEligible && plan.trialDays > 0) {
                Text(plan.trialDays.toString()+"-day free trial", fontWeight=FontWeight.SemiBold)
            }
            plan.features.take(8).forEach { Text("• "+it, style=MaterialTheme.typography.bodySmall) }
            if(plan.features.size > 8) Text("+"+(plan.features.size-8)+" more features", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    when {
                        plan.contactSales || plan.paymentChannel == "contact_sales" -> onContact()
                        plan.paymentChannel == "website" -> {
                            onStatus("Opening BQ Sqwad AI website checkout.")
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bqsqwadai.com")))
                            }.onFailure {
                                onStatus("Could not open the BQ Sqwad AI website.")
                            }
                        }
                        plan.playEligible && playEntry != null && playProduct != null && billing?.isReady() == true -> {
                            val result=billing.launchPurchase(playProduct.productId, playEntry.basePlanId)
                            onStatus(if(result?.responseCode==com.android.billingclient.api.BillingClient.BillingResponseCode.OK) "Purchase started for "+plan.id+"/"+(selectedEntry.basePlanId ?: selectedPeriod)+". Access unlocks only after server verification." else result?.debugMessage?.ifBlank{"Purchase could not be started."} ?: "Purchase could not be started.")
                        }
                        else -> onStatus("This plan is not currently purchasable on this device.")
                    }
                },
                modifier=Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        plan.contactSales || plan.paymentChannel=="contact_sales" -> "Contact Sales"
                        plan.paymentChannel=="website" -> "Subscribe on website"
                        else -> "Get"
                    }
                )
            }
        }
    }
}


private fun parseUserEntitlements(body:JSONObject):List<UserEntitlement> {
    val a=body.optJSONArray("items")?:JSONArray()
    return List(a.length()){i->
        val x=a.getJSONObject(i)
        UserEntitlement(x.optString("id"),x.optString("planId"),x.optString("productId"),x.optString("product"),x.optString("basePlanId").ifBlank{null},x.optString("state"),x.optString("renewalState"),x.optString("endDate").ifBlank{null})
    }.filter { it.planId.isNotBlank() || it.productId.isNotBlank() }
}

private fun daysUntil(value:String?):Long {
    if(value.isNullOrBlank()) return Long.MAX_VALUE
    return runCatching { ChronoUnit.DAYS.between(Instant.now(), Instant.parse(value)) }.getOrDefault(Long.MAX_VALUE)
}

private fun formatEntitlementDate(value:String):String {
    return runCatching { java.time.ZonedDateTime.parse(value).toLocalDate().toString() }.getOrElse { value.take(10) }
}

private fun parseContactOptions(body:JSONObject):List<PublicContactOption>{
    val a=body.optJSONArray("publicContactSales")?:JSONArray()
    return List(a.length()){i->
        val x=a.getJSONObject(i)
        val f=x.optJSONArray("features")?:JSONArray()
        val l=x.optJSONObject("limits")?:JSONObject()
        val limits=mutableMapOf<String,String>(); l.keys().forEach{k->limits[k]=l.optString(k)}
        PublicContactOption(x.optString("name"),x.optString("module"),x.optString("billingPeriod"),x.optString("price"),List(f.length()){n->f.optString(n)}.filter{it.isNotBlank()},limits)
    }.filter{it.name.isNotBlank()}
}

private fun parseCatalog(body:JSONObject):List<PlayCatalogEntry>{
    val a=body.optJSONArray("products")?:JSONArray()
    return List(a.length()){i ->
        val p=a.getJSONObject(i)
        PlayCatalogEntry(
            p.optString("planId"),
            p.optString("productId"),
            p.optString("basePlanId").ifBlank{null},
            p.optString("productType"),
            p.optBoolean("enabled",false),
            if(p.has("priceUsd") && !p.isNull("priceUsd")) p.optDouble("priceUsd") else null,
            p.optString("billingPeriod").ifBlank{null}
        )
    }.filter{it.productId.isNotBlank()}
}

private fun parsePlans(body:JSONObject):List<LivePlan>{
    val a=body.optJSONArray("plans")?:JSONArray()
    return List(a.length()){i -> val p=a.getJSONObject(i); val f=p.optJSONArray("features")?:JSONArray();val l=p.optJSONObject("limits")?:JSONObject();val limits=mutableMapOf<String,String>();l.keys().forEach{key->limits[key]=l.optString(key)};LivePlan(
            p.optString("id"),
            p.optString("name"),
            p.optString("module"),
            List(f.length()){n->f.optString(n)}.filter{it.isNotBlank()},
            limits,
            run {
                val priceObject=p.optJSONObject("price") ?: JSONObject()
                val values=mutableMapOf<String,String>()
                priceObject.keys().forEach { key -> values[key]=priceObject.optString(key) }
                values
            },
            p.optString("productId").ifBlank { null },
            run {
                val baseArray = p.optJSONArray("basePlans") ?: JSONArray()
                List(baseArray.length()) { n -> baseArray.optString(n) }.filter { it.isNotBlank() }
            },
            p.optInt("trialDays",0),
            p.optBoolean("trialEligible",false),
            p.optString("offerId").ifBlank{null},
            p.optString("paymentChannel").ifBlank{if(p.optBoolean("playEligible",false))"play" else "website"},
            p.optBoolean("playEligible",false),
            p.optBoolean("contactSales",false),
            p.optBoolean("popular",false)
        )}.filter{it.id.isNotBlank()}
}
@Composable private fun ProductLoading(modifier:Modifier){Column(modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){repeat(4){Card(Modifier.fillMaxWidth().height(132.dp),shape=RoundedCornerShape(18.dp)){}}}}
@Composable private fun ProductError(message:String,modifier:Modifier,retry:()->Unit){Column(modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){BqLogo(64.dp);Spacer(Modifier.height(16.dp));Text("Products unavailable",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(message,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(16.dp));Button(onClick=retry){Text("Retry")}}}
@Composable private fun ProductNotice(title:String,body:String){Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

private fun parseFeatures(body:JSONObject):List<CatalogFeature>{
    val a=body.optJSONArray("features")?:JSONArray()
    return List(a.length()){i -> val x=a.getJSONObject(i); CatalogFeature(x.optString("id"),x.optString("name"),x.optString("description"),x.optString("group"),x.optBoolean("comingSoon",false))}.filter{it.id.isNotBlank()}
}
private fun parseBindings(body:JSONObject):List<FeatureBinding>{
    val a=body.optJSONArray("bindings")?:JSONArray()
    return List(a.length()){i -> val x=a.getJSONObject(i); FeatureBinding(x.optString("planId"),x.optString("featureId"),x.optString("state","unavailable"),if(x.isNull("quota"))null else x.optString("quota"))}.filter{it.featureId.isNotBlank()}
}