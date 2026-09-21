package com.bqsqwad.ai.core

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
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
) : BillingClient.BillingClientStateListener, BillingClient.PurchasesUpdatedListener {

    private val baseUrl = "https://europe-west1-" +
        FirebaseApp.getInstance().options.projectId + ".cloudfunctions.net/bqSqwadApi"

    private val client = BillingClient.newBuilder(activity)
        .setListener(this)
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
        else queryCatalog()
    }

    fun close() {
        client.endConnection()
        connected = false
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        connected = result.responseCode == BillingClient.BillingResponseCode.OK
        if (connected) queryCatalog()
    }

    override fun onBillingServiceDisconnected() {
        connected = false
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
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

    fun launchPurchase(productId: String): BillingResult? {
        val product = details[productId] ?: return null
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
        val offerToken = when (product.productType) {
            ProductType.SUBS -> {
                val preferredBasePlan = catalog[productId]?.basePlanId
                val offer = product.subscriptionOfferDetails?.firstOrNull { detail ->
                    preferredBasePlan.isNullOrBlank() || detail.basePlanId == preferredBasePlan
                }
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
            BillingClient.QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()
        ) { _, purchases -> purchases.forEach(::verifyWithBackend) }
        client.queryPurchasesAsync(
            BillingClient.QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
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
                val catalog = JSONObject(raw).optJSONArray("products") ?: JSONArray()
                val products = mutableListOf<QueryProductDetailsParams.Product>()
                for (i in 0 until catalog.length()) {
                    val item = catalog.getJSONObject(i)
                    if (!item.optBoolean("enabled", false)) continue
                    val id = item.optString("productId")
                    val type = item.optString("productType")
                    if (id.isBlank() || (type != ProductType.SUBS && type != ProductType.INAPP)) continue
                    catalog[id] = PlayCatalogItem(
                        planId = item.optString("planId"),
                        productId = id,
                        basePlanId = item.optString("basePlanId").ifBlank { null },
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
                onPurchaseState(PlayPurchaseState("success", "Purchase successful. Your entitlement is now active after server verification."))
            }
        }.start()
    }

    private fun obfuscatedUid(): String {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(64)
    }
}
