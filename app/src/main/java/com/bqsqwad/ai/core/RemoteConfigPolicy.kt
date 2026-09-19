package com.bqsqwad.ai.core

import com.bqsqwad.ai.config.RemoteConfig

class RemoteConfigPolicy {
    fun accept(config: RemoteConfig, nowEpochSeconds: Long, maxAgeSeconds: Long): Boolean {
        if (config.version < 1L) return false
        return nowEpochSeconds >= 0L && maxAgeSeconds >= 0L
    }

    fun featureEnabled(config: RemoteConfig, featureId: String): Boolean =
        config.featureAvailability[featureId] == true

    fun providerEnabled(config: RemoteConfig, providerId: String): Boolean =
        config.providerActivation[providerId] == true
}
