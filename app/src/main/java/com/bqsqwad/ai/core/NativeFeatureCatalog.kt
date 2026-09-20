package com.bqsqwad.ai.core

/**
 * Native parity catalog mirrored from the former BQ Sqwad AI web product.
 * This file is data-only: billing amounts and provider secrets remain server-side.
 */
data class NativeFeature(
    val id: String,
    val title: String,
    val description: String,
    val workspace: String
)

data class NativePlan(
    val id: String,
    val name: String,
    val credits: Int,
    val period: String,
    val displayUsd: String,
    val module: String = "credits"
)

object NativeFeatureCatalog {
    val workspaces = listOf(
        "Main Chat", "Web Search", "Deep Research", "Live Camera", "Live Voice",
        "Personal Intelligence", "Life Matters", "Learning Studio", "Business AI",
        "AI Creative Studio", "AI Companion", "Codron", "AI Agent", "Frontier Lab",
        "Documents & Files", "Image Generation", "Video Generation", "Music Generation",
        "Avatar Studio", "Device Intelligence", "Social & Automation", "Settings & Privacy",
        "Admin Control Center"
    )

    val features = listOf(
        NativeFeature("chat","AI Chat","General conversation, writing, coding, planning and assistance.","Main Chat"),
        NativeFeature("voice","Voice Conversations","Voice input and spoken AI responses using the saved voice preference.","Live Voice"),
        NativeFeature("birthday","AI Birthday","Account age calculated from the account creation date.","Personal Intelligence"),
        NativeFeature("memory","Permission-based Memory","Remember useful information only with user permission.","Personal Intelligence"),
        NativeFeature("camera","Live Camera Assistant","Permission-gated visual questions and scene understanding.","Live Camera"),
        NativeFeature("navigation","Smart Navigation","Location-aware navigation assistance when the user explicitly enables it.","Device Intelligence"),
        NativeFeature("translation","Translation","Translate text and conversation across supported languages.","Main Chat"),
        NativeFeature("homework","Homework Help","Explain and work through academic exercises.","Learning Studio"),
        NativeFeature("creative-writing","Creative Writing","Stories, scripts, ideas and other original writing.","AI Creative Studio"),
        NativeFeature("coding","Coding Assistant","Code explanation, generation, debugging and project planning.","Codron"),
        NativeFeature("reminders","Smart Reminders","Create reminders only after explicit confirmation.","Life Matters"),
        NativeFeature("cloud-sync","Cloud Sync","Synchronize permitted account data across signed-in devices.","Personal Intelligence"),
        NativeFeature("calendar","Calendar Assistance","Plan and organize calendar information when connected.","Life Matters"),
        NativeFeature("daily-planning","Daily Planning","Goals, tasks, routines and practical planning.","Life Matters"),
        NativeFeature("deep-research","Deep Research","Multi-source research with uncertainty and source tracking.","Deep Research"),
        NativeFeature("document-analysis","Document Analysis","Analyze supported files and extract useful information.","Documents & Files"),
        NativeFeature("image-generation","AI Image Generation","Generate original images from user prompts.","Image Generation"),
        NativeFeature("image-editing","AI Image Editing","Edit user-provided images with explicit instructions.","Image Generation"),
        NativeFeature("video-generation","AI Video Generation","Create video concepts and supported generated media.","Video Generation"),
        NativeFeature("music-generation","AI Music Generation","Create original music concepts and supported audio outputs.","Music Generation"),
        NativeFeature("avatar","AI Avatar Studio","Create supported avatar/presenter assets.","Avatar Studio"),
        NativeFeature("agent","AI Agent","Plan multi-step tasks with review and permission gates.","AI Agent"),
        NativeFeature("workflows","Agent Workflows","Reusable multi-step workflows with controlled execution.","AI Agent"),
        NativeFeature("business-agents","Business AI Employees","Business assistants with scoped roles and knowledge.","Business AI"),
        NativeFeature("business-knowledge","Business Knowledge","Controlled business files, documents and knowledge sources.","Business AI"),
        NativeFeature("business-connections","Business Connections","CRM, channels and approved external integrations.","Business AI"),
        NativeFeature("business-analytics","Business Analytics","Operational metrics and conversation analytics.","Business AI"),
        NativeFeature("frontier","Frontier Lab","Experimental research and unmet-problem exploration.","Frontier Lab"),
        NativeFeature("codron","Codron","AI coding/project workspace with inspectable tasks.","Codron"),
        NativeFeature("plugins","Plugins & Tools","Admin-controlled tool and plugin permissions.","Admin Control Center"),
        NativeFeature("device-actions","Device Intelligence","Explicitly approved device actions and permission status.","Device Intelligence"),
        NativeFeature("social-automation","Social & Automation","Approved social publishing and automation workflows.","Social & Automation")
    )

    val plans = listOf(
        NativePlan("monthly-100","Monthly 100",100,"monthly","$11.99"),
        NativePlan("monthly-250","Monthly 250",250,"monthly","$24.99"),
        NativePlan("monthly-500","Monthly 500",500,"monthly","$44.99"),
        NativePlan("monthly-1000","Monthly 1,000",1000,"monthly","$79.99"),
        NativePlan("day-100","Day 100",100,"daily","$7.99"),
        NativePlan("week-100","Week 100",100,"weekly","$9.99"),
        NativePlan("week-250","Week 250",250,"weekly","$19.99"),
        NativePlan("week-500","Week 500",500,"weekly","$34.99"),
        NativePlan("week-1000","Week 1,000",1000,"weekly","$59.99"),
        NativePlan("year-500","Yearly 500",500,"yearly","$59.99"),
        NativePlan("year-1000","Yearly 1,000",1000,"yearly","$119.99"),
        NativePlan("learning-explorer","Learning — Explorer","0","module","Server-configured","education"),
        NativePlan("learning-scholar","Learning — Scholar","0","module","Server-configured","education"),
        NativePlan("learning-family","Learning — Family Academy","0","module","Server-configured","education"),
        NativePlan("learning-school","Learning — School & Tutor","0","module","Server-configured","education"),
        NativePlan("companion-basic","Companion — Basic","0","module","Server-configured","companion"),
        NativePlan("companion-plus","Companion — Plus","0","module","Server-configured","companion"),
        NativePlan("companion-premium","Companion — Premium","0","module","Server-configured","companion"),
        NativePlan("companion-ultimate","Companion — Ultimate","0","module","Server-configured","companion")
    )
}
