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
    CANCELLED
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
    PLANNING,
    WAITING_FOR_APPROVAL,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val outcomeSummary: String? = null,
    val unverifiedDisclaimers: List<String> = emptyList()
) {
    val isFinished: Boolean
        get() = status in setOf(
            MissionOverallStatus.COMPLETED,
            MissionOverallStatus.FAILED,
            MissionOverallStatus.CANCELLED
        )
}
