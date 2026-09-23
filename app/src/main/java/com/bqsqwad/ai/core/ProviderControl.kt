package com.bqsqwad.ai.core

/**
 * Server-controlled provider configuration.
 *
 * Secret values are never stored in this model and must never be bundled in the APK.
 * The backend resolves secretRef against its server-side secret store.
 */
enum class ProviderKind {
    GEMINI,
    OPENAI,
    BQ_SQWAD_AGENT,
    TAVILY,
    PAYSTACK
}

data class ProviderDefinition(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val enabled: Boolean = false,
    val defaultModel: String? = null,
    val secretRef: String? = null,
    val publicKeyRef: String? = null,
    val capabilities: Set<String> = emptySet()
)

data class CapabilityRoute(
    val capabilityId: String,
    val primaryProviderId: String,
    val primaryModelId: String? = null,
    val fallbackProviderIds: List<String> = emptyList(),
    val enabled: Boolean = true,
    val creditCostId: String? = null
)

object BuiltInProviderDefaults {
    const val GEMINI_ID = "gemini"
    const val OPENAI_ID = "openai"
    const val BQ_AGENT_ID = "bq_sqwad_agent"
    const val TAVILY_ID = "tavily"
    const val PAYSTACK_ID = "paystack"

    /**
     * Main Chat and device-command routing are defaults, not hardcoded execution.
     * Admin remote configuration can replace these routes without an APK update.
     */
    const val MAIN_CHAT_DEFAULT_PROVIDER = OPENAI_ID
    const val DEVICE_COMMAND_DEFAULT_PROVIDER = GEMINI_ID
    const val WEB_SEARCH_DEFAULT_PROVIDER = TAVILY_ID
}
