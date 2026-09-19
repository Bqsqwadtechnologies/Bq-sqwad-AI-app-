package com.bqsqwad.ai.core

data class ProviderRoute(
    val capability: String,
    val providerId: String,
    val modelId: String,
    val enabled: Boolean
)

class ProviderRouter {
    private var routes: List<ProviderRoute> = emptyList()

    fun replace(routes: List<ProviderRoute>) {
        this.routes = routes
    }

    fun route(capability: String): ProviderRoute? =
        routes.firstOrNull { it.capability == capability && it.enabled }
}
