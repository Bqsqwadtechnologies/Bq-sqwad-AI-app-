package com.bqsqwad.ai.ui

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.NoRouteToHostException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class BqIssueKind { OFFLINE, NOT_SIGNED_IN, SESSION_EXPIRED, EMAIL_NOT_VERIFIED, CONSENT_REQUIRED, OUT_OF_CREDITS, PLAN_REQUIRED, FEATURE_OFF, RATE_LIMITED, FILE_TOO_LARGE, PERMISSION_DENIED, SERVER_BUSY, CANCELLED, UNKNOWN }
enum class BqIssueAction { NONE, RETRY, SIGN_IN, VERIFY_EMAIL, REVIEW, BUY_CREDITS, VIEW_PLANS, OPEN_SETTINGS, REPORT_PROBLEM }
data class BqIssue(val kind: BqIssueKind, val title: String, val message: String, val primaryAction: BqIssueAction, val refCode: String? = null)

private fun newBqRefCode(): String = UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.ROOT)
private fun Throwable.safeCauseText(): String = generateSequence(this) { it.cause }.take(8).joinToString(" ") { it.message.orEmpty() }.lowercase(Locale.ROOT)

internal fun issueFrom(throwable: Throwable? = null, httpStatus: Int? = null, errorCode: String? = null, context: String = "request", signedIn: Boolean = true): BqIssue {
    val causeText = throwable?.safeCauseText().orEmpty()
    val code = errorCode.orEmpty().lowercase(Locale.ROOT)
    val text = "$causeText $code ${throwable?.javaClass?.simpleName.orEmpty()} $context".lowercase(Locale.ROOT)
    fun issue(kind: BqIssueKind, title: String, message: String, action: BqIssueAction, ref: Boolean = false) =
        BqIssue(kind, title, message, action, if (ref) newBqRefCode() else null)
    if (throwable is CancellationException || text.contains("cancellationexception")) return issue(BqIssueKind.CANCELLED, "", "", BqIssueAction.NONE)
    val causes = throwable?.let { generateSequence(it) { current -> current.cause }.take(8).toList() }.orEmpty()
    if (causes.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is NoRouteToHostException || it is IOException } ||
        text.contains("no internet") || text.contains("network is unreachable") || text.contains("unable to resolve host") || text.contains("dns")) {
        return issue(BqIssueKind.OFFLINE, "You’re offline", "You are offline. Check your Wi-Fi or mobile data and try again.", BqIssueAction.RETRY)
    }
    if (!signedIn || text.contains("sign in is required") || text.contains("sign_in_required") || text.contains("auth_required") || httpStatus == 401) {
        return issue(BqIssueKind.NOT_SIGNED_IN, "Sign in required", "Please sign in to use this.", BqIssueAction.SIGN_IN)
    }
    if (text.contains("session_expired") || text.contains("token refresh failed") || text.contains("authentication token unavailable") || text.contains("invalid_id_token") || text.contains("user token expired")) {
        return issue(BqIssueKind.SESSION_EXPIRED, "Session expired", "Your session expired. Sign in again to continue.", BqIssueAction.SIGN_IN)
    }
    if (text.contains("email_not_verified") || text.contains("email is not verified")) return issue(BqIssueKind.EMAIL_NOT_VERIFIED, "Verify your email", "Verify your email to continue.", BqIssueAction.VERIFY_EMAIL)
    if (text.contains("consent_required") || text.contains("consent required")) return issue(BqIssueKind.CONSENT_REQUIRED, "Consent required", "Accept the Terms and Privacy to continue.", BqIssueAction.REVIEW)
    if (httpStatus == 402 || text.contains("insufficient_credits") || text.contains("insufficient credits") || text.contains("credit_balance") || text.contains("out of credits")) {
        return issue(BqIssueKind.OUT_OF_CREDITS, "Out of credits", "You are out of credits.", BqIssueAction.BUY_CREDITS)
    }
    if (text.contains("plan_required") || text.contains("entitlement_required")) return issue(BqIssueKind.PLAN_REQUIRED, "Plan required", "This feature isn’t included in your current plan. Review plans to continue.", BqIssueAction.VIEW_PLANS)
    if (text.contains("capability_disabled") || text.contains("capability_killed") || text.contains("image_generation_disabled") || text.contains("feature_disabled")) {
        return issue(BqIssueKind.FEATURE_OFF, "Feature unavailable", "This feature is currently turned off. Please try another feature.", BqIssueAction.NONE)
    }
    if (httpStatus == 429 || text.contains("rate_limited") || text.contains("rate limit")) return issue(BqIssueKind.RATE_LIMITED, "Please wait", "You are sending requests too quickly. Wait a moment and try again.", BqIssueAction.RETRY)
    if (text.contains("file_too_large") || text.contains("payload_too_large") || text.contains("attachment_payload_too_large")) return issue(BqIssueKind.FILE_TOO_LARGE, "File too large", "This file or message is too large. Try a smaller file.", BqIssueAction.NONE)
    if (text.contains("permission_denied") || text.contains("camera_permission_missing") || text.contains("microphone_permission_missing") || text.contains("permission denied")) {
        return issue(BqIssueKind.PERMISSION_DENIED, "Permission needed", "Allow the required permission in Android settings, then try again.", BqIssueAction.OPEN_SETTINGS)
    }
    if ((httpStatus != null && httpStatus >= 500) || text.contains("server_busy") || text.contains("provider outage") || text.contains("http 5") || text.contains("temporarily unavailable. the bq sqwad ai team is working")) {
        return issue(BqIssueKind.SERVER_BUSY, "Temporarily unavailable", "BQ Sqwad AI is busy right now. Please try again in a minute.", BqIssueAction.RETRY, true)
    }
    val ref = newBqRefCode()
    return BqIssue(BqIssueKind.UNKNOWN, "Something went wrong", "Something went wrong. Reference $ref.", BqIssueAction.RETRY, ref)
}
