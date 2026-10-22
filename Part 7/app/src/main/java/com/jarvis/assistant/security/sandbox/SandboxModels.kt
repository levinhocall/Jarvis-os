package com.jarvis.assistant.security.sandbox

import java.util.UUID

enum class ThreatType {
    PROMPT_INJECTION,
    PATH_TRAVERSAL,
    SSRF_ATTEMPT,
    DATA_EXFILTRATION,
    UNAUTHORIZED_ESCALATION,
    RATE_LIMIT_EXCEEDED
}

sealed class SandboxCheckResult {
    object Allowed : SandboxCheckResult()
    data class Blocked(
        val threatType: ThreatType,
        val reason: String,
        val sanitizedFallback: String? = null
    ) : SandboxCheckResult()
}

data class AuditLedgerEntry(
    val entryId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val actionName: String,
    val invoker: String,
    val threatDetected: ThreatType?,
    val isAllowed: Boolean,
    val details: String,
    val prevSignatureHash: String,
    val currentSignatureHash: String
)
