package com.jarvis.assistant.automation.routing

import com.google.gson.JsonParser
import com.jarvis.assistant.automation.agent.LlmStepDecision
import com.jarvis.assistant.automation.mission.ToolDefinition
import com.jarvis.assistant.automation.mission.ToolParameter
import org.junit.Assert.*
import org.junit.Test

class OpenAiToolCodecTest {
    @Test fun schemaPreservesBooleanAndIntegerTypes() {
        val declaration = OpenAiToolCodec.declaration(ToolDefinition("test","test",listOf(
            ToolParameter("enabled","Boolean","flag"),ToolParameter("count","Int","count",false))))
        val function = declaration["function"] as Map<*,*>
        val params = function["parameters"] as Map<*,*>
        val properties = params["properties"] as Map<*,*>
        assertEquals("boolean",(properties["enabled"] as Map<*,*>)["type"])
        assertEquals(listOf("enabled"),params["required"])
    }
    @Test fun parsesTypedArgumentsAndRejectsParallelRequests() {
        val body = """{"tool_calls":[{"type":"function","function":{"name":"test","arguments":"{\"enabled\":true,\"count\":2}"}}]}"""
        val calls = OpenAiToolCodec.calls(JsonParser.parseString(body).asJsonObject)
        assertEquals(true,calls.single().arguments["enabled"])
        assertEquals(2,(calls.single().arguments["count"] as Number).toInt())
        val parallel = """{"tool_calls":[{},{}]}"""
        assertTrue(runCatching { OpenAiToolCodec.calls(JsonParser.parseString(parallel).asJsonObject) }.isFailure)
    }
    @Test fun proseCannotCompleteMissionAndClarificationIsSeparate() {
        assertTrue(OpenAiToolCodec.decision(LlmResponse("done")) is LlmStepDecision.Abort)
        val response = LlmResponse("",listOf(LlmStepDecision.InvokeTool("request_clarification",mapOf("question" to "Which contact?"))))
        assertEquals(LlmStepDecision.Clarify("Which contact?"),OpenAiToolCodec.decision(response))
    }
}
