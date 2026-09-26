package com.bqsqwad.ai.domain

enum class Workspace(val title: String, val description: String) {
    CHAT("Main Chat", "Fast AI conversation, voice input and image generation"),
    VIDEO("Video Generation", "Dedicated AI video generation workspace with credit-controlled render jobs"),
    SEARCH("Web Search", "Current information with source-aware answers"),
    TRUTH_GUARD("TruthGuard AI", "Evidence-aware fact checking, source analysis and claim review"),
    FRONTIER_LAB("Frontier Lab", "Advanced AI experimentation, research and emerging capability workflows"),
    CAMERA("AI Live Camera", "Live vision, device actions and location assistance"),
    VOICE("Live Voice AI", "Natural voice conversation with interruption support"),
    PERSONAL("Personal Intelligence", "Planning, organization and personal assistance"),
    LIFE("Life Matters", "Dedicated AI guidance workspace"),
    COMPANION("AI Companion", "Caring conversation, personality, memory and check-ins"),
    LEARNING("Learning Studio", "Subjects, lessons and custom learning"),
    BUSINESS("Business AI", "Agents, knowledge, connections and automation"),
    ORGANIZATION_STUDIO("Organization AI / Studio", "Organization AI, shared knowledge, team controls, live production, multi-camera coordination and streaming"),
    DATA_ANALYSIS("Data Analyst", "Structured data analysis, calculations, charts and AI-generated reports"),
    ADMIN("Admin Control Center", "Remote configuration, providers, billing and testing")
}
