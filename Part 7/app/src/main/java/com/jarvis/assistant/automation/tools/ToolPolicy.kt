package com.jarvis.assistant.automation.tools

/**
 * Central, typed allow-list for every tool the live model may call.
 *
 * - Unknown tool names are rejected as UNSUPPORTED (the model cannot invent tools; there is no shell tool).
 * - Required arguments and basic types are validated before any handler runs.
 * - Tools that message or call people are flagged [Sensitivity.SENSITIVE]; their approval is handled by
 *   [ApprovalLedger], never by a model-supplied argument.
 *
 * Validation is deliberately lenient where the existing handlers already coerce (numeric strings for
 * integers, "true"/"false" for booleans) and ignores unknown extra arguments, so working behaviour is
 * preserved while missing or malformed required arguments now fail loudly instead of acting on blanks.
 */
object ToolPolicy {
    enum class ArgType { STRING, INT, BOOLEAN, STRING_LIST }
    enum class Sensitivity { NORMAL, SENSITIVE }
    enum class Outcome { PASS, PARTIAL, BLOCKED, UNSUPPORTED }

    data class ArgSpec(val name: String, val type: ArgType, val required: Boolean = false)

    data class ToolSpec(
        val name: String,
        val args: List<ArgSpec> = emptyList(),
        val sensitivity: Sensitivity = Sensitivity.NORMAL
    )

    sealed class Validation {
        object Valid : Validation()
        data class Invalid(val outcome: Outcome, val code: String, val message: String) : Validation()
    }

    const val MAX_STRING_LENGTH = 4000
    private const val MAX_LIST_SIZE = 10

    private fun s(name: String, required: Boolean = false) = ArgSpec(name, ArgType.STRING, required)
    private fun i(name: String, required: Boolean = false) = ArgSpec(name, ArgType.INT, required)
    private fun b(name: String, required: Boolean = false) = ArgSpec(name, ArgType.BOOLEAN, required)

    private val specs: Map<String, ToolSpec> = listOf(
        ToolSpec("open_app", listOf(s("app_name", true), i("app_number"))),
        ToolSpec("open_website", listOf(ArgSpec("urls", ArgType.STRING_LIST), s("query"))),
        ToolSpec("search_in_chrome", listOf(s("query", true))),
        ToolSpec("search_and_play_youtube", listOf(s("query", true))),
        ToolSpec("search_youtube", listOf(s("query", true))),
        ToolSpec("youtube_accessibility_action", listOf(s("action", true))),
        ToolSpec("media_playback_control", listOf(s("action", true))),
        ToolSpec(
            "send_whatsapp_message",
            listOf(s("recipient_name", true), s("message", true), i("app_number"), b("confirmed")),
            Sensitivity.SENSITIVE
        ),
        ToolSpec(
            "whatsapp_call",
            listOf(s("recipient_name", true), s("call_type", true), i("app_number"), b("confirmed")),
            Sensitivity.SENSITIVE
        ),
        ToolSpec("call_contact", listOf(s("contact_name", true)), Sensitivity.SENSITIVE),
        ToolSpec("set_volume", listOf(s("action", true), i("percentage"))),
        ToolSpec("set_brightness", listOf(s("action", true), i("percentage"))),
        ToolSpec("builtin_chrome_search", listOf(s("query", true))),
        ToolSpec("go_to_background"),
        ToolSpec("set_flashlight", listOf(b("enabled", true))),
        ToolSpec(
            "click_ui_element",
            listOf(s("target_name", true), i("x"), i("y"), i("element_id"), b("is_toggle"), s("description"))
        ),
        ToolSpec("type_text", listOf(s("text", true), i("x"), i("y"), s("target_input_name"), b("submit"))),
        ToolSpec("scroll_screen", listOf(s("direction", true), s("distance"))),
        ToolSpec("press_key", listOf(s("key", true))),
        ToolSpec("task_completed", listOf(s("summary", true))),
        ToolSpec("inspect_screen")
    ).associateBy { it.name }

    val knownTools: Set<String> get() = specs.keys

    fun isSensitive(toolName: String): Boolean = specs[toolName]?.sensitivity == Sensitivity.SENSITIVE

    fun validate(toolName: String, args: Map<String, Any?>): Validation {
        val spec = specs[toolName] ?: return Validation.Invalid(
            Outcome.UNSUPPORTED, "UNKNOWN_TOOL", "Unknown tool: $toolName"
        )
        for (arg in spec.args) {
            val value = args[arg.name]
            val missing = value == null || (value is String && value.isBlank())
            if (missing) {
                if (arg.required) return Validation.Invalid(
                    Outcome.BLOCKED, "MISSING_ARGUMENT", "Tool $toolName requires a non-empty '${arg.name}' argument."
                )
                continue
            }
            val problem = typeProblem(arg, value!!)
            if (problem != null) return Validation.Invalid(
                Outcome.BLOCKED, "INVALID_ARGUMENT", "Tool $toolName argument '${arg.name}' $problem."
            )
        }
        return Validation.Valid
    }

    private fun typeProblem(arg: ArgSpec, value: Any): String? = when (arg.type) {
        ArgType.STRING -> when {
            value !is String && value !is Number && value !is Boolean -> "must be text"
            value.toString().length > MAX_STRING_LENGTH -> "is longer than $MAX_STRING_LENGTH characters"
            else -> null
        }
        ArgType.INT -> when (value) {
            is Int, is Long -> null
            is Number -> if (value.toDouble() % 1.0 == 0.0) null else "must be a whole number"
            is String -> if (value.trim().toIntOrNull() != null) null else "must be a whole number"
            else -> "must be a whole number"
        }
        ArgType.BOOLEAN -> when (value) {
            is Boolean -> null
            is String -> if (value.trim().lowercase() in setOf("true", "false")) null else "must be true or false"
            else -> "must be true or false"
        }
        ArgType.STRING_LIST -> when {
            value !is Collection<*> -> "must be a list of text values"
            value.size > MAX_LIST_SIZE -> "has more than $MAX_LIST_SIZE entries"
            value.any { it !is String || it.length > MAX_STRING_LENGTH } -> "must contain only short text values"
            else -> null
        }
    }
}
