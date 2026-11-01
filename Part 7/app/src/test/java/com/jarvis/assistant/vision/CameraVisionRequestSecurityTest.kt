package com.jarvis.assistant.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraVisionRequestSecurityTest {

    @Test
    fun apiCredentialIsSentInHeaderAndNeverInUrl() {
        val apiKey = "test-secret-key"
        val request = CameraVisionAnalyzer.buildVisionRequest(apiKey, """{"contents":[]}""")

        assertNull("API credentials must not be placed in the request URL", request.url.query)
        assertEquals(apiKey, request.header("x-goog-api-key"))
        assertEquals("POST", request.method)
    }

    @Test
    fun apiCredentialIsTrimmedBeforeTransport() {
        val request = CameraVisionAnalyzer.buildVisionRequest("  test-secret-key  ", """{"contents":[]}""")

        assertEquals("test-secret-key", request.header("x-goog-api-key"))
        assertNull(request.url.query)
    }
}
