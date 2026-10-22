package com.jarvis.assistant.security.sandbox

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class CapabilitySandbox(
    private val defaultMaxRpm: Int = 30
) {
    private val auditLedger = mutableListOf<AuditLedgerEntry>()
    private var lastLedgerHash = "0000000000000000000000000000000000000000000000000000000000000000"

    private val rateLimitBuckets = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * Inspects text for prompt injection, jailbreak attempts, or unauthorized control phrases.
     */
    fun validateInputText(input: String): SandboxCheckResult {
        val lower = input.lowercase()
        val maliciousPhrases = listOf(
            "ignore previous instructions" to "Prompt injection: override directive detected",
            "ignore all previous" to "Prompt injection: override directive detected",
            "disregard all instructions" to "Prompt injection: disregard directive detected",
            "you are now in dan mode" to "Jailbreak attempt: DAN mode trigger",
            "bypass system safety" to "Security bypass attempt detected",
            "reveal system prompt" to "System prompt extraction attempt",
            "print system instructions" to "System instructions extraction attempt",
            "exfiltrate credentials" to "Data exfiltration trigger detected"
        )

        for ((phrase, reason) in maliciousPhrases) {
            if (lower.contains(phrase)) {
                return SandboxCheckResult.Blocked(
                    threatType = ThreatType.PROMPT_INJECTION,
                    reason = reason,
                    sanitizedFallback = "[REDACTED UNTRUSTED DIRECTIVE]"
                )
            }
        }

        return SandboxCheckResult.Allowed
    }

    /**
     * Inspects target URL for SSRF, private IP access, or unauthorized schemes.
     */
    fun validateUrl(urlStr: String, allowPrivateNetwork: Boolean = false): SandboxCheckResult {
        return try {
            val uri = URI(urlStr)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return SandboxCheckResult.Blocked(
                    threatType = ThreatType.SSRF_ATTEMPT,
                    reason = "Unsupported or dangerous URI scheme '$scheme'. Only http and https are allowed."
                )
            }

            val host = uri.host?.lowercase() ?: return SandboxCheckResult.Blocked(
                threatType = ThreatType.SSRF_ATTEMPT,
                reason = "URL has missing host"
            )

            if (!allowPrivateNetwork && isPrivateOrLoopbackHost(host)) {
                return SandboxCheckResult.Blocked(
                    threatType = ThreatType.SSRF_ATTEMPT,
                    reason = "Access to loopback, link-local, or private IP network address '$host' is forbidden."
                )
            }

            SandboxCheckResult.Allowed
        } catch (e: Exception) {
            SandboxCheckResult.Blocked(
                threatType = ThreatType.SSRF_ATTEMPT,
                reason = "Malformed URL: ${e.message}"
            )
        }
    }

    /**
     * Validates that target file path stays strictly inside base directory and outside system areas.
     */
    fun validateFilePath(baseDir: String, requestedPath: String): SandboxCheckResult {
        return try {
            val baseFile = File(baseDir).canonicalFile
            val targetFile = File(baseFile, requestedPath).canonicalFile

            if (!targetFile.path.startsWith(baseFile.path)) {
                return SandboxCheckResult.Blocked(
                    threatType = ThreatType.PATH_TRAVERSAL,
                    reason = "Path '${targetFile.path}' attempts directory traversal outside base directory '${baseFile.path}'"
                )
            }

            val forbiddenSystemPrefixes = listOf("/proc", "/sys", "/dev", "/system", "/etc")
            if (forbiddenSystemPrefixes.any { targetFile.path.startsWith(it) }) {
                return SandboxCheckResult.Blocked(
                    threatType = ThreatType.PATH_TRAVERSAL,
                    reason = "Access to protected system path '${targetFile.path}' is prohibited"
                )
            }

            SandboxCheckResult.Allowed
        } catch (e: Exception) {
            SandboxCheckResult.Blocked(
                threatType = ThreatType.PATH_TRAVERSAL,
                reason = "Invalid file path: ${e.message}"
            )
        }
    }

    /**
     * Checks if tool requires elevated authorization.
     */
    fun validateToolInvocation(
        toolName: String,
        isElevatedTool: Boolean,
        isSessionElevated: Boolean
    ): SandboxCheckResult {
        if (isElevatedTool && !isSessionElevated) {
            return SandboxCheckResult.Blocked(
                threatType = ThreatType.UNAUTHORIZED_ESCALATION,
                reason = "Tool '$toolName' requires an elevated security session (PIN or authorized passphrase)."
            )
        }
        return SandboxCheckResult.Allowed
    }

    /**
     * Sliding window rate limiter.
     */
    @Synchronized
    fun checkRateLimit(identifier: String, maxRequestsPerMinute: Int = defaultMaxRpm): SandboxCheckResult {
        val now = System.currentTimeMillis()
        val windowStart = now - 60_000L
        val timestamps = rateLimitBuckets.getOrPut(identifier) { mutableListOf() }

        // Remove timestamps older than 60s
        timestamps.removeAll { it < windowStart }

        if (timestamps.size >= maxRequestsPerMinute) {
            return SandboxCheckResult.Blocked(
                threatType = ThreatType.RATE_LIMIT_EXCEEDED,
                reason = "Rate limit exceeded for '$identifier': ${timestamps.size}/$maxRequestsPerMinute RPM."
            )
        }

        timestamps.add(now)
        return SandboxCheckResult.Allowed
    }

    /**
     * Audits an action in a tamper-evident cryptographic hash ledger.
     */
    @Synchronized
    fun audit(actionName: String, invoker: String, result: SandboxCheckResult, details: String = ""): AuditLedgerEntry {
        val threat = (result as? SandboxCheckResult.Blocked)?.threatType
        val isAllowed = result is SandboxCheckResult.Allowed
        val now = System.currentTimeMillis()

        val rawData = "$lastLedgerHash|$now|$actionName|$invoker|$threat|$isAllowed|$details"
        val newHash = sha256(rawData)

        val entry = AuditLedgerEntry(
            timestamp = now,
            actionName = actionName,
            invoker = invoker,
            threatDetected = threat,
            isAllowed = isAllowed,
            details = details,
            prevSignatureHash = lastLedgerHash,
            currentSignatureHash = newHash
        )

        auditLedger.add(entry)
        lastLedgerHash = newHash
        return entry
    }

    @Synchronized
    fun getAuditLedger(): List<AuditLedgerEntry> = auditLedger.toList()

    /**
     * Verifies cryptographic integrity of the ledger.
     */
    @Synchronized
    fun verifyLedgerIntegrity(): Boolean {
        var expectedPrevHash = "0000000000000000000000000000000000000000000000000000000000000000"
        for (entry in auditLedger) {
            if (entry.prevSignatureHash != expectedPrevHash) {
                return false
            }
            val rawData = "${entry.prevSignatureHash}|${entry.timestamp}|${entry.actionName}|${entry.invoker}|${entry.threatDetected}|${entry.isAllowed}|${entry.details}"
            val computedHash = sha256(rawData)
            if (computedHash != entry.currentSignatureHash) {
                return false
            }
            expectedPrevHash = entry.currentSignatureHash
        }
        return true
    }

    private fun isPrivateOrLoopbackHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "::1") return true
        if (host == "169.254.169.254") return true // Cloud metadata instance IP
        if (host.startsWith("10.")) return true
        if (host.startsWith("192.168.")) return true
        if (host.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\..*"""))) return true
        return false
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
