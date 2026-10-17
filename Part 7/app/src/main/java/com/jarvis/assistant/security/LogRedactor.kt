package com.jarvis.assistant.security

/**
 * Pure-Kotlin helpers that keep credentials and message content out of logs and user-visible errors.
 *
 * Use [redact] on any free-form text (exception messages, HTTP error bodies, URLs) before it is logged
 * or shown. Use [describeArgs] instead of logging raw tool arguments, which can contain message bodies,
 * contact names and typed text.
 */
object LogRedactor {
    private const val MASK = "[REDACTED]"

    /** Google API keys have a fixed, recognisable shape. */
    private val googleApiKey = Regex("AIza[0-9A-Za-z_\\-]{20,}")

    /** key=..., api_key=..., access_token=... in URLs, JSON-ish text and form bodies. */
    private val secretParam = Regex(
        """(?i)(\b(?:api[_-]?key|key|access[_-]?token|token|secret|password|authorization)\b"?\s*[=:]\s*"?)([^&\s",;]+)"""
    )

    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-~+/]+=*")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out: String = text
        out = googleApiKey.replace(out, MASK)
        out = bearer.replace(out) { it.groupValues[1] + MASK }
        out = secretParam.replace(out) { it.groupValues[1] + MASK }
        return out
    }

    fun redact(error: Throwable?): String {
        if (error == null) return ""
        val name = error.javaClass.simpleName.ifBlank { "Error" }
        val message = redact(error.message)
        return if (message.isBlank()) name else "$name: $message"
    }

    /**
     * Describes tool arguments without leaking their values: names with value type/length only.
     * Example: `recipient_name=<string:4>, message=<string:12>, app_number=<int>`.
     */
    fun describeArgs(args: Map<String, Any?>): String =
        args.entries.sortedBy { it.key }.joinToString(", ") { (key, value) ->
            val shape = when (value) {
                null -> "null"
                is String -> "string:${value.length}"
                is Boolean -> "bool"
                is Number -> "int"
                is Collection<*> -> "list:${value.size}"
                else -> "value"
            }
            "$key=<$shape>"
        }
}
