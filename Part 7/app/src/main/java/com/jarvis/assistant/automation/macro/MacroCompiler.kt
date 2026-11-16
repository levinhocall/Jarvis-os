package com.jarvis.assistant.automation.macro

import com.google.gson.JsonParser
import com.jarvis.assistant.automation.mission.*
import com.jarvis.assistant.security.sandbox.RiskPolicy

data class CompiledMacro(val name: String, val steps: List<MissionStep>)

/** JSON is an untrusted plan, never authority to bypass the existing mission engine. */
object MacroCompiler {
    private val allowed = setOf("open_app", "inspect_screen", "click_ui_element", "type_text",
        "scroll_screen", "wait_for_screen_text")
    private val variable = Regex("\\$\\{([a-zA-Z][a-zA-Z0-9_]{0,39})}")
    fun compile(json: String): CompiledMacro {
        require(json.length in 2..32_768) { "Workflow must be at most 32 KB." }
        // Bound nesting before Gson sees any untrusted recursive JSON.
        var quoted = false; var escaped = false; var depth = 0
        for (c in json) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) { "Workflow nesting is too deep." } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid workflow JSON." } }
            }
        }
        require(!quoted && depth == 0) { "Incomplete workflow JSON." }
        val root = JsonParser.parseString(json).asJsonObject
        require(root.keySet().all { it in setOf("name", "variables", "steps") }) { "Unknown workflow field." }
        val name = root.get("name")?.asString?.trim().orEmpty()
        require(name.isNotBlank() && name.length <= 160) { "Workflow name is required (max 160 characters)." }
        val variables = root.getAsJsonObject("variables")?.entrySet()?.associate { (key, value) ->
            require(key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}")) && value.isJsonPrimitive && value.asJsonPrimitive.isString)
            require(value.asString.length <= 4000)
            key to value.asString
        }.orEmpty()
        val rawSteps = root.getAsJsonArray("steps") ?: error("Workflow steps are required.")
        require(rawSteps.size() in 1..24) { "Workflow must contain 1–24 steps." }
        val steps = rawSteps.mapIndexed { index, element ->
            val raw = element.asJsonObject
            require(raw.keySet().all { it in setOf("tool", "arguments") }) { "Unknown step field." }
            val toolName = raw.get("tool")?.asString.orEmpty()
            require(toolName in allowed) { "Unsupported workflow tool." }
            val tool = ToolRegistry.getTool(toolName) ?: error("Workflow tool is not registered.")
            val arguments = raw.getAsJsonObject("arguments")?.entrySet()?.associate { (key, value) ->
                require(value.isJsonPrimitive) { "Step arguments must be primitive values." }
                val v = value.asJsonPrimitive
                key to when {
                    v.isString -> variable.replace(v.asString) { match ->
                        variables[match.groupValues[1]] ?: error("A workflow variable is missing.")
                    }.also { require(it.length <= 4000) }
                    v.isBoolean -> v.asBoolean
                    v.isNumber -> v.asBigDecimal
                    else -> error("Invalid step value.")
                }
            }.orEmpty()
            require(ToolArgumentValidator.invalidFields(tool, arguments).isEmpty()) { "Invalid step argument types." }
            require(tool.parameters.filter { it.required }.all { arguments[it.name]?.toString()?.isNotBlank() == true }) { "A required step argument is missing." }
            val approval = RiskPolicy.evaluateRisk(toolName, arguments, name).requiresExplicitConfirmation
            MissionStep(title = "${index+1}. $toolName", toolName = toolName, parameters = arguments,
                riskLevel = if (approval) MissionRisk.HIGH_CONSEQUENTIAL else tool.riskLevel,
                isIdempotent = tool.isIdempotent, maxRetries = if (tool.isIdempotent) 1 else 0,
                requiredPermissions = tool.requiredPermissions)
        }
        return CompiledMacro(name, steps)
    }
}
