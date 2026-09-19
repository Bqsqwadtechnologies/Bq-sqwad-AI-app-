package com.bqsqwad.ai.core

data class Account(
    val id: String,
    val email: String,
    val displayName: String,
    val authenticated: Boolean
)

data class FeatureEntitlement(
    val feature: String,
    val enabled: Boolean,
    val reason: String? = null
)

data class ProviderState(
    val providerId: String,
    val displayName: String,
    val enabled: Boolean,
    val primary: Boolean = false,
    val models: List<String> = emptyList()
)

data class CreditBalance(
    val available: Long,
    val reserved: Long = 0,
    val currency: String = "credits"
)

data class PlanSummary(
    val planId: String,
    val name: String,
    val active: Boolean,
    val limits: Map<String, Long> = emptyMap()
)

data class NotificationItem(
    val id: String,
    val title: String,
    val body: String,
    val read: Boolean
)

data class CapabilityAccess(
    val capability: String,
    val enabled: Boolean,
    val subscriptionRequired: Boolean = false,
    val reason: String? = null
)

data class AiAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uri: String? = null
)

interface AuthRepository {
    suspend fun signIn(email: String, credential: String): Account
    suspend fun signOut()
    suspend fun currentAccount(): Account?
}

interface AiGateway {
    suspend fun send(request: AiRequest): AiResponse
}

data class AiRequest(
    val workspace: String,
    val message: String,
    val attachments: List<AiAttachment> = emptyList(),
    val useWebSearch: Boolean = false,
    val generateImage: Boolean = false,
    val generateVideo: Boolean = false,
    val analyzeFiles: Boolean = false
)

data class AiResponse(
    val text: String,
    val providerId: String,
    val modelId: String,
    val usage: Map<String, Long> = emptyMap(),
    val citations: List<String> = emptyList(),
    val incidentId: String? = null
)

interface CreditsRepository {
    suspend fun balance(): CreditBalance
    suspend fun reserve(amount: Long): Boolean
    suspend fun commit(reservationId: String)
    suspend fun reverse(reservationId: String)
}

interface FeatureRepository {
    suspend fun entitlements(accountId: String): List<FeatureEntitlement>
    suspend fun capabilityAccess(accountId: String, capability: String): CapabilityAccess
}

interface NotificationRepository {
    suspend fun registerDeviceToken(token: String)
    suspend fun list(): List<NotificationItem>
}

interface PaymentRepository {
    suspend fun plans(): List<PlanSummary>
    suspend fun purchase(planId: String): String
}

interface AdminRepository {
    suspend fun setProviderEnabled(providerId: String, enabled: Boolean)
    suspend fun setFeatureEnabled(featureId: String, enabled: Boolean)
    suspend fun publishRemoteConfig(): Long
}
