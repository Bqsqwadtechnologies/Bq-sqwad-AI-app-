package com.bqsqwad.ai.core

import com.bqsqwad.ai.domain.Workspace

/**
 * Single native-app registry for user-facing AI capabilities.
 *
 * Every product capability that can be surfaced to a signed-in user is represented
 * here. Internal build files, secrets, provider credentials and test-only controls
 * intentionally stay outside this registry.
 */
data class NativeFeatureEntry(
    val id: String,
    val title: String,
    val description: String,
    val workspace: Workspace? = null,
    val webSearch: Boolean = false,
    val credits: Boolean = true
)

object NativeFeatureRegistry {
    val core: List<NativeFeatureEntry> = listOf(
        NativeFeatureEntry("chat", "Main Chat", "General BQ Sqwad AI conversation.", Workspace.CHAT, false, true),
        NativeFeatureEntry("image_generation", "Image Generation", "Generate images directly from Main Chat using the shared credit system.", Workspace.CHAT, false, true),
        NativeFeatureEntry("file_analysis", "File Analysis", "Analyze supported documents and attachments from Main Chat using the shared AI runtime.", Workspace.CHAT, false, true),
        NativeFeatureEntry("video_generation", "Video Generation", "Dedicated AI video generation workspace.", Workspace.VIDEO, false, true),
        NativeFeatureEntry("web_search", "Web Search", "Current information with source-aware answers.", Workspace.SEARCH, true, true),
        NativeFeatureEntry("truth_guard", "TruthGuard AI", "Evidence-aware claim checking with web evidence.", Workspace.TRUTH_GUARD, true, true),
        NativeFeatureEntry("frontier_lab", "Frontier Lab", "Advanced AI experimentation and emerging capabilities.", Workspace.FRONTIER_LAB, true, true),
        NativeFeatureEntry("data_analysis_workspace", "Data Analyst", "Structured data analysis and AI-generated reports.", Workspace.DATA_ANALYSIS, false, true),
        NativeFeatureEntry("ai_live_camera", "AI Live Camera", "Live visual understanding and supported screen analysis.", Workspace.CAMERA, false, true),
        NativeFeatureEntry("live_voice_ai", "Live Voice AI", "Real-time voice conversation with interruption support.", Workspace.VOICE, false, true),
        NativeFeatureEntry("personal_intelligence", "Personal Intelligence", "Planning, organization and permission-based personal intelligence.", Workspace.PERSONAL, false, true),
        NativeFeatureEntry("life_matters", "Life Matters", "Practical guidance, routines and planning.", Workspace.LIFE, false, true),
        NativeFeatureEntry("ai_companion", "AI Companion", "Companion conversation, memory and check-ins.", Workspace.COMPANION, false, true),
        NativeFeatureEntry("learning_studio", "Learning Studio", "Subjects, tutoring, lessons and revision.", Workspace.LEARNING, false, true),
        NativeFeatureEntry("business_ai", "Business AI", "Business assistants, knowledge, CRM and automation.", Workspace.BUSINESS, false, true),
        NativeFeatureEntry("organization_studio_ai", "Organization Studio AI", "Organization intelligence, production and streaming controls.", Workspace.ORGANIZATION_STUDIO, false, true)
    )

    val advanced: List<NativeFeatureEntry> = AdvancedCapabilities.advanced
        // BQ Sqwad AI Agent is an internal/admin capability and must never appear in the end-user feature catalog.
        .filterNot { id -> id == "ai_agent" || core.any { it.id == id } }
        .sorted()
        .map { id ->
            NativeFeatureEntry(
                id = id,
                title = displayName(id),
                description = description(id),
                workspace = null,
                webSearch = id == "web_research" || id == "deep_research",
                credits = true
            )
        }

    val all: List<NativeFeatureEntry> get() = core + advanced

    fun find(id: String): NativeFeatureEntry? = all.firstOrNull { it.id == id }

    private fun displayName(id: String): String = id.split('_').joinToString(" ") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun description(id: String): String = when (id) {
        "deep_research", "web_research" -> "Research with web evidence and source-aware AI results."
        "knowledge_graphs" -> "Connect people, facts, documents and relationships into searchable knowledge."
        "semantic_file_search" -> "Find relevant information across supported files by meaning."
        "conversation_intelligence" -> "Extract topics, facts, actions and summaries from conversations."
        "project_workspaces" -> "Keep related conversations, files, instructions and memory together."
        "ai_generated_reports" -> "Generate structured reports from conversations and analyzed data."
        "ai_coding_workspace", "github_codron_integration", "codron" -> "AI-assisted coding, repository understanding and controlled development workflows."
        "background_generation_jobs", "generation_queue" -> "Track longer AI work in a server-backed generation queue."
        "cross_device_sync" -> "Synchronize supported AI data across signed-in devices."
        "business_crm", "business_workflows", "business_analytics" -> "Business intelligence, CRM and automation capability connected to the business backend."
        else -> "Native BQ Sqwad AI capability connected to the shared server runtime and admin controls."
    }
}
