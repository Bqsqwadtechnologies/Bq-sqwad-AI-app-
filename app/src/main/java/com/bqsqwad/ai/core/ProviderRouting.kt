package com.bqsqwad.ai.core

data class ProviderRoute(
    val capability: String,
    val providerId: String,
    val modelId: String,
    val enabled: Boolean,
    val fallbackProviderIds: List<String> = emptyList(),
    val creditCostId: String? = null
)

class ProviderRouter {
    private var routes: List<ProviderRoute> = emptyList()

    fun replace(routes: List<ProviderRoute>) {
        this.routes = routes
    }

    fun route(capability: String): ProviderRoute? =
        routes.firstOrNull { it.capability == capability && it.enabled }

    fun candidates(capability: String): List<String> {
        val route = route(capability) ?: return emptyList()
        return listOf(route.providerId) + route.fallbackProviderIds
    }
}
