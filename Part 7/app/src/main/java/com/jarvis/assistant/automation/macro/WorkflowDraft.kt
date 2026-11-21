package com.jarvis.assistant.automation.macro

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.jarvis.assistant.automation.mission.ToolDefinition
import java.util.Locale

internal data class WorkflowDraftStep(val tool: ToolDefinition, val fields: Map<String, String>)

/** Produces the same reviewed JSON format used by the saved workflow library. */
internal fun buildWorkflowJson(name: String, steps: List<WorkflowDraftStep>): String {
    require(steps.size in 1..24)
    val allowed = MacroCompiler.builderTools().associateBy(ToolDefinition::name)
    val variables = JsonObject()
    val encodedSteps = JsonArray()
    steps.forEachIndexed { stepIndex, step ->
        require(allowed[step.tool.name] == step.tool)
        val arguments = JsonObject()
        step.tool.parameters.forEachIndexed { index, parameter ->
            val raw = step.fields[parameter.name].orEmpty()
            require(!parameter.required || raw.isNotBlank())
            if (raw.isBlank()) return@forEachIndexed
            when (parameter.type.lowercase(Locale.ROOT)) {
                "string" -> {
                    require(raw.length <= 4000)
                    // The compiler expands each placeholder once, keeping user text literal.
                    val key = "step${stepIndex}_field$index"
                    variables.addProperty(key, raw)
                    arguments.addProperty(parameter.name, "\${$key}")
                }
                "int", "integer" -> arguments.addProperty(parameter.name, raw.trim().toInt())
                "boolean" -> arguments.addProperty(parameter.name, raw.trim().lowercase(Locale.ROOT).toBooleanStrict())
                else -> error("Unsupported workflow field type")
            }
        }
        encodedSteps.add(JsonObject().apply {
            addProperty("tool", step.tool.name)
            add("arguments", arguments)
        })
    }
    return JsonObject().apply {
        addProperty("name", name)
        add("variables", variables)
        add("steps", encodedSteps)
    }.toString()
}
