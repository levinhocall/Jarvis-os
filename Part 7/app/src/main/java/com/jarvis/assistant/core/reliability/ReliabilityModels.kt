package com.jarvis.assistant.core.reliability

enum class SystemHealthState {
    HEALTHY,
    DEGRADED,
    RECOVERING,
    UNAVAILABLE
}

enum class ErrorCategory {
    NETWORK_TIMEOUT,
    PROVIDER_FAILURE,
    SERVICE_DISCONNECTED,
    PERMISSION_DENIED,
    HARDWARE_UNAVAILABLE,
    STALE_SCREEN,
    RECOVERY_LOOP_DETECTED,
    CONCURRENCY_CONFLICT
}

data class DiagnosticEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val category: ErrorCategory,
    val component: String,
    val rawMessage: String,
    val isRecoverable: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Sanitizes diagnostic message to ensure no API keys, tokens, or private secrets leak into logs.
     */
    val sanitizedMessage: String
        get() {
            var clean = rawMessage
            // Run specific provider key match first (Google/Gemini keys start with AIza and are ~39 chars)
            clean = clean.replace(Regex("""AIza[0-9A-Za-z-_]{30,40}"""), "[REDACTED_GEMINI_KEY]")
            // Then generic token/password/key match
            clean = clean.replace(Regex("""(?i)(?:bearer|key|token|password|secret)[=:\s]+[A-Za-z0-9_\-\.]{8,}"""), "[REDACTED_SECRET]")
            return clean
        }
}

sealed class RecoveryResult {
    data class Recovered(val component: String, val attemptsMade: Int, val message: String) : RecoveryResult()
    data class Degraded(val component: String, val reason: String) : RecoveryResult()
    data class Failed(val component: String, val reason: String, val loopBreakerTriggered: Boolean = false) : RecoveryResult()
    data class CannotReplayNonIdempotentAction(val actionName: String, val reason: String) : RecoveryResult()
}
