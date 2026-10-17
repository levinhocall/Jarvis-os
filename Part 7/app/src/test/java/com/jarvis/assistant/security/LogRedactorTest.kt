package com.jarvis.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    // Obviously fake, but shaped like a real Google API key.
    private val fakeKey = "AIza" + "A".repeat(35)

    @Test
    fun googleApiKeysAreRemovedFromFreeText() {
        val out = LogRedactor.redact("request failed for key $fakeKey on host")
        assertFalse(out.contains(fakeKey))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun urlQueryAndHeaderStyleSecretsAreRemoved() {
        val url = "https://example.test/v1/models?alt=json&key=sekret123&page=2"
        val out = LogRedactor.redact(url)
        assertFalse(out.contains("sekret123"))
        assertTrue(out.contains("page=2"))
        assertFalse(LogRedactor.redact("Authorization: Bearer abc.def-123").contains("abc.def-123"))
        assertFalse(LogRedactor.redact("x-goog-api-key: topsecret").contains("topsecret"))
        assertFalse(LogRedactor.redact("{\"api_key\":\"topsecret\"}").contains("topsecret"))
    }

    @Test
    fun ordinaryTextAndNullAreUntouched() {
        assertEquals("Connection reset by peer", LogRedactor.redact("Connection reset by peer"))
        assertEquals("", LogRedactor.redact(null as String?))
    }

    @Test
    fun throwablesAreRedactedToo() {
        val out = LogRedactor.redact(RuntimeException("GET /x?key=$fakeKey failed"))
        assertTrue(out.startsWith("RuntimeException"))
        assertFalse(out.contains(fakeKey))
    }

    @Test
    fun argumentDescriptionsNeverContainValues() {
        val out = LogRedactor.describeArgs(
            mapOf("recipient_name" to "Rahul", "message" to "secret plans", "app_number" to 2, "confirmed" to true)
        )
        assertFalse(out.contains("Rahul"))
        assertFalse(out.contains("secret plans"))
        assertTrue(out.contains("message=<string:12>"))
        assertTrue(out.contains("app_number=<int>"))
    }
}
