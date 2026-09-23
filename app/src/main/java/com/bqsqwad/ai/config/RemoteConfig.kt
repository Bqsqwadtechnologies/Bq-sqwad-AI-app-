package com.bqsqwad.ai.config

import com.bqsqwad.ai.core.ProviderRoutingState
import com.bqsqwad.ai.core.WebSearchPricing

data class RemoteConfig(
    val version: Long = 1L,
    val featureAvailability: Map<String, Boolean> = emptyMap(),
    val providerActivation: Map<String, Boolean> = emptyMap(),
    val modelRouting: Map<String, String> = emptyMap(),
    val providerRoutes: Map<String, ProviderRoutingState> = emptyMap(),
    val webSearchPricing: WebSearchPricing = WebSearchPricing(pricingId = "default"),
    val maintenanceMode: Boolean = false,
    val privacyPolicyVersion: String = "1"
)

interface RemoteConfigRepository {
    suspend fun getConfig(): RemoteConfig
}
