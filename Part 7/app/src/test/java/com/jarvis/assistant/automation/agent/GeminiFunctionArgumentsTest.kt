package com.jarvis.assistant.automation.agent

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiFunctionArgumentsTest {
    @Test
    fun nestedFunctionArgumentsPreserveNativeJsonTypes() {
        val json = JsonParser.parseString(
            """{"count":3,"enabled":true,"name":"JARVIS","ratio":0.75,"tags":["a","b"],"config":{"safe":true}}"""
        )
        val values = json.toNativeToolValue() as Map<*, *>

        assertEquals(3, (values["count"] as Number).toInt())
        assertEquals(true, values["enabled"])
        assertEquals("JARVIS", values["name"])
        assertEquals(0.75, (values["ratio"] as Number).toDouble(), 0.0001)
        assertEquals(listOf("a", "b"), values["tags"])
        assertTrue(values["config"] is Map<*, *>)
        assertEquals(true, (values["config"] as Map<*, *>)["safe"])
    }
}
