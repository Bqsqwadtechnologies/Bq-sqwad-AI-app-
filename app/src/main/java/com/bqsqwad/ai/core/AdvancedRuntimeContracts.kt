package com.bqsqwad.ai.core

data class AiJob(
    val id: String,
    val capability: String,
    val status: String,
    val progress: Int = 0,
    val resultRef: String? = null,
    val error: String? = null
)

data class AiWorkflow(
    val id: String,
    val name: String,
    val description: String = "",
    val status: String = "draft",
    val version: Int = 1,
    val steps: List<WorkflowStep> = emptyList()
)

data class WorkflowStep(
    val id: String,
    val capability: String,
    val requiresApproval: Boolean = false,
    val config: Map<String, String> = emptyMap()
)

data class BusinessAiAgent(
    val id: String,
    val name: String,
    val purpose: String,
    val instructions: String,
    val status: String = "draft",
    val tools: Set<String> = emptySet(),
    val channels: Set<String> = emptySet(),
    val knowledgeRefs: List<String> = emptyList()
)

data class EvaluationRun(
    val id: String,
    val capability: String,
    val modelId: String,
    val datasetRef: String?,
    val status: String,
    val score: Double? = null
)

data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val requestedPermissions: Set<String> = emptySet(),
    val enabled: Boolean = false,
    val approvedByAdmin: Boolean = false
)

data class SecurityEvent(
    val id: String,
    val category: String,
    val severity: String,
    val action: String,
    val createdAt: String
)

data class DataRetentionPolicy(
    val conversationDays: Int?,
    val fileHours: Int?,
    val auditDays: Int?,
    val allowExport: Boolean = true,
    val allowDeletion: Boolean = true
)
