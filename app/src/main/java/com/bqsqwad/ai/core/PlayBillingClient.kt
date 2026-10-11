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
        .build()

    private val details = mutableMapOf<String, ProductDetails>()
    private val catalog = mutableMapOf<String, MutableList<PlayCatalogItem>>()
    private var connected = false

    fun start() {
        if (!client.isReady) client.startConnection(this)
        else {
            queryCatalog()
            restorePurchases()
        }
    }

    fun close() {
        client.endConnection()
        connected = false
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        connected = result.responseCode == BillingClient.BillingResponseCode.OK
        if (connected) {
            queryCatalog()
            restorePurchases()
        }
    }

    override fun onBillingServiceDisconnected() {
        connected = false
    }

    private fun handlePurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            onPurchaseState(PlayPurchaseState("cancelled", "Purchase cancelled. No payment was completed. You can try again whenever you're ready."))
            return
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
            onPurchaseState(PlayPurchaseState("failed", result.debugMessage.ifBlank { "Purchase did not complete." }))
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
        val product = details[productId] ?: return null
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
                offer?.offerToken ?: return null
            }
            ProductType.INAPP -> product.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
            else -> null
        }
        if (!offerToken.isNullOrBlank()) params.setOfferToken(offerToken)
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params.build()))
            .setObfuscatedAccountId(obfuscatedUid())
            .build()
        return client.launchBillingFlow(activity, flow)
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
        Thread {
            runCatching {
                val user = FirebaseAuth.getInstance().currentUser ?: return@runCatching
                val token = Tasks.await(user.getIdToken(false)).token ?: return@runCatching
                val catalogPath = if (catalogContext == "organization") "$baseUrl/play/catalog?context=organization" else "$baseUrl/play/catalog"
                val connection = (URL(catalogPath).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val catalogJson = JSONObject(raw).optJSONArray("products") ?: JSONArray()
                catalog.clear()
                val products = mutableListOf<QueryProductDetailsParams.Product>()
                val seenProducts = mutableSetOf<String>()
                for (i in 0 until catalogJson.length()) {
                    val item = catalogJson.getJSONObject(i)
                    if (!item.optBoolean("enabled", false)) continue
                    val id = item.optString("productId")
                    val type = item.optString("productType")
                    if (id.isBlank() || (type != ProductType.SUBS && type != ProductType.INAPP)) continue
                    catalog.getOrPut(id) { mutableListOf() }.add(
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
                if (products.isEmpty()) return@runCatching
                client.queryProductDetailsAsync(
                    QueryProductDetailsParams.newBuilder().setProductList(products).build()
                ) { result, queried ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queried.productDetailsList.forEach { details[it.productId] = it }
                    }
                }
            }
        }.start()
    }

    private fun verifyWithBackend(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            onPurchaseState(PlayPurchaseState("pending", "Payment pending. We will unlock your plan after Google Play confirms it."))
            return
        }
        Thread {
            runCatching {
                val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in is required to verify this purchase.")
                val token = Tasks.await(user.getIdToken(false)).token ?: error("Authentication token unavailable.")
                val products = purchase.products
                if (products.isEmpty()) error("Google Play returned no product for this purchase.")
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
                connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream.bufferedReader().use { it.readText() }
                if (status !in 200..299) throw IllegalStateException(runCatching { JSONObject(raw).optString("error") }.getOrDefault("Purchase verification failed"))
                val verifiedBody = JSONObject(raw)
                val verified = verifiedBody.optBoolean("ok", false)
                if (!verified) throw IllegalStateException("The server did not confirm this Google Play purchase.")

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
