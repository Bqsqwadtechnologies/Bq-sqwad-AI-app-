package com.bqsqwad.ai.core

sealed class ApiError(message: String) : Exception(message) {
    data object AuthRequired : ApiError("Please sign in to continue.")
    data object ConsentRequired : ApiError("Please accept the required consent before using this feature.")
    data object InsufficientCredits : ApiError("You do not have enough credits for this request.")
    data object CapabilityDisabled : ApiError("This AI capability is currently unavailable.")
    data object RateLimited : ApiError("Too many requests. Please try again shortly.")
    data object NotMigrated : ApiError("This feature is temporarily unavailable. Please try again later.")
    data object ProviderUnavailable : ApiError("The AI service is temporarily unavailable. Please try again shortly.")
    data class Unknown(val status: Int, val detail: String? = null) : ApiError("BQ Sqwad AI couldn't complete that request. Please try again.")
}

fun apiErrorFrom(status: Int, code: String?): ApiError = when (code?.lowercase()) {
    "auth_required" -> ApiError.AuthRequired
    "consent_required" -> ApiError.ConsentRequired
    "insufficient_credits" -> ApiError.InsufficientCredits
    "capability_disabled" -> ApiError.CapabilityDisabled
    "rate_limited" -> ApiError.RateLimited
    "not_migrated" -> ApiError.NotMigrated
    "provider_unavailable" -> ApiError.ProviderUnavailable
    else -> ApiError.Unknown(status)
}
