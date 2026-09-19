package com.bqsqwad.ai.domain

enum class Workspace(val title: String, val description: String) {
    CHAT("Main Chat", "Fast AI conversation, voice input and image generation"),
    SEARCH("Web Search", "Current information with source-aware answers"),
    CAMERA("AI Live Camera", "Live vision, device actions and location assistance"),
    VOICE("Live Voice AI", "Natural voice conversation with interruption support"),
    PERSONAL("Personal Intelligence", "Planning, organization and personal assistance"),
    LIFE("Life Matters", "Dedicated AI guidance workspace"),
    LEARNING("Learning Studio", "Subjects, lessons and custom learning"),
    BUSINESS("Business Intelligence", "Agents, knowledge, connections and automation"),
    ADMIN("Admin Control Center", "Remote configuration, providers, billing and testing")
}
