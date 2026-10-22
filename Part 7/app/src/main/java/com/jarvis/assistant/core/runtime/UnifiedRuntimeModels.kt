package com.jarvis.assistant.core.runtime

enum class SubsystemStatus {
    HEALTHY,
    DEGRADED,
    OFFLINE,
    DISABLED,
    FAILED
}

data class SubsystemDiagnostic(
    val subsystemName: String,
    val status: SubsystemStatus,
    val latencyMs: Long,
    val diagnosticMessage: String,
    val requiredActionForUser: String? = null
)

data class SystemBootReport(
    val bootTimestamp: Long = System.currentTimeMillis(),
    val overallStatus: SubsystemStatus,
    val diagnostics: List<SubsystemDiagnostic>,
    val isAccessibilityReady: Boolean,
    val isVoiceReady: Boolean,
    val isNetworkReady: Boolean,
    val summaryMessage: String
)

sealed class UnifiedExecutionResult {
    data class Success(
        val outputMessage: String,
        val specialistHandled: String,
        val provenanceRecordId: String? = null
    ) : UnifiedExecutionResult()

    data class BlockedBySecurity(
        val threatReason: String
    ) : UnifiedExecutionResult()

    data class Degraded(
        val reason: String,
        val fallbackMessage: String
    ) : UnifiedExecutionResult()

    data class RecoveredCrash(
        val errorSummary: String
    ) : UnifiedExecutionResult()
}
