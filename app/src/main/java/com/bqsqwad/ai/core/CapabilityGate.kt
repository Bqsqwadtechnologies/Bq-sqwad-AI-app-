package com.bqsqwad.ai.core

import com.bqsqwad.ai.config.SafeRemoteConfigStore

class CapabilityGate(private val config: SafeRemoteConfigStore) {
    fun canUse(featureId: String, entitlement: FeatureEntitlement?): Boolean {
        if (entitlement?.enabled != true) return false
        return config.featureEnabled(featureId)
    }

    fun canUseProvider(providerId: String): Boolean = config.providerEnabled(providerId)
}
