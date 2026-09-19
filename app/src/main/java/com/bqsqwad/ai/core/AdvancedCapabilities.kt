package com.bqsqwad.ai.core

/**
 * Canonical capability catalogue for the BQ Sqwad AI product.
 *
 * The native client only describes capabilities and requests authorization.
 * Entitlements, subscriptions, pricing, credit consumption and provider secrets
 * remain server-controlled.
 */
object AdvancedCapabilities {
    const val IMAGE_GENERATION = "image_generation"
    const val VIDEO_GENERATION = "video_generation"
    const val FILE_ANALYSIS = "file_analysis"

    val subscriptionOnly = setOf(
        IMAGE_GENERATION,
        VIDEO_GENERATION,
        FILE_ANALYSIS
    )

    val advanced = setOf(
        "ai_orchestration",
        "model_routing",
        "provider_failover",
        "multimodal_images",
        "multifile_reasoning",
        "document_analysis",
        "audio_analysis",
        "video_analysis",
        "conversation_search",
        "conversation_export",
        "memory",
        "personal_memory",
        "project_memory",
        "business_memory",
        "device_intelligence",
        "calendar_actions",
        "reminder_actions",
        "location_assistance",
        "screen_awareness",
        "media_controls",
        "web_research",
        "research_export",
        "live_camera_ocr",
        "live_camera_scene_understanding",
        "live_voice_barge_in",
        "voice_activity_detection",
        "learning_quizzes",
        "document_to_course",
        "business_crm",
        "business_workflows",
        "business_analytics",
        "business_knowledge",
        "business_agents",
        "business_channels",
        "business_automation",
        "agent_training",
        "agent_evaluation",
        "agent_versioning",
        "agent_rollback",
        "notifications",
        "referrals",
        "credit_reservation",
        "credit_reversal",
        "rate_limiting",
        "abuse_protection",
        "incident_system",
        "ai_evaluation_center",
        "secure_file_pipeline",
        "offline_retry_queue"
    )

    fun requiresSubscription(capability: String): Boolean =
        capability in subscriptionOnly
}
