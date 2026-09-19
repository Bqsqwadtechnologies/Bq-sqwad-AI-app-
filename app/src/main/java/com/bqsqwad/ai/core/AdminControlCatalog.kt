package com.bqsqwad.ai.core

/**
 * Canonical admin-control IDs. These are configuration keys, not secrets.
 * The server remains the source of truth for authorization and values.
 */
object AdminControlCatalog {
    val sections = listOf(
        "overview",
        "providers",
        "provider_routing",
        "models",
        "web_search",
        "capabilities",
        "feature_flags",
        "rollouts",
        "plans",
        "pricing",
        "credits",
        "payments",
        "subscriptions",
        "testing",
        "codron",
        "agents",
        "business",
        "plugins",
        "notifications",
        "referrals",
        "security",
        "privacy",
        "audit",
        "incidents",
        "experiments",
        "analytics"
    )

    val providerSecretRefs = listOf(
        "GEMINI_API_KEY",
        "OPENAI_API_KEY",
        "TAVILY_API_KEY",
        "PAYSTACK_SECRET_KEY"
    )

    val providerPublicRefs = listOf(
        "PAYSTACK_PUBLIC_KEY"
    )

    val providerIds = listOf(
        BuiltInProviderDefaults.GEMINI_ID,
        BuiltInProviderDefaults.OPENAI_ID,
        BuiltInProviderDefaults.BQ_AGENT_ID,
        BuiltInProviderDefaults.TAVILY_ID,
        BuiltInProviderDefaults.PAYSTACK_ID
    )

    val configurableCapabilities = setOf(
        "chat",
        "device_commands",
        "web_search",
        "deep_research",
        "live_camera",
        "live_voice",
        "personal_intelligence",
        "life_matters",
        "learning",
        "business",
        "image_generation",
        "video_generation",
        "file_analysis",
        "codron",
        *AdvancedCapabilities.advanced.toTypedArray()
    )
}
