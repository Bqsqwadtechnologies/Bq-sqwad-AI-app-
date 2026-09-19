package com.bqsqwad.ai.core

object WorkspaceCapabilities {
    val all = setOf(
        "chat","web_search","live_camera","live_voice","personal_intelligence","life_matters",
        "learning","business","admin","notifications","referrals","payments","credits",
        AdvancedCapabilities.CODRON,AdvancedCapabilities.IMAGE_GENERATION,
        AdvancedCapabilities.VIDEO_GENERATION,AdvancedCapabilities.FILE_ANALYSIS,
        "video_analysis","ai_orchestration","memory","device_intelligence","research",
        "agent_training","agent_evaluation","incident_system","security_controls",
        "offline_retry_queue","admin_staff_management","admin_reports","subscription_entitlements"
    )
}