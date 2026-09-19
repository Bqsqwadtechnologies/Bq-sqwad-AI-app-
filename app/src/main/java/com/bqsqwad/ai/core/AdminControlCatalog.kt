package com.bqsqwad.ai.core

/**
 * Canonical admin-control IDs. The server is the source of truth for values and authorization.
 * These controls are intentionally stable so the Admin Control Center can manage them remotely.
 */
object AdminControlCatalog {
    val sections = listOf(
        "ai_models","providers","routing","capabilities","credits","pricing","plans","payments",
        "subscriptions","business","agents","device","privacy","security","users","notifications",
        "referrals","codron","plugins","experiments","rollouts","monitoring","incidents","audit",
        "data","system","emergency"
    )

    /** The 40 advanced controls requested for the Admin Control Center. */
    val advancedControls = listOf(
        "provider_cost_optimizer",
        "provider_health_monitor",
        "model_version_management",
        "evaluation_lab",
        "prompt_config_versioning",
        "capability_credit_pricing",
        "search_domain_controls",
        "device_approval_policies",
        "ai_activity_timeline",
        "action_preview",
        "human_approval",
        "undoable_ai_actions",
        "agent_sandbox",
        "plugin_marketplace",
        "background_jobs",
        "scheduled_tasks",
        "monitoring_agents",
        "usage_forecasting",
        "automatic_quality_fallback",
        "emergency_kill_switches",
        "incident_replay",
        "security_risk_monitoring",
        "config_snapshots_rollback",
        "regional_pricing_promos",
        "subscription_lifecycle",
        "business_ai_employees",
        "agent_to_agent_workflows",
        "cross_device_sync",
        "secure_sensitive_vault",
        "resumable_uploads",
        "multilingual_ui_voice",
        "accessibility_controls",
        "passkeys_sessions_devices",
        "data_export_deletion",
        "provider_routing",
        "model_routing",
        "provider_secret_management",
        "web_search_pricing",
        "feature_rollouts",
        "admin_testing_mode"
    )

    val providerSecretRefs = listOf(
        "GEMINI_API_KEY","OPENAI_API_KEY","TAVILY_API_KEY","PAYSTACK_SECRET_KEY"
    )
    val providerPublicRefs = listOf("PAYSTACK_PUBLIC_KEY")

    val providerIds = listOf(
        BuiltInProviderDefaults.GEMINI_ID,
        BuiltInProviderDefaults.OPENAI_ID,
        BuiltInProviderDefaults.BQ_AGENT_ID,
        BuiltInProviderDefaults.TAVILY_ID,
        BuiltInProviderDefaults.PAYSTACK_ID
    )

    val configurableCapabilities = setOf(
        "chat","device_commands","web_search","deep_research","live_camera","live_voice",
        "personal_intelligence","life_matters","learning","business","image_generation",
        "video_generation","file_analysis","codron",
        *AdvancedCapabilities.advanced.toTypedArray()
    )
}
