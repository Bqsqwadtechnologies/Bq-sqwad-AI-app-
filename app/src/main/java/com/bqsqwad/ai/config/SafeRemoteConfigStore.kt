package com.bqsqwad.ai.config

class SafeRemoteConfigStore(
    private val repository: RemoteConfigRepository,
    private val isConfigFresh: (RemoteConfig) -> Boolean
) {
    private var cached: RemoteConfig? = null

    suspend fun refresh(): RemoteConfig? {
        val incoming = runCatching { repository.getConfig() }.getOrNull() ?: return cached
        if (!isConfigFresh(incoming)) return cached
        cached = incoming
        return incoming
    }

    fun current(): RemoteConfig? = cached
    fun featureEnabled(featureId: String): Boolean = cached?.featureAvailability?.get(featureId) == true
    fun providerEnabled(providerId: String): Boolean = cached?.providerActivation?.get(providerId) == true
}
