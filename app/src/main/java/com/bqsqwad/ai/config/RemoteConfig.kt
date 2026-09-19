package com.bqsqwad.ai.config

data class RemoteConfig(
    val version: Long = 1L,
    val featureAvailability: Map<String, Boolean> = emptyMap(),
    val providerActivation: Map<String, Boolean> = emptyMap(),
    val modelRouting: Map<String, String> = emptyMap(),
    val maintenanceMode: Boolean = false,
    val privacyPolicyVersion: String = "1"
)

interface RemoteConfigRepository {
    suspend fun getConfig(): RemoteConfig
}
