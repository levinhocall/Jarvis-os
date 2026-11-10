package com.jarvis.assistant.automation.mission

import org.junit.Assert.*
import org.junit.Test

class MissionActionIdentityTest {
    @Test fun fieldOrderAndNestedObjectOrderDoNotChangeIdentity() {
        val first = linkedMapOf<String, Any?>("contact" to "Rahul", "message" to "hi", "options" to linkedMapOf("a" to 1, "b" to true))
        val second = linkedMapOf<String, Any?>("options" to linkedMapOf("b" to true, "a" to 1), "message" to "hi", "contact" to "Rahul")
        assertEquals(MissionActionIdentity.key("whatsapp_send", first), MissionActionIdentity.key("whatsapp_send", second))
    }

    @Test fun aliasesEquivalentNumbersAndAbsentOptionalNullAreStable() {
        assertEquals(MissionActionIdentity.key("send_whatsapp", mapOf("count" to 1, "optional" to null)),
            MissionActionIdentity.key("whatsapp_send", mapOf("count" to 1.0)))
    }

    @Test fun changedMessagesTypesAndArrayOrderRemainDistinct() {
        fun key(value: Any?) = MissionActionIdentity.key("whatsapp_send", mapOf("message" to value))
        assertNotEquals(key("Hi"), key("hi"))
        assertNotEquals(key(true), key("true"))
        assertNotEquals(key(listOf(1, 2)), key(listOf(2, 1)))
        val private = key("private-recipient-and-message")
        assertFalse(private.contains("private-recipient"))
        assertTrue(private.startsWith("mission-v1-sha256:"))
    }

    @Test fun rejectsNonFiniteUnsupportedAndExcessivelyNestedArguments() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Any())) {
            assertThrows(IllegalArgumentException::class.java) { MissionActionIdentity.key("test", mapOf("value" to value)) }
        }
        val cycle = mutableMapOf<String, Any?>()
        cycle["self"] = cycle
        assertThrows(IllegalArgumentException::class.java) { MissionActionIdentity.key("test", cycle) }
    }

    @Test fun previousRawKeysAndNewFingerprintsBothRemainHonored() {
        val parameters = linkedMapOf<String, Any?>("contact" to "Rahul", "message" to "hi")
        val legacy = "whatsapp_send:$parameters"
        val modern = MissionActionIdentity.key("whatsapp_send", parameters)
        assertEquals(20L, MissionActionIdentity.previousTimestamp("whatsapp_send", parameters, mapOf(legacy to 20L)))
        assertEquals(30L, MissionActionIdentity.previousTimestamp("whatsapp_send", parameters, mapOf(legacy to 20L, modern to 30L)))
        assertEquals(0L, MissionActionIdentity.previousTimestamp("whatsapp_send", parameters, emptyMap()))
    }
}
