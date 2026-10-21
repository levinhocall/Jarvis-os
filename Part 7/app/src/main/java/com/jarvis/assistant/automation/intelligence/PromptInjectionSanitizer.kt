package com.jarvis.assistant.automation.intelligence

import android.util.Log

data class SanitizationResult(
    val cleanText: String,
    val isInjectionDetected: Boolean,
    val flaggedPatterns: List<String>
)

object PromptInjectionSanitizer {
    private const val TAG = "PromptInjectionSanitizer"

    private val INJECTION_PATTERNS = listOf(
        Regex("(?i)\\b(ignore|disregard|forget|bypass)\\s+(all\\s+)?(previous|prior|above|existing)\\s+(instructions|prompts|rules|commands|constraints)\\b"),
        Regex("(?i)\\b(system\\s+prompt\\s+override|developer\\s+mode|dan\\s+mode|jailbreak\\s+mode)\\b"),
        Regex("(?i)\\b(you\\s+are\\s+now|act\\s+as|pretend\\s+to\\s+be)\\s+(an\\s+unrestricted|a\\s+rogue|an\\s+uncensored|god\\s+mode)\\b"),
        Regex("(?i)\\b(print|reveal|output|display|leak)\\s+(all\\s+)?(api[_-]?keys?|passwords?|credentials?|secret[_-]?keys?|system\\s+prompt)\\b"),
        Regex("(?i)\\b(send|upload|exfiltrate|post)\\s+(all\\s+)?(contacts|sms|messages|user\\s+data)\\s+to\\b")
    )

    /**
     * Inspects and sanitizes untrusted external text from files, web search, or third-party APIs.
     * Neutralizes detected prompt injections and wraps untrusted content in protective boundaries.
     */
    fun sanitize(untrustedInput: String): SanitizationResult {
        var clean = untrustedInput
        val flagged = mutableListOf<String>()

        for (pattern in INJECTION_PATTERNS) {
            val matches = pattern.findAll(clean).toList()
            if (matches.isNotEmpty()) {
                for (match in matches) {
                    flagged.add(match.value)
                }
                clean = pattern.replace(clean, "[NEUTRALIZED_UNTRUSTED_INSTRUCTION]")
            }
        }

        val hasInjection = flagged.isNotEmpty()
        if (hasInjection) {
            Log.w(TAG, "Prompt injection attempt neutralized! Flagged: ${flagged.joinToString()}")
        }

        return SanitizationResult(
            cleanText = clean,
            isInjectionDetected = hasInjection,
            flaggedPatterns = flagged
        )
    }

    /**
     * Encloses sanitized external content inside an explicit safety sandbox delimiter
     * to prevent LLM instruction drift.
     */
    fun wrapUntrustedContext(label: String, content: String): String {
        val sanitized = sanitize(content).cleanText
        return """
            --- BEGIN UNTRUSTED DATA [$label] ---
            $sanitized
            --- END UNTRUSTED DATA [$label] ---
        """.trimIndent()
    }
}
