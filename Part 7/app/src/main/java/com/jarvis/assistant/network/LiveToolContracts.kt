package com.jarvis.assistant.network

import com.google.gson.JsonObject
import com.jarvis.assistant.automation.agent.toNativeToolValue
import com.jarvis.assistant.automation.mission.ToolArgumentValidator
import com.jarvis.assistant.automation.mission.ToolDefinition
import com.jarvis.assistant.automation.mission.ToolParameter

/** Derived from the exact declarations sent to Live, avoiding a second handwritten registry. */
internal class LiveToolContracts(declarations: List<JsonObject>) {
    private val tools = declarations.associate { declaration ->
        val schema = declaration.getAsJsonObject("parameters") ?: JsonObject()
        val required = schema.getAsJsonArray("required")?.map { it.asString }?.toSet().orEmpty()
        val parameters = schema.getAsJsonObject("properties")?.entrySet()?.map { (name, value) ->
            ToolParameter(name, value.asJsonObject.get("type")?.asString.orEmpty(), "", name in required)
        }.orEmpty()
        val name = declaration.get("name").asString
        name to ToolDefinition(name, "", parameters)
    }

    /** Messages contain field names only, never argument contents or personal data. */
    fun rejection(name: String, args: JsonObject): String? {
        val tool = tools[name] ?: return "Unknown tool. No action was executed."
        if (args.toString().length > 128 * 1024) return "Tool arguments exceed the input limit."
        val expected = tool.parameters.map { it.name }.toSet()
        if (args.keySet().any { it !in expected }) return "Unexpected tool argument. No action was executed."
        @Suppress("UNCHECKED_CAST")
        val values = args.toNativeToolValue() as Map<String, Any?>
        val missing = tool.parameters.filter { it.required && (values[it.name] == null ||
            (values[it.name] as? String)?.isBlank() == true) }.map { it.name }
        if (missing.isNotEmpty()) return "Missing required arguments: ${missing.joinToString()}."
        val invalid = ToolArgumentValidator.invalidFields(tool, values)
        return if (invalid.isEmpty()) null else "Invalid argument types: ${invalid.joinToString()}."
    }
}
