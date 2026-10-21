package com.jarvis.assistant.automation.mission

import java.util.UUID

enum class MissionRisk {
    LOW,
    MEDIUM,
    HIGH_CONSEQUENTIAL // Deletion, external communications, financial, permissions
}

enum class MissionApprovalState {
    NOT_REQUIRED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}

enum class MissionStepStatus {
    PENDING,
    RUNNING,
    VERIFYING,
    COMPLETED,
    RECOVERING,
    BLOCKED,
    FAILED,
    CANCELLED,
    UNVERIFIED
}

enum class EvidenceType {
    UI_STATE_CHANGE,
    TEXT_MATCH,
    FILE_CREATED,
    DATABASE_RECORD,
    DEVICE_SETTING_VERIFIED,
    EXTERNAL_API_ACKNOWLEDGED,
    UNVERIFIED
}

data class MissionVerificationEvidence(
    val evidenceType: EvidenceType,
    val isVerified: Boolean,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ClarificationRequest(
    val question: String,
    val missingField: String,
    val suggestedOptions: List<String> = emptyList()
)

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val requiredPermissions: List<String> = emptyList(),
    val riskLevel: MissionRisk = MissionRisk.LOW,
    val requiresAccessibility: Boolean = false,
    val isIdempotent: Boolean = true
)

sealed class ToolExecutionResult {
    data class Success(val evidence: MissionVerificationEvidence, val outputData: Map<String, Any?> = emptyMap()) : ToolExecutionResult()
    data class Failure(val reason: String, val canRetry: Boolean = true) : ToolExecutionResult()
    data class PermissionDenied(val missingPermissions: List<String>) : ToolExecutionResult()
    data class RequiresApproval(val prompt: String) : ToolExecutionResult()
    data class ClarificationNeeded(val clarification: ClarificationRequest) : ToolExecutionResult()
    object Timeout : ToolExecutionResult()
}

data class MissionStep(
    val stepId: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val toolName: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val requiredPermissions: List<String> = emptyList(),
    val riskLevel: MissionRisk = MissionRisk.LOW,
    val preconditions: List<String> = emptyList(),
    val expectedEvidence: String = "",
    val isIdempotent: Boolean = true,
    val timeoutMs: Long = 15_000L,
    val status: MissionStepStatus = MissionStepStatus.PENDING,
    val verificationEvidence: MissionVerificationEvidence? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 2,
    val failureReason: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null
)

enum class MissionOverallStatus {
    IDLE,
    UNDERSTANDING,
    CLARIFICATION_REQUIRED,
    PLANNING,
    WAITING_FOR_APPROVAL,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    CANCELLED,
    UNVERIFIED
}

data class MissionDefinition(
    val missionId: String = UUID.randomUUID().toString(),
    val goal: String,
    val userConstraints: List<String> = emptyList(),
    val requiredInputs: List<String> = emptyList(),
    val steps: List<MissionStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val status: MissionOverallStatus = MissionOverallStatus.IDLE,
    val approvalState: MissionApprovalState = MissionApprovalState.NOT_REQUIRED,
    val approvalPrompt: String? = null,
    val clarificationRequest: ClarificationRequest? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val outcomeSummary: String? = null,
    val unverifiedDisclaimers: List<String> = emptyList()
) {
    val isFinished: Boolean
        get() = status in setOf(
            MissionOverallStatus.COMPLETED,
            MissionOverallStatus.PARTIALLY_COMPLETED,
            MissionOverallStatus.FAILED,
            MissionOverallStatus.CANCELLED,
            MissionOverallStatus.UNVERIFIED
        )
}
