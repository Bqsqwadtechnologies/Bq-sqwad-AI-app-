package com.bqsqwad.ai.core

data class Account(val id:String,val email:String,val displayName:String,val authenticated:Boolean)
data class FeatureEntitlement(val feature:String,val enabled:Boolean,val reason:String?=null)

data class SubscriptionEntitlement(
    val planId:String,val planName:String,val active:Boolean,val status:String,
    val startsAt:String?=null,val endsAt:String?=null,val autoRenew:Boolean=false,
    val capabilities:Set<String> = emptySet()
)

data class ProviderState(
    val providerId:String,
    val displayName:String,
    val enabled:Boolean,
    val primary:Boolean=false,
    val models:List<String> = emptyList(),
    val kind:String? = null,
    val secretConfigured:Boolean = false,
    val publicKeyConfigured:Boolean = false
)
data class ProviderRoutingState(
    val capabilityId:String,
    val primaryProviderId:String,
    val primaryModelId:String? = null,
    val fallbackProviderIds:List<String> = emptyList(),
    val enabled:Boolean = true,
    val creditCostId:String? = null
)
data class AdminProviderCredentialState(
    val providerId:String,
    val secretRef:String? = null,
    val publicRef:String? = null,
    val configured:Boolean = false,
    val lastRotatedAt:String? = null
)

/**
 * Transient credential update. The secretValue must only travel over an authenticated
 * TLS connection to the backend and must never be persisted by the Android client.
 */
data class ProviderSecretUpdate(
    val providerId:String,
    val secretValue:String
)
data class CreditBalance(val available:Long,val reserved:Long=0,val currency:String="credits")
data class PlanSummary(val planId:String,val name:String,val active:Boolean,val limits:Map<String,Long> = emptyMap())
data class NotificationItem(val id:String,val title:String,val body:String,val read:Boolean)

data class CapabilityAccess(
    val capability:String,val enabled:Boolean,val subscriptionRequired:Boolean=false,
    val subscriptionActive:Boolean=false,val planId:String?=null,val reason:String?=null
)

data class AiAttachment(val id:String,val name:String,val mimeType:String,val sizeBytes:Long,val uri:String?=null)
data class CodronControl(
    val enabledByAdmin:Boolean=false,val active:Boolean=false,val trainingEnabled:Boolean=false,
    val version:String?=null,val activatedAt:String?=null
)

enum class StaffRole { MODERATOR, COLLABORATOR, WORKER }
data class StaffMember(
    val id:String,val accountId:String,val email:String,val displayName:String,
    val role:StaffRole,val active:Boolean=true,val permissions:Set<String> = emptySet()
)
data class AdminReport(
    val id:String,val category:String,val subject:String,val description:String,
    val reporterAccountId:String?,val status:String="open",val assignedTo:String?=null,val createdAt:String
)

interface AuthRepository { suspend fun signIn(email:String,credential:String):Account; suspend fun signOut(); suspend fun currentAccount():Account? }
interface AiGateway { suspend fun send(request:AiRequest):AiResponse }
data class AiRequest(
    val workspace:String,val message:String,val attachments:List<AiAttachment> = emptyList(),
    val useWebSearch:Boolean=false,val generateImage:Boolean=false,val generateVideo:Boolean=false,val analyzeFiles:Boolean=false
)
data class AiResponse(
    val text:String,val providerId:String,val modelId:String,val usage:Map<String,Long> = emptyMap(),
    val citations:List<String> = emptyList(),val incidentId:String?=null
)
interface CreditsRepository { suspend fun balance():CreditBalance; suspend fun reserve(amount:Long):Boolean; suspend fun commit(reservationId:String); suspend fun reverse(reservationId:String) }
interface FeatureRepository {
    suspend fun entitlements(accountId:String):List<FeatureEntitlement>
    suspend fun capabilityAccess(accountId:String,capability:String):CapabilityAccess
    suspend fun subscription(accountId:String):SubscriptionEntitlement?
}
interface NotificationRepository { suspend fun registerDeviceToken(token:String); suspend fun list():List<NotificationItem> }
interface PaymentRepository { suspend fun plans():List<PlanSummary>; suspend fun purchase(planId:String):String }
interface AdminProviderRepository {
    suspend fun listProviders():List<ProviderState>
    suspend fun listRoutes():List<ProviderRoutingState>
    suspend fun setPrimaryProvider(capabilityId:String,providerId:String,modelId:String?=null):Boolean
    suspend fun setFallbackProviders(capabilityId:String,providerIds:List<String>):Boolean
    suspend fun setProviderEnabled(providerId:String,enabled:Boolean):Boolean
    suspend fun listCredentialStatus():List<AdminProviderCredentialState>
    suspend fun updateProviderSecret(update:ProviderSecretUpdate):Boolean
    suspend fun setWebSearchPricing(pricing:WebSearchPricing):Boolean
    suspend fun webSearchPricing():WebSearchPricing
}

interface AdminAdvancedRepository {
    suspend fun listAdvancedOptions(): List<AdvancedAdminOption>
    suspend fun updateAdvancedOption(option: AdvancedAdminOption): AdvancedAdminOption
}

interface AdminRepository {
    suspend fun setProviderEnabled(providerId:String,enabled:Boolean)
    suspend fun setFeatureEnabled(featureId:String,enabled:Boolean)
    suspend fun setCodronActive(enabled:Boolean)
    suspend fun setCodronTrainingEnabled(enabled:Boolean)
    suspend fun addStaff(member:StaffMember):Boolean
    suspend fun updateStaff(memberId:String,role:StaffRole,active:Boolean,permissions:Set<String>):Boolean
    suspend fun listStaff():List<StaffMember>
    suspend fun listReports(status:String?=null):List<AdminReport>
    suspend fun updateReport(reportId:String,status:String,assignedTo:String?=null):Boolean
    suspend fun publishRemoteConfig():Long
}