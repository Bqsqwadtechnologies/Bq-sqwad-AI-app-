package com.bqsqwad.ai.core

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class PlayCatalogItem(
    val planId: String,
    val productId: String,
    val basePlanId: String?,
    val offerId: String?,
    val trialOfferId: String?,
    val productType: String,
    val enabled: Boolean,
    val consumable: Boolean
)

data class PlayPurchaseState(
    val status: String,
    val message: String
)

class PlayBillingClient(
    private val activity: Activity,
    private val onPurchaseState: (PlayPurchaseState) -> Unit = {},
    private val catalogContext: String? = null
) : BillingClientStateListener {

    private val baseUrl = "${ApiConfig.baseUrl}"
    private val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = BillingClient.newBuilder(activity)
        .setListener { result, purchases ->
            handlePurchasesUpdated(result, purchases)
        }
        .enablePendingPurchases(
            com.android.billingclient.api.PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    private val details = mutableMapOf<String, ProductDetails>()
    private val catalog = mutableMapOf<String, MutableList<PlayCatalogItem>>()
    private var connected = false
    private var connecting = false
    private var retryCount = 0
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var catalogQueryComplete = false

    fun hasCompletedCatalogQuery(): Boolean = catalogQueryComplete

    fun start() {
        if (client.isReady) {
            connected = true
            queryCatalog()
            restorePurchases()
        } else if (!connecting) {
            connecting = true
            client.startConnection(this)
        }
    }

    /** Refresh the server-backed Play mapping after sign-in or a catalog refresh. */
    fun refreshCatalog() {
        if (connected && client.isReady) queryCatalog() else start()
    }

    fun close() {
        retryHandler.removeCallbacksAndMessages(null)
        client.endConnection()
        connected = false
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        connecting = false
        connected = result.responseCode == BillingClient.BillingResponseCode.OK
        if (connected) retryCount = 0
        if (!connected) {
            catalogQueryComplete = true
            onPurchaseState(PlayPurchaseState("catalog_failed", BrandCopy.checkout(friendlyBillingMessage(result.responseCode))))
        }
        if (connected) {
            queryCatalog()
            restorePurchases()
        } else {
            reportBillingDiagnostic(billingDiagnosticCode(result.responseCode))
            if (result.responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE || result.responseCode == BillingClient.BillingResponseCode.NETWORK_ERROR) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (retryCount >= 4) return
        val delayMs = (1500L shl retryCount).coerceAtMost(15_000L)
        retryCount++
        retryHandler.postDelayed({ if (!client.isReady && !connecting) start() }, delayMs)
    }

    override fun onBillingServiceDisconnected() {
        connected = false
        connecting = false
        catalogQueryComplete = true
        onPurchaseState(PlayPurchaseState("catalog_failed", BrandCopy.checkout("Google Play billing disconnected. Check your connection and retry.")))
        reportBillingDiagnostic("billing_service_disconnected")
        scheduleReconnect()
    }

    private fun billingDiagnosticCode(responseCode: Int): String = when (responseCode) {
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "billing_service_unavailable"
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "billing_network_error"
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "billing_unavailable"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "billing_item_unavailable"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "billing_developer_error"
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "billing_feature_not_supported"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "billing_item_already_owned"
        else -> "billing_response_unknown"
    }

    private fun reportBillingDiagnostic(safeCode: String, httpStatus: Int = 0) {
        val refCode = java.util.UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        diagnosticsScope.launch {
            runCatching {
                WorkspaceApiClient().reportClientDiagnostic(JSONObject().apply {
                    put("refCode", refCode)
                    put("route", "/play/catalog")
                    put("step", "billing")
                    put("httpStatus", httpStatus.coerceIn(0, 599))
                    put("safeCode", safeCode.filter { it.isLetterOrDigit() || it in "_.-" }.take(80))
                    put("appVersion", com.bqsqwad.ai.BuildConfig.VERSION_NAME.take(32))
                    put("androidVersion", android.os.Build.VERSION.RELEASE.orEmpty().take(32))
                    put("signedIn", FirebaseAuth.getInstance().currentUser != null)
                    // Temporary checkout diagnostics: verify install source, app identity,
                    // uploaded build version and whether live product details reached the client.
                    put("applicationId", activity.packageName.take(100))
                    put("installerPackage", runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            activity.packageManager.getInstallSourceInfo(activity.packageName).installingPackageName
                        } else {
                            @Suppress("DEPRECATION")
                            activity.packageManager.getInstallerPackageName(activity.packageName)
                        }
                    }.getOrNull().orEmpty().take(100))
                    put("versionCode", runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
                        }
                    }.getOrDefault(0L))
                    put("billingReady", client.isReady)
                    put("catalogProductCount", details.size)
                    put("catalogProductIds", details.keys.joinToString(",").take(300))
                })
            }
        }
    }

    private fun friendlyBillingMessage(responseCode: Int): String = when (responseCode) {
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "We couldn't reach the BQ Sqwad AI catalog. Check your internet connection and try again."
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "Purchases aren't available on this device or account."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "These products are not available for this install. Install from the Play testing link with a licensed tester account."
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "We couldn't confirm this purchase setup. Please contact support."
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "Purchases aren't supported on this device."
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "This purchase is already owned. Refresh or restore purchases to verify access."
        BillingClient.BillingResponseCode.USER_CANCELED -> ""
        else -> "Checkout couldn't be completed. Please try again."
    }

    private fun handlePurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) return
        if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            onPurchaseState(PlayPurchaseState("verifying", "This purchase is already owned. Refreshing verified access…"))
            restorePurchases()
            return
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
            reportBillingDiagnostic(billingDiagnosticCode(result.responseCode))
            onPurchaseState(PlayPurchaseState("failed", BrandCopy.checkout(friendlyBillingMessage(result.responseCode))))
            return
        }
        purchases.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                onPurchaseState(PlayPurchaseState("pending", "Payment pending. We will unlock your plan as soon as it is confirmed."))
            } else {
                onPurchaseState(PlayPurchaseState("verifying", "Purchase received. Verifying securely…"))
                verifyWithBackend(purchase)
            }
        }
    }

    fun isReady(): Boolean = connected

    fun productDetails(productId: String): ProductDetails? = details[productId]

    fun availableProducts(): List<ProductDetails> = details.values.toList()

    fun launchPurchase(productId: String): BillingResult? = launchPurchase(productId, null, null)

    fun launchPurchase(productId: String, basePlanId: String?, offerId: String? = null): BillingResult? {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            retryHandler.post { launchPurchase(productId, basePlanId, offerId) }
            onPurchaseState(PlayPurchaseState("pending", "Opening secure checkout…"))
            return null
        }
        if (!client.isReady) {
            start()
            onPurchaseState(PlayPurchaseState("failed", BrandCopy.checkout("Checkout is reconnecting. Please tap Buy again in a moment.")))
            return null
        }
        val product = details[productId] ?: run {
            reportBillingDiagnostic("billing_product_details_missing")
            onPurchaseState(PlayPurchaseState("failed", BrandCopy.checkout("Google Play has not returned purchase details for this item yet. Refresh the catalog and try again.")))
            return null
        }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
        val offerToken = when (product.productType) {
            ProductType.SUBS -> {
                val mapping = catalog[productId].orEmpty().firstOrNull { basePlanId.isNullOrBlank() || it.basePlanId == basePlanId }
                val preferredBasePlan = basePlanId ?: mapping?.basePlanId
                val preferredOfferId = offerId ?: mapping?.offerId ?: mapping?.trialOfferId
                val offersForBasePlan = product.subscriptionOfferDetails?.filter { detail ->
                    preferredBasePlan.isNullOrBlank() || detail.basePlanId == preferredBasePlan
                }.orEmpty()
                // Never silently fall back to a different subscription offer when the
                // BQ catalog selected a specific offer (for example, a trial offer).
                // The wrong token can launch checkout with terms different from the
                // plan the customer selected. Fail closed until the configured offer
                // is actually available from Google Play.
                val offer = if (!preferredOfferId.isNullOrBlank()) {
                    offersForBasePlan.firstOrNull { detail -> detail.offerId == preferredOfferId }
                } else {
                    offersForBasePlan.firstOrNull()
                }
                if (offer == null) {
                    reportBillingDiagnostic(
                        if (offersForBasePlan.isEmpty()) "billing_base_plan_unavailable"
                        else "billing_subscription_offer_mismatch"
                    )
                    onPurchaseState(
                        PlayPurchaseState(
                            "failed",
                            if (offersForBasePlan.isEmpty()) {
                                BrandCopy.checkout("Google Play did not return the selected subscription plan. Refresh the catalog or check the Play Console product setup.")
                            } else {
                                BrandCopy.checkout("The selected subscription offer does not match an offer currently available in Google Play. Checkout was stopped to avoid showing different terms. Please contact support.")
                            }
                        )
                    )
                    return null
                }
                offer.offerToken
            }
            ProductType.INAPP -> product.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
            else -> null
        }
        if (!offerToken.isNullOrBlank()) params.setOfferToken(offerToken)
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params.build()))
            .setObfuscatedAccountId(obfuscatedUid())
            .build()
        val result = client.launchBillingFlow(activity, flow)
        if (result.responseCode != BillingClient.BillingResponseCode.OK &&
            result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED
        ) {
            reportBillingDiagnostic(billingDiagnosticCode(result.responseCode))
            onPurchaseState(PlayPurchaseState("failed", BrandCopy.checkout(friendlyBillingMessage(result.responseCode))))
        }
        return result
    }

    fun restorePurchases() {
        if (!connected) return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()
        ) { _, purchases -> purchases.forEach(::verifyWithBackend) }
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        ) { _, purchases -> purchases.forEach(::verifyWithBackend) }
    }

    private fun queryCatalog() {
        catalogQueryComplete = false
        Thread {
            runCatching {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    catalogQueryComplete = true
                    reportBillingDiagnostic("billing_catalog_requires_sign_in")
                    onPurchaseState(PlayPurchaseState("catalog_failed", "Sign in to your BQ Sqwad AI account to load your plans."))
                    return@runCatching
                }
                val token = Tasks.await(user.getIdToken(false)).token
                if (token.isNullOrBlank()) {
                    catalogQueryComplete = true
                    reportBillingDiagnostic("billing_auth_token_missing")
                    onPurchaseState(PlayPurchaseState("catalog_failed", "Your account could not be verified. Sign in again, then refresh purchase options."))
                    return@runCatching
                }
                val catalogPath = if (catalogContext == "organization") "$baseUrl/play/catalog?context=organization" else "$baseUrl/play/catalog"
                val connection = (URL(catalogPath).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val raw = try {
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) error("Play catalog request failed.")
                    text
                } finally {
                    connection.disconnect()
                }
                val catalogJson = JSONObject(raw).optJSONArray("products") ?: JSONArray()
                val nextCatalog = mutableMapOf<String, MutableList<PlayCatalogItem>>()
                val products = mutableListOf<QueryProductDetailsParams.Product>()
                val seenProducts = mutableSetOf<String>()
                for (i in 0 until catalogJson.length()) {
                    val item = catalogJson.getJSONObject(i)
                    if (!item.optBoolean("enabled", false)) continue
                    val id = item.optString("productId")
                    val type = item.optString("productType")
                    if (id.isBlank() || (type != ProductType.SUBS && type != ProductType.INAPP)) continue
                    nextCatalog.getOrPut(id) { mutableListOf() }.add(
                        PlayCatalogItem(
                            planId = item.optString("planId"),
                            productId = id,
                            basePlanId = item.optString("basePlanId").ifBlank { null },
                            offerId = item.optString("offerId").ifBlank { null },
                            trialOfferId = item.optString("trialOfferId").ifBlank { null },
                            productType = type,
                            enabled = item.optBoolean("enabled", false),
                            consumable = item.optBoolean("consumable", false)
                        )
                    )
                    if (seenProducts.add(id + ":" + type)) {
                        products += QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(type)
                            .build()
                    }
                }
                if (products.isEmpty()) {
                    catalogQueryComplete = true
                    reportBillingDiagnostic("billing_catalog_empty")
                    onPurchaseState(PlayPurchaseState("catalog_failed", BrandCopy.checkout("No Google Play purchase options are available for this account yet. Refresh the catalog or check the Play Console product setup.")))
                    return@runCatching
                }
                client.queryProductDetailsAsync(
                    QueryProductDetailsParams.newBuilder().setProductList(products).build()
                ) { result, queried ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK && queried.productDetailsList.isNotEmpty()) {
                        // Replace mappings/details together only after Play returned a valid
                        // product-details response. Empty or failed refreshes keep the last
                        // valid checkout mapping rather than leaving stale details unmapped.
                        synchronized(catalog) {
                            catalog.clear()
                            nextCatalog.forEach { (id, mappings) -> catalog[id] = mappings }
                            details.clear()
                            queried.productDetailsList.forEach { details[it.productId] = it }
                        }
                        catalogQueryComplete = true
                        onPurchaseState(PlayPurchaseState("catalog_loaded", "Live prices are ready."))
                    } else if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        catalogQueryComplete = true
                        reportBillingDiagnostic(billingDiagnosticCode(result.responseCode))
                        onPurchaseState(PlayPurchaseState("catalog_failed", BrandCopy.checkout(friendlyBillingMessage(result.responseCode))))
                    } else {
                        catalogQueryComplete = true
                        reportBillingDiagnostic("billing_item_unavailable")
                        onPurchaseState(PlayPurchaseState("catalog_failed", BrandCopy.checkout(friendlyBillingMessage(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE))))
                    }
                }
            }.onFailure {
                catalogQueryComplete = true
                reportBillingDiagnostic("billing_catalog_load_failed")
                onPurchaseState(PlayPurchaseState("catalog_failed", BrandCopy.checkout("Google Play purchase options could not be loaded. Check your connection and refresh the catalog.")))
            }
        }.start()
    }

    private fun verifyWithBackend(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            onPurchaseState(PlayPurchaseState("pending", "Payment pending. Your plan will activate after the payment is confirmed."))
            return
        }
        Thread {
            runCatching {
                val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required to verify this purchase.")
                val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable.")
                val products = purchase.products
                if (products.isEmpty()) error("No product was returned for this purchase.")
                val payload = JSONObject()
                    .put("purchaseToken", purchase.purchaseToken)
                    .put("productId", products.first())
                    .put("purchaseTime", purchase.purchaseTime)
                    // Do not claim a base plan from the client during verification.
                    // Google Play's purchase token is authoritative; the backend reads
                    // the verified line item and checks the actual yearly/monthly base plan.
                    .put("basePlanId", "")
                    .put("offerId", "")
                    .put("obfuscatedAccountId", purchase.accountIdentifiers?.obfuscatedAccountId ?: "")
                val connection = (URL("$baseUrl/play/verify").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val raw = try {
                    connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrDefault("Purchase verification failed"))
                    text
                } finally {
                    connection.disconnect()
                }
                val verifiedBody = JSONObject(raw)
                val verified = verifiedBody.optBoolean("ok", false)
                if (!verified) throw IllegalStateException("The server did not confirm this purchase.")

                // The secure backend verifies, grants and acknowledges/consumes the purchase.
                // Do not duplicate that operation from the Android client.
                val walletTopup = verifiedBody.optInt("organizationWalletTopupCents", 0)
                if (walletTopup > 0) {
                    onPurchaseState(PlayPurchaseState("success", "Organization usage balance deposit verified and added."))
                } else {
                    onPurchaseState(PlayPurchaseState("success", "Purchase verified. Your access is now active."))
                }
            }.onFailure {
                // Do not leave the UI in a permanent "verifying" state. Access remains
                // locked until the server verifies the purchase; users can retry restore.
                onPurchaseState(
                    PlayPurchaseState(
                        "failed",
                        "Purchase verification could not be completed. Your access remains locked until verification succeeds. Please try Restore purchases again."
                    )
                )
            }
        }.start()
    }

    private fun obfuscatedUid(): String {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(64)
    }
}
