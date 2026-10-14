@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.bqsqwad.ai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.BillingClient.ProductType
import com.bqsqwad.ai.core.NativeFeatureRegistry
import com.bqsqwad.ai.core.PlayBillingClient
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

private data class PlayCatalogEntry(val planId:String,val productId:String,val basePlanId:String?,val productType:String,val enabled:Boolean,val priceUsd:Double?,val billingPeriod:String?)
private data class LivePlan(val id:String,val name:String,val module:String,val features:List<String>,val limits:Map<String,String>,val price:Map<String,String> = emptyMap(),val priceNgn:Map<String,String> = emptyMap(),val productId:String? = null,val basePlans:List<String> = emptyList(),val trialDays:Int = 0,val trialEligible:Boolean = false,val offerId:String? = null,val paymentChannel:String = "play",val playEligible:Boolean = false,val contactSales:Boolean = false,val popular:Boolean = false,val sortOrder:Int = 0)
private data class CatalogFeature(val id:String,val name:String,val description:String,val group:String,val comingSoon:Boolean)
private data class FeatureBinding(val planId:String,val featureId:String,val state:String,val quota:String?)
private data class PublicContactOption(val name:String,val module:String,val billingPeriod:String,val price:String,val features:List<String>,val limits:Map<String,String>)
private data class ContactSelection(val planId:String,val planName:String,val billingPeriod:String,val features:List<String>)
private data class UserEntitlement(val id:String,val planId:String,val productId:String,val product:String,val basePlanId:String?,val state:String,val renewalState:String,val endDate:String?)

private fun livePriceLabel(price: JSONObject): String {
    if (!price.optBoolean("enabled", true)) return "Unavailable"
    if (price.optBoolean("free", false)) return "Free now"
    val rate = price.optDouble("creditsPerMinute", 0.0)
    val rateText = if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString()
    val startFee = price.optDouble("startFeeCredits", 0.0)
    return buildString {
        append(rateText).append(" credits/min")
        if (startFee > 0.0) append(" • ").append(startFee).append(" start fee")
    }
}

internal fun orderCanonicalCreditPackIds(ids: Iterable<String>): List<String> {
    val available = ids.toSet()
    return listOf("credit100", "credit250", "credit500", "credit1000").filter { it in available }
}

internal fun catalogCategoryHeaderIndex(totalItemCount: Int, trailingItemCount: Int, planCount: Int): Int =
    (totalItemCount - trailingItemCount - planCount - 1).coerceAtLeast(0)

private fun String.equalsAnyCatalogModule(modules: Set<String>): Boolean = modules.any { equals(it, ignoreCase = true) }

@Composable
fun ProductCatalogScreen(onBack:()->Unit, initialSection: String = "all") {
    val context=androidx.compose.ui.platform.LocalContext.current
    val activity=context as? Activity
    val scope=rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(emptyList<LivePlan>()) }
    var planSearch by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(if (initialSection.equals("credits", ignoreCase = true)) "credits" else "all") }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (initialSection.equals("credits", ignoreCase = true)) 1 else 0)
    var catalog by remember { mutableStateOf(emptyList<PlayCatalogEntry>()) }
    var showAllCapabilities by remember { mutableStateOf(true) }
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
    var livePricing by remember { mutableStateOf<JSONObject?>(null) }
    var contactOptions by remember { mutableStateOf(emptyList<PublicContactOption>()) }
    var playManagement by remember { mutableStateOf<JSONObject?>(null) }

    fun load() {
        // A previous unauthenticated catalog query cannot fetch Play mappings.
        // Retry on refresh so signing in does not require leaving this screen.
        billing?.refreshCatalog()
        error = null
        val cachedCatalog = context.getSharedPreferences("bq_product_catalog", Context.MODE_PRIVATE)
            .getString("catalog", null)
        val cachedSnapshot = cachedCatalog?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        val hasCachedCatalog = cachedSnapshot != null && parsePlans(cachedSnapshot).isNotEmpty()
        if (cachedSnapshot != null) {
            plans = parsePlans(cachedSnapshot)
            catalog = parseCatalog(cachedSnapshot)
            features = parseFeatures(cachedSnapshot)
            bindings = parseBindings(cachedSnapshot)
            contactOptions = parseContactOptions(cachedSnapshot)
            offline = hasCachedCatalog
        } else {
            offline = false
        }
        loading = !hasCachedCatalog
        scope.launch {
            try {
                val api=WorkspaceApiClient()
                val live=api.nativeCatalog()
                val livePlans = parsePlans(live)
                val catalogWarnings = live.optJSONObject("catalogStatus")?.optJSONArray("warnings")
                    ?.let { warnings -> List(warnings.length()) { index -> warnings.optString(index) }.filter { it.isNotBlank() } }
                    .orEmpty()
                if (livePlans.isEmpty() && hasCachedCatalog) {
                    // A transient/partial backend response must not erase the last valid
                    // BQ catalog or replace it with an empty screen.
                    offline = true
                    status = "The live catalog returned no plans. Showing the last synced BQ Sqwad AI catalog."
                } else {
                    offline = false
                    plans = livePlans
                    catalog = parseCatalog(live)
                    features = parseFeatures(live)
                    bindings = parseBindings(live)
                    contactOptions = parseContactOptions(live)
                    if (livePlans.isEmpty()) {
                        status = buildString {
                            append("The BQ Sqwad AI catalog endpoint returned zero visible plans.")
                            if (catalogWarnings.isNotEmpty()) append(" Catalog data warnings: ").append(catalogWarnings.joinToString(", "))
                            append(" Please refresh the catalog or try again later. No sample prices have been shown.")
                        }
                    }
                    context.getSharedPreferences("bq_product_catalog",Context.MODE_PRIVATE).edit()
                        .putString("catalog",live.toString()).apply()
                }
                entitlement=runCatching{api.entitlement()}.getOrNull()
                entitlements=runCatching{parseUserEntitlements(api.entitlements())}.getOrDefault(emptyList())
                credits=runCatching{api.credits()}.getOrNull()
                livePricing=runCatching{api.livePricing()}.getOrNull()
                playManagement=runCatching{api.playSubscriptionManagement()}.getOrNull()
                salesNotice=runCatching {
                    val a=api.contactSalesStatus().optJSONArray("requests")
                    val rows=a?.let { (0 until it.length()).map { i -> it.getJSONObject(i) } } ?: emptyList()
                    val contacted=rows.firstOrNull { it.optString("status")=="contacted" }
                    val seen=rows.firstOrNull { it.optString("status")=="seen" }
                    contacted?.let { "Sales has contacted you about "+it.optString("planName").ifBlank{"your request"}+". Check your email." }
                        ?: seen?.let { "Your Contact Sales request for "+it.optString("planName").ifBlank{"your request"}+" has been seen by our sales team." }
                }.getOrNull()
            } catch(t:Throwable) {
                if (hasCachedCatalog) {
                    offline = true
                    error = null
                } else {
                    error = "BQ Sqwad AI plans are temporarily unavailable. The team is working on it. Please try again shortly."
                }
            } finally { loading=false }
        }
    }

    DisposableEffect(activity) {
        if(activity!=null){
            val manager=PlayBillingClient(activity, onPurchaseState={state->status=state.message; if(state.status=="success") load()})
            billing=manager; manager.start()
            onDispose{manager.close()}
        } else onDispose{}
    }
    LaunchedEffect(billing) {
        // Billing can connect before queryProductDetailsAsync has returned. Keep polling
        // briefly so the plan cards don't incorrectly appear unavailable on fast devices.
        repeat(30) {
            val manager = billing
            if (manager?.isReady() == true) {
                val loaded = manager.availableProducts()
                if (loaded.isNotEmpty()) {
                    playProducts = loaded
                    return@LaunchedEffect
                }
            }
            kotlinx.coroutines.delay(400)
        }
        playProducts = billing?.availableProducts().orEmpty()
        if (playProducts.isEmpty()) {
            status = if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
                "Browse the BQ Sqwad AI plans freely. Sign in before purchasing so checkout and access can be verified securely."
            } else {
                "Your BQ Sqwad AI plans are shown above, but Google Pay checkout is not ready on this device yet. Refresh the catalog and try again shortly."
            }
        }
    }
    LaunchedEffect(Unit){load()}

    // Customer plans come only from the live/cached server catalog. Add-ons and
    // capability-only products stay out of the general plan browser until configured.
    val customerPlanModules = plans.filter { plan ->
        val module = plan.module.lowercase(java.util.Locale.ROOT)
        module !in setOf("learning-capability") &&
            !module.contains("addon") &&
            !module.contains("capability")
    }
    val creditPackOrder = orderCanonicalCreditPackIds(customerPlanModules.map { it.id })
    val creditPacks = customerPlanModules.filter { it.id in creditPackOrder }
        .sortedBy { creditPackOrder.indexOf(it.id) }
    val nonCreditCustomerPlans = customerPlanModules.filter { it.module.lowercase(java.util.Locale.ROOT) != "credits" }
    val categorySpecs = listOf(
        Triple("business", "Business AI", setOf("business")),
        Triple("learning", "Learning Studio", setOf("learning")),
        Triple("organization", "Organization Studio", setOf("organization", "organization-studio")),
        Triple("truthguard", "TruthGuard AI", setOf("truthguard")),
        Triple("life-mathis", "Life Mathis", setOf("life-mathis")),
        Triple("companion", "AI Companion", setOf("companion")),
        Triple("personal-intelligence", "Personal Intelligence", setOf("personal-intelligence")),
        Triple("frontier", "Frontier Lab", setOf("frontier")),
        Triple("data-analysis", "Data Analyst", setOf("data-analysis", "data-analyst"))
    ).filter { spec -> nonCreditCustomerPlans.any { it.module.equalsAnyCatalogModule(spec.third) } }
    val normalizedSearch = planSearch.trim().lowercase(java.util.Locale.ROOT)
    val compactSearch = normalizedSearch.filter { it.isLetterOrDigit() }
    val filteredCustomerPlans = nonCreditCustomerPlans.filter { plan ->
        val categoryMatch = selectedCategory == "all" ||
            categorySpecs.firstOrNull { it.first == selectedCategory }?.third?.contains(plan.module.lowercase(java.util.Locale.ROOT)) == true
        val searchText = (plan.id + " " + plan.name + " " + plan.module)
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        val compactText = searchText.filter { it.isLetterOrDigit() }
        categoryMatch && (normalizedSearch.isBlank() || searchText.contains(normalizedSearch) || compactText.contains(compactSearch))
    }.sortedWith(compareBy<LivePlan> { it.sortOrder }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })

    Scaffold(topBar={
        TopAppBar(
            title={Column{Text("Plans & Credits",maxLines=1,fontWeight=FontWeight.Bold);Text("BQ Sqwad AI catalog",style=MaterialTheme.typography.labelSmall)}},
            navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,contentDescription="Back")}},
            actions={IconButton(onClick={::load},enabled=!loading){Icon(Icons.Default.Refresh,contentDescription="Refresh products")}}
        )
    }){padding->
        LaunchedEffect(initialSection, loading, error) {
            if (!loading && error == null && initialSection.equals("credits", ignoreCase = true)) {
                kotlinx.coroutines.delay(50)
                if (listState.layoutInfo.totalItemsCount > 1) listState.animateScrollToItem(1)
            }
        }
        LaunchedEffect(selectedCategory, filteredCustomerPlans.size, showAllCapabilities, contactOptions.size) {
            if (selectedCategory != "all" && selectedCategory != "credits" && filteredCustomerPlans.isNotEmpty()) {
                kotlinx.coroutines.delay(80)
                val trailingItems = 1 +
                    (if (showAllCapabilities) 2 + NativeFeatureRegistry.core.size + NativeFeatureRegistry.advanced.size else 0) +
                    (if (contactOptions.isNotEmpty()) 1 + contactOptions.size else 0)
                val target = catalogCategoryHeaderIndex(
                    listState.layoutInfo.totalItemsCount,
                    trailingItems,
                    filteredCustomerPlans.size
                )
                listState.animateScrollToItem(target)
            }
        }
        when{
            loading->ProductLoading(Modifier.padding(padding))
            error!=null->ProductError(Modifier.padding(padding),::load)
            else->LazyColumn(state=listState, modifier=Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),contentPadding=PaddingValues(top=16.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                credits?.let { balance ->
                    item {
                        Surface(shape=RoundedCornerShape(24.dp), tonalElevation=6.dp, modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Surface(Modifier.size(48.dp), shape=RoundedCornerShape(16.dp), color=MaterialTheme.colorScheme.primaryContainer) { Box(contentAlignment=Alignment.Center) { BqLogo(34.dp) } }
                                    Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                        Text("Your credits", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                                        Text("One balance for every AI feature", color=MaterialTheme.colorScheme.onSurfaceVariant, style=MaterialTheme.typography.bodySmall)
                                    }
                                    BqStatusBadge("LIVE")
                                }
                                Text(formatCredits(balance.optDouble("available", 0.0)), style=MaterialTheme.typography.displaySmall, fontWeight=FontWeight.Bold, color=MaterialTheme.colorScheme.primary)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Surface(Modifier.weight(1f), shape=RoundedCornerShape(16.dp), color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Column(Modifier.padding(14.dp)) {
                                            Text("Available", style=MaterialTheme.typography.labelLarge)
                                            Text(formatCredits(balance.optDouble("available", 0.0)), fontWeight=FontWeight.Bold)
                                        }
                                    }
                                    Surface(Modifier.weight(1f), shape=RoundedCornerShape(16.dp), color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Column(Modifier.padding(14.dp)) {
                                            Text("Trial credits", style=MaterialTheme.typography.labelLarge)
                                            Text(formatCredits(balance.optDouble("trial", 0.0)), fontWeight=FontWeight.Bold)
                                        }
                                    }
                                }
                                balance.optString("trialExpiresAt").takeIf { it.isNotBlank() && it != "null" }?.let {
                                    Text("Trial expiry: " + it.take(10), color=MaterialTheme.colorScheme.onSurface)
                                }
                                val voicePrice = livePricing?.optJSONObject("liveVoice")
                                val cameraPrice = livePricing?.optJSONObject("liveCamera")
                                if (voicePrice != null) Text("Live Voice: " + livePriceLabel(voicePrice), color=MaterialTheme.colorScheme.primary, style=MaterialTheme.typography.bodyMedium)
                                if (cameraPrice != null) Text("Live Camera: " + livePriceLabel(cameraPrice), color=MaterialTheme.colorScheme.primary, style=MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } ?: item {
                    ProductNotice("Your credits", "Sign in to see your shared BQ Sqwad AI balance, trial credits and expiry.")
                }
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Credit packs", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                        Text("Buy credits for eligible AI features. Store prices are localized by Google Play.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (creditPacks.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("Credit packs are loading, tap to retry", fontWeight=FontWeight.SemiBold)
                                Button(onClick=::load, modifier=Modifier.fillMaxWidth()) { Text("Retry credit packs") }
                            }
                        }
                    }
                } else {
                    items(creditPacks, key={ "credit-pack:" + it.id }) { plan ->
                        LivePlanCard(
                            plan,
                            catalog.filter { it.planId == plan.id && it.enabled },
                            playProducts,
                            billing,
                            onContact = { contactPlan = ContactSelection(plan.id, plan.name, "custom", plan.features) },
                            onStatus = { status = it }
                        )
                    }
                }
                stickyHeader(key="catalog-categories") {
                    Surface(color=MaterialTheme.colorScheme.surface, tonalElevation=4.dp, modifier=Modifier.fillMaxWidth()) {
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp), contentPadding=PaddingValues(vertical=8.dp)) {
                            item {
                                FilterChip(selected=selectedCategory=="credits", onClick={
                                    selectedCategory="credits"
                                    scope.launch { listState.animateScrollToItem(1) }
                                }, label={Text("Credits")})
                            }
                            item {
                                FilterChip(selected=selectedCategory=="all", onClick={selectedCategory="all"}, label={Text("All")})
                            }
                            items(categorySpecs, key={it.first}) { category ->
                                FilterChip(
                                    selected=selectedCategory==category.first,
                                    onClick={ planSearch = ""; selectedCategory = category.first },
                                    colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFF1565C0), selectedLabelColor=Color.White),
                                    label={Text(if(category.first=="business") "Business AI" else category.second, maxLines=1)}
                                )
                            }
                        }
                    }
                }
                if(offline)item{ProductNotice("Offline mode","Showing the last successfully synced catalog. Purchase actions remain controlled by Google Pay.")}
                item{Surface(shape=RoundedCornerShape(20.dp),tonalElevation=3.dp,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){BqLogo(56.dp);Text("Your AI. One connected ecosystem.",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Explore BQ Sqwad AI workspaces, compare clear plan options, and use one shared credit balance across eligible AI tools. Secure in-app purchases are handled by Google Pay.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium);if(plans.isNotEmpty())Text("${plans.size} available plans",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)}}}
                if(plans.isEmpty()) item {
                    ProductNotice(
                        "No plans configured yet",
                        "No active plans are available right now. Please refresh this screen or try again later. Google Pay offers are configured separately from the BQ Sqwad AI catalog."
                    )
                }
                if(status.isNotBlank())item{Text(status,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                salesNotice?.let { notice -> item { ProductNotice("Sales update", notice) } }
                item {
                    OutlinedButton(
                        onClick = {
                            billing?.restorePurchases()
                            status = "Checking your Google Pay purchases and restoring verified access…"
                        },
                        enabled = billing?.isReady() == true,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Restore purchases") }
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
                                Text("Google Pay subscription", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                                Text(
                                    if (autoRenew) "Auto-renewal is ON. Google Pay will renew this subscription unless you turn it off there."
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
                                                status = "Your Play subscriptions page opened. Turn off auto-renewal there; your access remains until the paid period ends."
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
                                    if (autoRenew) "Turn off auto-renewal opens the Play subscriptions page. BQ Sqwad AI will refresh your verified access after the change." else "Auto-renewal is currently off. You can manage or resubscribe through the Play subscriptions page. BQ Sqwad AI will refresh your verified access after the change.",
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
                if (plans.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                BqLogo(42.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("BQ Sqwad AI plans", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("Clear options by workspace. All eligible AI tools share one credit balance.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    item { Text("Browse plans", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) }
                    item {
                        OutlinedTextField(
                            value = planSearch,
                            onValueChange = { planSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Search plans") },
                            placeholder = { Text("Business, TruthGuard, Learning…") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                        )
                    }
                    if (filteredCustomerPlans.isEmpty()) {
                        item {
                            Text(
                                if (customerPlanModules.isEmpty()) "No live plans are configured yet. Refresh the catalog to check again."
                                else "No plans match your search. Try another name or category.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        val moduleTitles = categorySpecs.associate { it.first to it.second }
                        val categoryForModule: (String) -> String = { module ->
                            categorySpecs.firstOrNull { spec -> module.lowercase(java.util.Locale.ROOT) in spec.third }?.first
                                ?: "other"
                        }
                        filteredCustomerPlans.groupBy { categoryForModule(it.module) }
                            .toList()
                            .sortedWith(compareBy<Pair<String, List<LivePlan>>> {
                                categorySpecs.indexOfFirst { spec -> spec.first == it.first }.let { index -> if (index < 0) Int.MAX_VALUE else index }
                            }.thenBy { it.first })
                            .forEach { (categoryKey, categoryPlans) ->
                                item(key = "category:" + categoryKey) {
                                    Text(
                                        moduleTitles[categoryKey] ?: "Other plans",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                categoryPlans.sortedWith(compareBy<LivePlan> { it.sortOrder }.thenBy { it.name.lowercase(java.util.Locale.ROOT) }).forEach { plan ->
                                    item(key = "plan:" + plan.id) {
                                        LivePlanCard(
                                            plan,
                                            catalog.filter { it.planId == plan.id && it.enabled },
                                            playProducts,
                                            billing,
                                            onContact = { contactPlan = ContactSelection(plan.id, plan.name, "custom", plan.features) },
                                            onStatus = { status = it }
                                        )
                                    }
                                }
                            }
                    }
                }
                item {
                    Text("BQ Sqwad AI Feature Directory", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Browse registered core workspaces and advanced capabilities. A registered name does not by itself guarantee that its live service is configured or available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { showAllCapabilities = !showAllCapabilities }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showAllCapabilities) "Hide feature directory" else "Show all AI features")
                    }
                }
                if (showAllCapabilities) {
                    item { Text("Core workspaces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(NativeFeatureRegistry.core, key = { "core-" + it.id }) { feature ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(feature.title, fontWeight = FontWeight.SemiBold)
                                Text(feature.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item { Text("Advanced capability registry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(NativeFeatureRegistry.advanced, key = { "advanced-" + it.id }) { feature ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(feature.title, fontWeight = FontWeight.SemiBold)
                                Text(feature.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Registered capability — live availability must be verified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
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
private fun humanizePlanLimit(key: String): String {
    val known = mapOf(
        "aiMessagesPerMonth" to "AI messages per month",
        "storageGb" to "Storage (GB)",
        "storageTb" to "Storage (TB)",
        "teamMembers" to "Team members",
        "socialChannels" to "Social channels",
        "trainingUploads" to "Training uploads",
        "assistants" to "Assistants",
        "everything" to "Included capacity",
        "conversationsPerMonth" to "Conversations per month"
    )
    return known[key] ?: key
        .replace(Regex("([a-z0-9])([A-Z])")) { match -> match.groupValues[1] + " " + match.groupValues[2] }
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
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
    val supportedPeriods = if (plan.playEligible) {
        playEntries.mapNotNull { it.billingPeriod?.takeIf { period -> period in setOf("monthly", "yearly", "term", "weekly") } }
            .distinct()
    } else {
        plan.price.keys.filter { it in setOf("monthly", "yearly", "term", "weekly") }
    }
    var selectedPeriod by remember(plan.id) { mutableStateOf("") }
    LaunchedEffect(plan.id, supportedPeriods) {
        if (selectedPeriod !in supportedPeriods) {
            selectedPeriod = supportedPeriods.firstOrNull { it == "monthly" }
                ?: supportedPeriods.firstOrNull { it == "yearly" }
                ?: supportedPeriods.firstOrNull().orEmpty()
        }
    }
    val selectedEntry = playEntries.firstOrNull { it.basePlanId == selectedPeriod || it.billingPeriod == selectedPeriod }
    val selectedProduct = selectedEntry?.let { entry -> playProducts.firstOrNull { it.productId == entry.productId } }
    val isFreePlan = plan.price.containsKey("free") || plan.paymentChannel == "free"
    val isContactSales = plan.contactSales || plan.paymentChannel == "contact_sales"
    val canPurchase = plan.playEligible && selectedEntry != null && selectedProduct != null && billing?.isReady() == true
    val purchaseBlockReason = when {
        isFreePlan || isContactSales || canPurchase -> null
        plan.paymentChannel == "website" -> "This plan is only purchasable through the website, not inside this Android app."
        !plan.playEligible -> "This plan is not enabled for in-app purchase yet."
        selectedEntry == null -> "No active BQ Sqwad AI purchase option is mapped to this plan, so Google Pay checkout is unavailable."
        selectedProduct == null -> "Google Pay did not return the mapped purchase option for this device or account. Refresh the catalog before purchasing."
        billing?.isReady() != true -> "Google Pay checkout is still connecting. Refresh the catalog and try again."
        else -> "This plan cannot be purchased in the app right now."
    }
    val moduleLabel = when (plan.module) {
        "business" -> "Business AI"
        "business-addon" -> "Business add-on"
        "learning" , "learning-capability" -> "Learning Studio"
        "learning-exam-addon" -> "Learning Studio exam add-on"
        "truthguard" -> "TruthGuard AI"
        "life-mathis" -> "Life Mathis"
        "companion" -> "AI Companion"
        "organization" -> "Organization AI / Studio"
        "frontier" -> "Frontier Lab"
        "data-analysis" -> "Data Analysis"
        "credits" -> "Credits & Top-ups"
        else -> "BQ Sqwad AI"
    }
    val storePriceText = selectedEntry?.let { entry ->
        when (entry.productType) {
            ProductType.SUBS -> selectedProduct?.subscriptionOfferDetails
                ?.firstOrNull { offer -> entry.basePlanId.isNullOrBlank() || offer.basePlanId == entry.basePlanId }
                ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            ProductType.INAPP -> selectedProduct?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
            else -> null
        }
    }
    // Never relabel the fallback USD catalog amounts as Naira. Use Play's localized
    // formatted price first, then an explicitly configured priceNgn value from the server.
    val displayPrices = if (plan.playEligible && supportedPeriods.isNotEmpty()) {
        plan.priceNgn.entries.filter { it.key in supportedPeriods }
    } else {
        plan.priceNgn.entries
    }
    val priceText = when {
        isContactSales -> "Contact Sales"
        isFreePlan -> "Free"
        plan.playEligible && !storePriceText.isNullOrBlank() && selectedEntry?.productType == ProductType.SUBS ->
            (selectedPeriod.replaceFirstChar { it.uppercase() } + ": " + storePriceText)
        plan.playEligible && !storePriceText.isNullOrBlank() -> storePriceText
        plan.playEligible -> "Price not configured"
        else -> displayPrices.joinToString(" • ") { (period, value) ->
            val numeric = value.toDoubleOrNull()
            val shown = numeric?.let { String.format(java.util.Locale.US, "₦%,.2f", it) }
                ?: value.takeIf { it.contains("₦") }
                ?: "Price not configured"
            period.replaceFirstChar { it.uppercase() } + ": " + shown
        }.ifBlank { "Price not configured" }
    }
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
                    Text(moduleLabel, style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(plan.popular) BqStatusBadge("POPULAR")
            }
            if (supportedPeriods.size > 1) {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(supportedPeriods) { period ->
                        FilterChip(selected=selectedPeriod == period, onClick={selectedPeriod=period}, label={Text(period.replaceFirstChar{it.uppercase()})})
                    }
                }
            }
            Text(priceText, style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold, color=MaterialTheme.colorScheme.primary)
            if(plan.trialEligible && plan.trialDays > 0) {
                Text(plan.trialDays.toString()+"-day free trial", fontWeight=FontWeight.SemiBold)
            }
            if (plan.features.isNotEmpty()) {
                Text("Included in this plan", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                plan.features.forEach { Text("• " + it, style=MaterialTheme.typography.bodySmall) }
            } else {
                Text(
                    "Feature details are not configured for this item yet. Confirm availability with BQ Sqwad AI support before purchasing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (plan.limits.isNotEmpty()) {
                Text("Plan capacity", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                plan.limits.forEach { (key, value) ->
                    Text(
                        humanizePlanLimit(key) + ": " + value,
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isFreePlan) {
                Text("Included at no cost. No purchase is required.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                purchaseBlockReason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        when {
                            isContactSales -> onContact()
                            canPurchase -> {
                                val result = billing!!.launchPurchase(selectedProduct!!.productId, selectedEntry!!.basePlanId)
                                onStatus(
                                    if (result?.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK)
                                        "Purchase started for " + plan.id + "/" + (selectedEntry!!.basePlanId ?: selectedPeriod) + ". Access unlocks only after server verification."
                                    else result?.debugMessage?.ifBlank { "Purchase could not be started." } ?: "Purchase could not be started."
                                )
                            }
                            else -> onStatus(purchaseBlockReason ?: "This plan is not currently purchasable on this device.")
                        }
                    },
                    enabled = isContactSales || canPurchase,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            isContactSales -> "Contact Sales"
                            canPurchase -> "Continue with Google Pay"
                            plan.playEligible && selectedEntry == null -> "Unavailable — product not mapped"
                            plan.playEligible && selectedProduct == null -> "Unavailable — product missing"
                            plan.playEligible && billing?.isReady() != true -> "Checkout loading…"
                            plan.paymentChannel == "website" -> "Website purchase only"
                            else -> "Not available in-app"
                        }
                    )
                }
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
    val root=catalogPayload(body)
    val a=root.optJSONArray("publicContactSales")?:root.optJSONObject("catalog")?.optJSONArray("publicContactSales")?:JSONArray()
    return List(a.length()){i->
        val x=a.getJSONObject(i)
        val f=x.optJSONArray("features")?:JSONArray()
        val l=x.optJSONObject("limits")?:JSONObject()
        val limits=mutableMapOf<String,String>(); l.keys().forEach{k->limits[k]=l.optString(k)}
        PublicContactOption(x.optString("name"),x.optString("module"),x.optString("billingPeriod"),x.optString("price"),List(f.length()){n->f.optString(n)}.filter{it.isNotBlank()},limits)
    }.filter{it.name.isNotBlank()}
}

private fun catalogPayload(body: JSONObject): JSONObject {
    val wrapped = body.optJSONObject("data") ?: body.optJSONObject("result")
    return if (wrapped != null && (
        wrapped.has("plans") || wrapped.has("catalog") || wrapped.has("sections") ||
            wrapped.has("products") || wrapped.has("features") || wrapped.has("bindings")
    )) wrapped else body
}

private fun parseCatalog(body:JSONObject):List<PlayCatalogEntry>{
    val root = catalogPayload(body)
    val a=root.optJSONArray("products") ?: root.optJSONObject("catalog")?.optJSONArray("products") ?: JSONArray()
    val visiblePlanIds = parsePlans(root).map { it.id }.toSet()
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
    }.filter{it.productId.isNotBlank() && it.planId in visiblePlanIds}
}

private fun parsePlans(body:JSONObject):List<LivePlan>{
    // Accept the native endpoint and older cached/wrapped catalog envelopes,
    // including API clients that wrap successful JSON responses in data/result.
    val root = catalogPayload(body)
    val nested = root.optJSONObject("catalog")
    val sections = root.optJSONObject("sections") ?: nested?.optJSONObject("sections")
    val sectionPlans = JSONArray().apply {
        val keys = sections?.keys()
        while (keys != null && keys.hasNext()) {
            val section = sections.optJSONArray(keys.next()) ?: continue
            for (index in 0 until section.length()) {
                section.optJSONObject(index)?.let { put(it) }
            }
        }
    }
    val a = root.optJSONArray("plans")
        ?: nested?.optJSONArray("plans")
        ?: sectionPlans
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
            run {
                val priceObject=p.optJSONObject("priceNgn") ?: JSONObject()
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
            p.optBoolean("popular",false),
            p.optInt("sortOrder",0)
        )}.filter { plan ->
            val canonicalCreditPack = plan.id.matches(Regex("credit(100|250|500|1000)")) ||
                plan.name.matches(Regex("(?i)(100|250|500|1,000) credits"))
            val searchable = (plan.id + " " + plan.name + " " + plan.module)
                .lowercase(java.util.Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
            val compact = searchable.replace(" ", "")
            val featureSpecificLiveCredit =
                (searchable.contains("live") && searchable.contains("credit")) ||
                Regex("\\bai live m\\b").containsMatchIn(searchable) ||
                compact.contains("ailivem")
            val anyNonCanonicalCreditPlan =
                Regex("(^| )credits?( |$)").containsMatchIn(searchable) && !canonicalCreditPack
            plan.id.isNotBlank() && !featureSpecificLiveCredit && !anyNonCanonicalCreditPlan &&
                (!plan.module.equals("credits", ignoreCase = true) || canonicalCreditPack)
        }.sortedWith(compareBy<LivePlan> { it.sortOrder }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
}
private fun formatCredits(value: Double): String = if (value % 1.0 == 0.0) String.format(java.util.Locale.US, "%,.0f", value) else String.format(java.util.Locale.US, "%,.2f", value)

@Composable private fun ProductLoading(modifier:Modifier){Column(modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){repeat(4){Card(Modifier.fillMaxWidth().height(132.dp),shape=RoundedCornerShape(18.dp)){}}}}
@Composable private fun ProductError(modifier:Modifier,retry:()->Unit){Column(modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){BqLogo(64.dp);Spacer(Modifier.height(16.dp));Text("Products unavailable",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("The BQ Sqwad AI team is working to restore this service. Please try again shortly.",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(16.dp));Button(onClick=retry){Text("Try again")}}}
@Composable private fun ProductNotice(title:String,body:String){Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

private fun parseFeatures(body:JSONObject):List<CatalogFeature>{
    val root=catalogPayload(body)
    val a=root.optJSONArray("features")?:root.optJSONObject("catalog")?.optJSONArray("features")?:JSONArray()
    return List(a.length()){i -> val x=a.getJSONObject(i); CatalogFeature(x.optString("id"),x.optString("name"),x.optString("description"),x.optString("group"),x.optBoolean("comingSoon",false))}.filter{it.id.isNotBlank()}
}
private fun parseBindings(body:JSONObject):List<FeatureBinding>{
    val root=catalogPayload(body)
    val a=root.optJSONArray("bindings")?:root.optJSONObject("catalog")?.optJSONArray("bindings")?:JSONArray()
    return List(a.length()){i -> val x=a.getJSONObject(i); FeatureBinding(x.optString("planId"),x.optString("featureId"),x.optString("state","unavailable"),if(x.isNull("quota"))null else x.optString("quota"))}.filter{it.featureId.isNotBlank()}
}