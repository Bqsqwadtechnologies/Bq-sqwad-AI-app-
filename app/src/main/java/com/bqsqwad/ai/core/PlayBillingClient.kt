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
    private val onPurchaseState: (PlayPurchaseState) -> Unit = {}
) : BillingClientStateListener {

    private val baseUrl = "https://europe-west1-" +
        FirebaseApp.getInstance().options.projectId + ".cloudfunctions.net/bqSqwadApi"

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
    private val catalog = mutableMapOf<String, PlayCatalogItem>()
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
                val preferredBasePlan = basePlanId ?: catalog[productId]?.basePlanId
                val preferredOfferId = offerId ?: catalog[productId]?.offerId ?: catalog[productId]?.trialOfferId
                val offersForBasePlan = product.subscriptionOfferDetails?.filter { detail ->
                    preferredBasePlan.isNullOrBlank() || detail.basePlanId == preferredBasePlan
                }.orEmpty()
                val offer = offersForBasePlan.firstOrNull { detail ->
                    !preferredOfferId.isNullOrBlank() && detail.offerId == preferredOfferId
                } ?: offersForBasePlan.firstOrNull()
                offer?.offerToken
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
                val connection = (URL("$baseUrl/play/catalog").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val catalogJson = JSONObject(raw).optJSONArray("products") ?: JSONArray()
                val products = mutableListOf<QueryProductDetailsParams.Product>()
                for (i in 0 until catalogJson.length()) {
                    val item = catalogJson.getJSONObject(i)
                    if (!item.optBoolean("enabled", false)) continue
                    val id = item.optString("productId")
                    val type = item.optString("productType")
                    if (id.isBlank() || (type != ProductType.SUBS && type != ProductType.INAPP)) continue
                    catalog[id] = PlayCatalogItem(
                        planId = item.optString("planId"),
                        productId = id,
                        basePlanId = item.optString("basePlanId").ifBlank { null },
                        offerId = item.optString("offerId").ifBlank { null },
                        trialOfferId = item.optString("trialOfferId").ifBlank { null },
                        productType = type,
                        enabled = item.optBoolean("enabled", false),
                        consumable = item.optBoolean("consumable", false)
                    )
                    products += QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(type)
                        .build()
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
                val user = FirebaseAuth.getInstance().currentUser ?: return@runCatching
                val token = Tasks.await(user.getIdToken(false)).token ?: return@runCatching
                val products = purchase.products
                if (products.isEmpty()) return@runCatching
                val payload = JSONObject()
                    .put("purchaseToken", purchase.purchaseToken)
                    .put("productId", products.first())
                    .put("purchaseTime", purchase.purchaseTime)
                    .put("basePlanId", purchase.products.firstOrNull()?.let { productId -> catalog[productId]?.basePlanId } ?: "")
                    .put("offerId", purchase.products.firstOrNull()?.let { productId -> catalog[productId]?.offerId } ?: "")
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
                val verified = runCatching { JSONObject(raw).optBoolean("ok", false) }.getOrDefault(false)
                if (!verified) throw IllegalStateException("The server did not confirm this Google Play purchase.")

                // Google Play requires non-consumable/subscription purchases to be acknowledged.
                // Credit products are consumable and must be consumed after successful server verification.
                // Never acknowledge or consume before backend verification succeeds.
                val catalogItem = products.firstOrNull()?.let { catalog[it] }
                if (catalogItem?.consumable == true && catalogItem.productType == ProductType.INAPP) {
                    client.consumeAsync(
                        com.android.billingclient.api.ConsumeParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                    ) { consumeResult ->
                        if (consumeResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            onPurchaseState(PlayPurchaseState("warning", "Purchase was verified, but Google Play could not finish the credit delivery step. Restore purchases after the connection is available."))
                        } else {
                            onPurchaseState(PlayPurchaseState("success", "Purchase verified and credits delivered."))
                        }
                    }
                } else if (!purchase.isAcknowledged) {
                    client.acknowledgePurchase(
                        com.android.billingclient.api.AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                    ) { acknowledgeResult ->
                        if (acknowledgeResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            onPurchaseState(PlayPurchaseState("success", "Purchase verified and acknowledged. Your entitlement is now active."))
                        } else {
                            onPurchaseState(PlayPurchaseState("warning", "Purchase was verified, but Google Play acknowledgement is still pending. Restore purchases after the connection is available."))
                        }
                    }
                } else {
                    onPurchaseState(PlayPurchaseState("success", "Purchase verified. Your entitlement is now active."))
                }
            }
        }.start()
    }

    private fun obfuscatedUid(): String {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(64)
    }
}
