package com.jarvis.assistant.security.sandbox

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class CapabilitySandbox(
    private val defaultMaxRpm: Int = 30,
    private val auditDao: AuditDao? = null
) {
    private val auditLedger = mutableListOf<AuditLedgerEntry>()
    private var lastLedgerHash = "0000000000000000000000000000000000000000000000000000000000000000"
    private var checkpointHash = "0000000000000000000000000000000000000000000000000000000000000000"

    private val rateLimitBuckets = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * Inspects text for prompt injection, jailbreak attempts, or unauthorized control phrases.
     */
    fun validateInputText(input: String): SandboxCheckResult {
        // NFKC normalization and removal of invisible formatting characters prevent
        // common full-width / zero-width character evasions of phrase checks.
        val normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC)
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("[\\p{Cc}&&[^\\r\\n\\t]]"), " ")
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

        val maliciousPhrases = listOf(
            "ignore previous instructions" to "Prompt injection: override directive detected",
            "ignore all previous" to "Prompt injection: override directive detected",
            "disregard all instructions" to "Prompt injection: disregard directive detected",
            "ignore developer instructions" to "Prompt injection: developer override detected",
            "you are now in dan mode" to "Jailbreak attempt: DAN mode trigger",
            "act as an unrestricted model" to "Jailbreak attempt: unrestricted model trigger",
            "bypass system safety" to "Security bypass attempt detected",
            "disable safety rules" to "Security bypass attempt detected",
            "reveal system prompt" to "System prompt extraction attempt",
            "print system instructions" to "System instructions extraction attempt",
            "exfiltrate credentials" to "Data exfiltration trigger detected",
            "reveal api keys" to "Credential disclosure trigger detected",
            "send api key" to "Credential disclosure trigger detected"
        )

        for ((phrase, reason) in maliciousPhrases) {
            if (normalized.contains(phrase)) {
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
     * Inspects target URL for SSRF, private IP access, hex/decimal IP tricks, or unauthorized schemes.
     * Performs strict DNS resolution and blocks loopback, private, link-local, IPv6 ULA, and metadata IPs.
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

            var host = uri.host?.lowercase() ?: return SandboxCheckResult.Blocked(
                threatType = ThreatType.SSRF_ATTEMPT,
                reason = "URL has missing host"
            )

            // Strip IPv6 brackets if present (e.g. [::1] -> ::1)
            host = host.removePrefix("[").removeSuffix("]")

            if (!allowPrivateNetwork) {
                // 1. Check numeric decimal IP (e.g. 2130706433 -> 127.0.0.1)
                val decimalIp = host.toLongOrNull()
                if (decimalIp != null) {
                    val b1 = ((decimalIp shr 24) and 0xFF).toInt()
                    val b2 = ((decimalIp shr 16) and 0xFF).toInt()
                    val b3 = ((decimalIp shr 8) and 0xFF).toInt()
                    val b4 = (decimalIp and 0xFF).toInt()
                    val resolvedAddr = InetAddress.getByAddress(byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte()))
                    if (isIpBlocked(resolvedAddr)) {
                        return SandboxCheckResult.Blocked(
                            threatType = ThreatType.SSRF_ATTEMPT,
                            reason = "Access to loopback, link-local, or private IP network address '$host' (resolved $resolvedAddr) is forbidden."
                        )
                    }
                }

                // 2. Check Hexadecimal IP (e.g. 0x7f000001)
                if (host.startsWith("0x", ignoreCase = true)) {
                    val hexStr = host.substring(2)
                    val hexNum = hexStr.toLongOrNull(16)
                    if (hexNum != null) {
                        val b1 = ((hexNum shr 24) and 0xFF).toInt()
                        val b2 = ((hexNum shr 16) and 0xFF).toInt()
                        val b3 = ((hexNum shr 8) and 0xFF).toInt()
                        val b4 = (hexNum and 0xFF).toInt()
                        val resolvedAddr = InetAddress.getByAddress(byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte()))
                        if (isIpBlocked(resolvedAddr)) {
                            return SandboxCheckResult.Blocked(
                                threatType = ThreatType.SSRF_ATTEMPT,
                                reason = "Access to hex-encoded loopback or private IP address '$host' is forbidden."
                            )
                        }
                    }
                }

                // 3. Check IPv4-mapped IPv6 (e.g. ::ffff:127.0.0.1)
                if (host.startsWith("::ffff:", ignoreCase = true)) {
                    val embeddedIpv4 = host.substringAfter("::ffff:")
                    val mappedAddr = InetAddress.getByName(embeddedIpv4)
                    if (isIpBlocked(mappedAddr)) {
                        return SandboxCheckResult.Blocked(
                            threatType = ThreatType.SSRF_ATTEMPT,
                            reason = "Access to IPv4-mapped loopback or private address '$host' is forbidden."
                        )
                    }
                }

                // 4. Resolve DNS and inspect all returned IP addresses
                val addresses = try {
                    InetAddress.getAllByName(host)
                } catch (e: Exception) {
                    arrayOf<InetAddress>()
                }

                for (addr in addresses) {
                    if (isIpBlocked(addr)) {
                        return SandboxCheckResult.Blocked(
                            threatType = ThreatType.SSRF_ATTEMPT,
                            reason = "Access to loopback, link-local, or private IP network address '$host' (${addr.hostAddress}) is forbidden."
                        )
                    }
                }

                // 5. Hostname string check
                if (isPrivateOrLoopbackHost(host)) {
                    return SandboxCheckResult.Blocked(
                        threatType = ThreatType.SSRF_ATTEMPT,
                        reason = "Access to loopback, link-local, or private IP network address '$host' is forbidden."
                    )
                }
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
     * Validates an entire redirect hop chain without automatically following redirects,
     * ensuring that no intermediate hop targets an internal/private address.
     */
    fun validateUrlWithRedirects(
        initialUrl: String,
        maxHops: Int = 5,
        client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build()
    ): SandboxCheckResult {
        var currentUrl = initialUrl
        for (hop in 0 until maxHops) {
            val check = validateUrl(currentUrl)
            if (check !is SandboxCheckResult.Allowed) return check

            try {
                val req = Request.Builder().url(currentUrl).head().build()
                val resp = client.newCall(req).execute()
                val code = resp.code
                if (code in 300..399) {
                    val loc = resp.header("Location") ?: break
                    currentUrl = if (loc.startsWith("http://") || loc.startsWith("https://")) {
                        loc
                    } else {
                        URI(currentUrl).resolve(loc).toString()
                    }
                } else {
                    break
                }
            } catch (_: Exception) {
                break
            }
        }
        return validateUrl(currentUrl)
    }

    private fun isIpBlocked(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
            return true
        }
        val hostAddress = addr.hostAddress ?: return true
        if (hostAddress == "169.254.169.254" || hostAddress == "0.0.0.0") return true

        val rawBytes = addr.address
        if (rawBytes.size == 16) {
            val firstByte = rawBytes[0].toInt() and 0xFF
            // IPv6 Unique Local Address: fc00::/7 (0xfc or 0xfd)
            if ((firstByte and 0xFE) == 0xFC) {
                return true
            }
            // IPv4-mapped IPv6 check
            if (rawBytes[0] == 0.toByte() && rawBytes[1] == 0.toByte() && rawBytes[2] == 0.toByte() &&
                rawBytes[3] == 0.toByte() && rawBytes[4] == 0.toByte() && rawBytes[5] == 0.toByte() &&
                rawBytes[6] == 0.toByte() && rawBytes[7] == 0.toByte() && rawBytes[8] == 0.toByte() &&
                rawBytes[9] == 0.toByte() && rawBytes[10] == 0xFF.toByte() && rawBytes[11] == 0xFF.toByte()) {
                val b12 = rawBytes[12].toInt() and 0xFF
                val b13 = rawBytes[13].toInt() and 0xFF
                // Check if mapped IPv4 is 127.x.x.x, 10.x.x.x, 192.168.x.x, 172.16-31.x.x
                if (b12 == 127 || b12 == 10 || (b12 == 192 && b13 == 168) || (b12 == 172 && b13 in 16..31)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isPrivateOrLoopbackHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "::1") return true
        if (host == "169.254.169.254") return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("192.168.")) return true
        if (host.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\..*"""))) return true
        return false
    }

    /**
     * Validates that target file path stays strictly inside base directory using canonical Path comparisons.
     * Prevents prefix spoofing vulnerabilities such as /data/app vs /data/app2.
     */
    fun validateFilePath(baseDir: String, requestedPath: String): SandboxCheckResult {
        return try {
            val basePath: Path = File(baseDir).canonicalFile.toPath()
            val targetPath: Path = File(basePath.toFile(), requestedPath).canonicalFile.toPath()

            // Strict Path.startsWith verifies path component boundaries
            if (!targetPath.startsWith(basePath)) {
                return SandboxCheckResult.Blocked(
                    threatType = ThreatType.PATH_TRAVERSAL,
                    reason = "Path '$targetPath' attempts directory traversal outside base directory '$basePath'"
                )
            }

            val forbiddenSystemPrefixes = listOf("/proc", "/sys", "/dev", "/system", "/etc")
            val targetStr = targetPath.toString()
            if (forbiddenSystemPrefixes.any { targetPath.startsWith(File(it).toPath()) || targetStr.startsWith(it) }) {
                return SandboxCheckResult.Blocked(
                    threatType = ThreatType.PATH_TRAVERSAL,
                    reason = "Access to protected system path '$targetPath' is prohibited"
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
     * Audits an action in a tamper-evident cryptographic hash ledger and persists to Room.
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

    suspend fun persistEntryToRoom(entry: AuditLedgerEntry) {
        auditDao?.insert(
            AuditEntity(
                timestamp = entry.timestamp,
                actionName = entry.actionName,
                invoker = entry.invoker,
                threatDetected = entry.threatDetected?.name,
                isAllowed = entry.isAllowed,
                details = entry.details,
                prevSignatureHash = entry.prevSignatureHash,
                currentSignatureHash = entry.currentSignatureHash
            )
        )
    }

    suspend fun loadPersistedLedger() {
        if (auditDao != null) {
            val entities = auditDao.getAllEntries()
            synchronized(this) {
                auditLedger.clear()
                for (e in entities) {
                    val threatType = e.threatDetected?.let {
                        try { ThreatType.valueOf(it) } catch (_: Exception) { null }
                    }
                    val item = AuditLedgerEntry(
                        timestamp = e.timestamp,
                        actionName = e.actionName,
                        invoker = e.invoker,
                        threatDetected = threatType,
                        isAllowed = e.isAllowed,
                        details = e.details,
                        prevSignatureHash = e.prevSignatureHash,
                        currentSignatureHash = e.currentSignatureHash
                    )
                    auditLedger.add(item)
                    lastLedgerHash = e.currentSignatureHash
                }
            }
        }
    }

    suspend fun pruneLedger(maxEntries: Int = 1000) {
        synchronized(this) {
            if (auditLedger.size > maxEntries) {
                val pruneCount = auditLedger.size - maxEntries
                checkpointHash = auditLedger[pruneCount - 1].currentSignatureHash
                for (i in 0 until pruneCount) {
                    auditLedger.removeAt(0)
                }
            }
        }
        if (auditDao != null) {
            val count = auditDao.getCount()
            if (count > maxEntries) {
                auditDao.pruneOldest(count - maxEntries)
            }
        }
    }

    @Synchronized
    fun getAuditLedger(): List<AuditLedgerEntry> = auditLedger.toList()

    /**
     * Verifies cryptographic integrity of the ledger.
     */
    @Synchronized
    fun verifyLedgerIntegrity(): Boolean {
        return verifyChainIntegrity(auditLedger, checkpointHash)
    }

    companion object {
        fun verifyChainIntegrity(entries: List<AuditLedgerEntry>, initialCheckpoint: String = "0000000000000000000000000000000000000000000000000000000000000000"): Boolean {
            var expectedPrevHash = initialCheckpoint
            for (entry in entries) {
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

        private fun sha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private fun sha256(input: String): String = Companion.sha256(input)
}
