package com.jarvis.assistant.security.sandbox

import java.util.Locale

/**
 * Delimits and monitors untrusted text originating from external sensors
 * (screen nodes, notifications, incoming messages, and web content).
 */
object UntrustedContentSanitizer {

    private val INSTRUCTION_TRIGGERS = listOf(
        "ignore previous", "disregard", "transfer money", "send money",
        "pay ", "delete all", "format phone", "factory reset", "call ",
        "open app", "system prompt", "dan mode", "exfiltrate", "run command"
    )

    /**
     * Encloses external text inside explicit delimiters so LLMs treat it strictly as passive data.
     */
    fun wrap(content: String, source: String = "screen"): String {
        return """<untrusted_content source="$source">$content</untrusted_content>"""
    }

    /**
     * Inspects whether external untrusted content contains active instruction-like patterns
     * that might attempt prompt injection against the agent.
     */
    fun containsInstructionLikePatterns(content: String): Boolean {
        val lower = content.lowercase(Locale.ROOT)
        return INSTRUCTION_TRIGGERS.any { lower.contains(it) }
    }
}
