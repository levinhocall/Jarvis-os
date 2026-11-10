package com.jarvis.assistant.automation.mission

import com.google.gson.JsonParser
import com.jarvis.assistant.automation.agent.toNativeToolValue
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentValidatorTest {
    private val tool = ToolDefinition("test", "test", listOf(
        ToolParameter("count", "Int", "count"), ToolParameter("enabled", "Boolean", "enabled"),
        ToolParameter("text", "String", "text"), ToolParameter("optional", "Int", "optional", false)
    ))

    @Test fun nativeGeminiTypesAreAccepted() {
        val json = JsonParser.parseString("""{"count":2,"enabled":true,"text":"कल का reminder"}""")
        @Suppress("UNCHECKED_CAST")
        assertTrue(ToolArgumentValidator.invalidFields(tool, json.toNativeToolValue() as Map<String, Any?>).isEmpty())
    }

    @Test fun rejectsStringCoercionFractionalOverflowAndContainers() {
        assertEquals(listOf("count", "enabled", "text"), ToolArgumentValidator.invalidFields(tool,
            mapOf("count" to "2", "enabled" to "true", "text" to mapOf("command" to "delete"))))
        for (number in listOf(1.5, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE)) {
            assertEquals(listOf("count"), ToolArgumentValidator.invalidFields(tool, mapOf("count" to number)))
        }
    }

    @Test fun optionalProvidedValuesAreStillValidated() {
        assertEquals(listOf("optional"), ToolArgumentValidator.invalidFields(tool, mapOf("optional" to false)))
    }

    @Test fun registryBlocksInvalidArgumentsBeforePlatformExecution() {
        val invalid = ToolRegistry.validate("schedule_reminder", mapOf("title" to "t", "description" to "d", "delay_minutes" to "10"))
        assertTrue(invalid is ToolValidationResult.InvalidParameters)
        assertEquals(ToolValidationResult.Valid, ToolRegistry.validate("schedule_reminder",
            mapOf("title" to "t", "description" to "d", "delay_minutes" to 10)))
    }
}
