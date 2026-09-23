package com.bqsqwad.ai.core

object AdvancedCapabilities {
    const val IMAGE_GENERATION = "image_generation"
    const val VIDEO_GENERATION = "video_generation"
    const val FILE_ANALYSIS = "file_analysis"
    const val TRUTH_GUARD = "truth_guard"
    const val FRONTIER_LAB = "frontier_lab"
    const val DATA_ANALYSIS = "data_analysis_workspace"
    const val CODRON = "codron"
    const val BUSINESS_TRAINING_TEXT = "business_training_text"
    const val BUSINESS_TRAINING_PHOTO = "business_training_photo"
    const val BUSINESS_TRAINING_VOICE = "business_training_voice"
    const val BUSINESS_TRAINING_VIDEO = "business_training_video"
    const val BUSINESS_TRAINING_STEP_BY_STEP = "business_training_step_by_step"
    const val BUSINESS_TRAINING_APPEARANCE = "business_training_appearance"

    val subscriptionOnly = setOf(IMAGE_GENERATION, VIDEO_GENERATION, FILE_ANALYSIS)
    const val CODRON_DEFAULT_ACTIVE = false

    val advanced = setOf(
        "ai_task_planner","agent_tool_use","agent_sandbox","ai_workflow_builder",
        "scheduled_ai_tasks","ai_monitoring_agents","deep_research","knowledge_graphs",
        "semantic_file_search","conversation_intelligence","project_workspaces",
        "ai_generated_reports","data_analysis_workspace","ai_coding_workspace",
        "github_codron_integration","ai_evaluation_laboratory","provider_cost_optimizer",
        "automatic_quality_fallback","response_confidence_context","source_verification",
        "custom_ai_assistants","business_ai_employee","agent_to_agent_workflows",
        "human_approval_checkpoints","action_preview","permission_center","privacy_dashboard",
        "ai_activity_timeline","undoable_ai_actions","secure_temporary_file_vault",
        "large_file_resumable_uploads","background_generation_jobs","generation_queue",
        "usage_forecasting","admin_experimentation","feature_rollout_percentages",
        "emergency_kill_switches","provider_health_checks","ai_incident_replay",
        "model_version_rollback","security_event_monitoring","ai_abuse_risk_detection",
        "encrypted_local_sensitive_storage","passkey_auth","account_session_device_management",
        "data_export_account_deletion","accessibility_ai_controls","multilingual_ui_voice",
        "cross_device_sync","admin_capability_marketplace","ai_orchestration","model_routing",
        "provider_failover","multimodal_images","multifile_reasoning","document_analysis",
        "audio_analysis","video_analysis","conversation_search","conversation_export","memory",
        "personal_memory","project_memory","business_memory","device_intelligence",
        "calendar_actions","reminder_actions","location_assistance","screen_awareness",
        "media_controls","web_research","research_export","truth_guard","frontier_lab","data_analysis_workspace","live_camera_ocr",
        "live_camera_scene_understanding","live_voice_barge_in","voice_activity_detection",
        "learning_quizzes","document_to_course","business_crm","business_workflows",
        "business_analytics","business_knowledge","business_agents","business_channels",
        "business_automation","business_training_text","business_training_photo","business_training_voice",
        "business_training_video","business_training_step_by_step","business_training_appearance","agent_training","agent_evaluation","agent_versioning",
        "agent_rollback","notifications","referrals","credit_reservation","credit_reversal",
        "rate_limiting","abuse_protection","incident_system","ai_evaluation_center",
        "secure_file_pipeline","offline_retry_queue"
    )

    fun requiresSubscription(capability: String): Boolean = capability in subscriptionOnly
}