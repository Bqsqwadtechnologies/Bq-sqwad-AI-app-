package com.bqsqwad.ai.core

object BrandCopy {
    fun checkout(raw: String?): String {
        val l = raw.orEmpty().lowercase()
        return when {
            l.isBlank() -> "Plans are unavailable right now. Please try again."
            "could not connect" in l || "disconnected" in l || "network" in l || "internet" in l ->
                "We couldn't reach the BQ Sqwad AI catalog. Check your internet connection and try again."
            "reconnecting" in l -> "Checkout is reconnecting. Please tap Buy again in a moment."
            "not available on this device" in l || "not supported" in l ->
                "Purchases aren't available on this device or account."
            "play testing" in l || "not available for this install" in l ->
                "These plans aren't available for this version of the app. Please update BQ Sqwad AI and try again."
            "validate" in l || "setup" in l || "console" in l ->
                "We couldn't confirm this purchase setup. Please contact support."
            "already owned" in l -> "You already have this. Refresh your account to restore access."
            "sign in" in l -> "Sign in to your BQ Sqwad AI account to see plans."
            else -> "Checkout couldn't be completed. Please try again."
        }
    }
    fun scrub(text: String): String = text
        .replace("Google Play Billing", "secure checkout", ignoreCase = true)
        .replace("Google Play", "BQ Sqwad AI", ignoreCase = true)
        .replace("Play Console", "plan setup", ignoreCase = true)
        .replace("Firebase", "BQ account", ignoreCase = true)
}
