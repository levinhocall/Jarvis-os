package com.jarvis.assistant.automation.integration

enum class VerificationStrategy {
    NONE,
    CLOSED_LOOP_ACCESSIBILITY,
    RETURN_VALUE,
    SYSTEM_STATE
}

enum class ActionRiskLevel {
    LOW,
    MEDIUM,
    HIGH_CONSEQUENTIAL
}

data class ActionParameterSchema(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val defaultValue: Any? = null
)

data class UniversalToolContract(
    val identifier: String,
    val description: String,
    val parameterSchemas: List<ActionParameterSchema>,
    val requiredPermissions: List<String> = emptyList(),
    val requiresAccessibility: Boolean = false,
    val riskLevel: ActionRiskLevel = ActionRiskLevel.LOW,
    val isIdempotent: Boolean = true,
    val verificationStrategy: VerificationStrategy = VerificationStrategy.RETURN_VALUE,
    val defaultTimeoutMs: Long = 10000L,
    val sideEffectsDescription: String = "No destructive side effects."
)

sealed class UniversalExecutionResult {
    data class Success(
        val toolIdentifier: String,
        val output: String,
        val evidence: String? = null,
        val durationMs: Long = 0L
    ) : UniversalExecutionResult()

    data class Failure(
        val toolIdentifier: String,
        val error: String,
        val isRecoverable: Boolean = false
    ) : UniversalExecutionResult()

    data class PermissionDenied(
        val toolIdentifier: String,
        val missingPermissions: List<String>,
        val settingsAction: String,
        val guidance: String
    ) : UniversalExecutionResult()

    data class ServiceUnavailable(
        val toolIdentifier: String,
        val reason: String,
        val guidance: String? = null
    ) : UniversalExecutionResult()

    data class TimedOut(
        val toolIdentifier: String,
        val timeoutMs: Long
    ) : UniversalExecutionResult()

    data class ConfirmationRequired(
        val toolIdentifier: String,
        val prompt: String,
        val actionSummary: String
    ) : UniversalExecutionResult()
}
