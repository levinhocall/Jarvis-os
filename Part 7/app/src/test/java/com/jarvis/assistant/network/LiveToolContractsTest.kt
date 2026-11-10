package com.jarvis.assistant.network

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class LiveToolContractsTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private val contracts = LiveToolContracts(listOf(json("""{"name":"test","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN"},"message":{"type":"STRING"}},"required":["enabled"]}}""")))

    @Test fun acceptsOnlyDeclaredTypedArguments() {
        assertNull(contracts.rejection("test", json("""{"enabled":true,"message":"कल मिलते हैं"}""")))
        assertNotNull(contracts.rejection("test", json("""{"enabled":"true"}""")))
        assertNotNull(contracts.rejection("test", json("""{"enabled":true,"shell":"delete"}""")))
        assertNotNull(contracts.rejection("unknown", json("{}")))
        assertNotNull(contracts.rejection("test", json("{}")))
    }

    @Test fun errorsDoNotEchoPrivateValues() {
        val rejection = contracts.rejection("test", json("""{"enabled":{"phone":"private-contact"}}"""))!!
        assertFalse(rejection.contains("private-contact"))
    }
}
