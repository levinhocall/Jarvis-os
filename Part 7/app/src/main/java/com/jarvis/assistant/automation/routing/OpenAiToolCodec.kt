package com.jarvis.assistant.automation.routing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.automation.agent.LlmStepDecision
import com.jarvis.assistant.automation.agent.toNativeToolValue
import com.jarvis.assistant.automation.mission.ToolDefinition

object OpenAiToolCodec {
    fun decision(response: LlmResponse): LlmStepDecision {
        val call = response.toolCalls.singleOrNull()
            ?: return LlmStepDecision.Abort("Planner returned no single actionable tool call; prose is not execution evidence.")
        return when (call.toolName) {
            "task_completed" -> (call.arguments["summary"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { LlmStepDecision.Finish(it) } ?: LlmStepDecision.Abort("Completion summary is missing.")
            "request_clarification" -> (call.arguments["question"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { LlmStepDecision.Clarify(it) } ?: LlmStepDecision.Abort("Clarification question is missing.")
            else -> call
        }
    }
    fun declaration(tool: ToolDefinition): Map<String, Any> {
        val properties = tool.parameters.associate { p -> p.name to mapOf(
            "type" to when(p.type.lowercase()) {
                "int", "integer" -> "integer"
                "boolean", "bool" -> "boolean"
                "number", "float", "double" -> "number"
                else -> "string"
            }, "description" to p.description) }
        return mapOf("type" to "function", "function" to mapOf("name" to tool.name,
            "description" to tool.description, "parameters" to mapOf("type" to "object",
                "properties" to properties, "required" to tool.parameters.filter { it.required }.map { it.name },
                "additionalProperties" to false)))
    }
    fun calls(message: JsonObject): List<LlmStepDecision.InvokeTool> {
        try {
            val calls = message.getAsJsonArray("tool_calls") ?: return emptyList()
            require(calls.size() <= 1) { "Only sequential tool execution is supported." }
            return calls.map { raw ->
                val call = raw.asJsonObject
                require(call.get("type")?.asString == "function")
                val function = call.getAsJsonObject("function")
                val name = function.get("name").asString
                require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,79}")))
                val json = function.get("arguments").asString
                require(json.length <= 32_768)
                var depth = 0; var quoted = false; var escaped = false
                for (c in json) {
                    if (quoted) { if (escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
                    else when(c) { '"' -> quoted=true; '{','[' -> { depth++; require(depth <= 8) }; '}',']' -> { depth--; require(depth >= 0) } }
                }
                require(!quoted && depth == 0)
                val args = JsonParser.parseString(json).asJsonObject
                LlmStepDecision.InvokeTool(name, args.entrySet().associate { (key,value) -> key to value.toNativeToolValue() })
            }
        } catch (_: Exception) { throw IllegalStateException("Provider returned an invalid or parallel tool call; nothing was executed.") }
    }
}
