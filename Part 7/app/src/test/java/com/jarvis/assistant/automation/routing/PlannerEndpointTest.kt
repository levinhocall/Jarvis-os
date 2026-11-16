package com.jarvis.assistant.automation.routing
import org.junit.Assert.*
import org.junit.Test
class PlannerEndpointTest {
    @Test fun acceptsRootAndCompleteEndpoint() {
        assertEquals("https://example.com/v1",PlannerEndpoint.normalize("https://example.com/v1/chat/completions/"))
        assertEquals("https://example.com/v1",PlannerEndpoint.normalize("https://example.com/v1"))
    }
    @Test fun rejectsInsecureAndEmbeddedSecrets() {
        listOf("http://example.com/v1","https://secret@example.com/v1","https://example.com/?key=secret",
            "https://example.com/#secret","file:///tmp/secret","").forEach { value ->
            assertTrue(value,runCatching { PlannerEndpoint.normalize(value) }.isFailure)
        }
    }
}
