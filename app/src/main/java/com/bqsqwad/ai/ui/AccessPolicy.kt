package com.bqsqwad.ai.ui

/** Central guest-access policy for the native Android app. Server authorization remains mandatory. */
enum class AccessArea {
    SIGN_IN, SIGN_UP, LEGAL_CENTER, PUBLIC_PLAN_CATALOG, DEVICE_APPEARANCE_SETTINGS,
    ANDROID_PERMISSIONS, MAIN_CHAT_PREVIEW, CHAT_HISTORY, FILE_BOX, ACCOUNT_PROTECTION,
    PLAN_PURCHASE, CREDITS_BALANCE, CONNECTORS, COMPANION, BUSINESS, ORGANIZATION,
    LEARNING, TRUTH_GUARD, LIFE_MATHIS, PERSONAL_INTELLIGENCE, ADMIN, LIVE_VOICE,
    LIVE_CAMERA, SCREEN_SHARE, CREDIT_SPENDING
}

object AccessPolicy {
    private val guestAllowed = setOf(
        AccessArea.SIGN_IN,
        AccessArea.SIGN_UP,
        AccessArea.LEGAL_CENTER,
        AccessArea.PUBLIC_PLAN_CATALOG,
        AccessArea.DEVICE_APPEARANCE_SETTINGS,
        AccessArea.ANDROID_PERMISSIONS,
        AccessArea.MAIN_CHAT_PREVIEW
    )

    fun guestMayAccess(area: AccessArea): Boolean = area in guestAllowed
    fun requiresAccount(area: AccessArea): Boolean = !guestMayAccess(area)
}
