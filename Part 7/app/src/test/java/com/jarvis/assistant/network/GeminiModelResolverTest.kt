package com.jarvis.assistant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiModelResolverTest {

    @Before
    fun setup() {
        GeminiModelResolver.invalidateCache()
    }

    private val fakeListModelsJson = """
        {
          "models": [
            {
              "name": "models/gemini-1.5-flash",
              "supportedGenerationMethods": ["generateContent"]
            },
            {
              "name": "models/gemini-2.5-flash",
              "supportedGenerationMethods": ["generateContent"]
            },
            {
              "name": "models/gemini-3.1-flash",
              "supportedGenerationMethods": ["generateContent"]
            },
            {
              "name": "models/gemini-3.8-flash",
              "supportedGenerationMethods": ["generateContent"]
            },
            {
              "name": "models/gemini-2.0-flash-live-preview",
              "supportedGenerationMethods": ["generateContent"]
            },
            {
              "name": "models/image-generation-001",
              "supportedGenerationMethods": ["generateImage"]
            },
            {
              "name": "models/gemini-3.8-flash-image",
              "supportedGenerationMethods": ["generateContent"]
            },
            {
              "name": "models/text-embedding-004",
              "supportedGenerationMethods": ["embedContent"]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testParseCandidateModels_excludesNonRestAndPrioritizesModern3x() {
        val candidates = GeminiModelResolver.parseCandidateModels(fakeListModelsJson)

        // 1. Should exclude live, image, embedding models
        assertFalse(candidates.any { it.contains("live") })
        assertFalse(candidates.any { it.contains("image") })
        assertFalse(candidates.any { it.contains("embedding") })

        // 2. Should exclude major version < 3 when version >= 3 exists
        assertFalse(candidates.any { it == "gemini-2.5-flash" })
        assertFalse(candidates.any { it == "gemini-1.5-flash" })

        // 3. Should prioritize version DESC: 3.8 should be first!
        assertEquals("gemini-3.8-flash", candidates.first())
        assertTrue(candidates.contains("gemini-3.1-flash"))
    }

    @Test
    fun testParseCandidateModels_whenOnlyLegacyExists_fallsBackGracefully() {
        val legacyJson = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "supportedGenerationMethods": ["generateContent"]
                }
              ]
            }
        """.trimIndent()

        val candidates = GeminiModelResolver.parseCandidateModels(legacyJson)
        // Since major < 3 is the only one in the mock response, it accepts it, but still appends known-good fallback constants
        assertTrue(candidates.contains("gemini-2.5-flash"))
        assertTrue(candidates.contains(GeminiModelResolver.KNOWN_GOOD_FLASH))
    }

    @Test
    fun testExtractReplacementHint_parsesStandardGoogleErrorMessage() {
        val errorMsg = """
            404 NOT_FOUND: This model models/gemini-2.5-flash is no longer available to new users. Please update your code to use models/gemini-3.8-flash
        """.trimIndent()

        val hint = GeminiModelResolver.extractReplacementHint(errorMsg)
        assertNotNull(hint)
        assertEquals("gemini-3.8-flash", hint)
    }

    @Test
    fun testExtractReplacementHint_ignoresSpecializedLiveHints() {
        val liveHint = "Please update your code to use models/gemini-2.0-flash-live-preview"
        val hint = GeminiModelResolver.extractReplacementHint(liveHint)
        assertEquals(null, hint)
    }

    @Test
    fun testExecuteWithModelFallback_advancesToNextCandidateOn404() {
        val attemptedModels = mutableListOf<String>()

        val result = GeminiModelResolver.executeWithModelFallback(
            apiKey = "dummy_key",
            initialCandidates = listOf("gemini-2.5-flash", "gemini-3.8-flash")
        ) { model ->
            attemptedModels.add(model)
            if (model == "gemini-2.5-flash") {
                throw RuntimeException("404 NOT_FOUND: models/gemini-2.5-flash is no longer available to new users. Please update your code to use models/gemini-3.8-flash")
            }
            "SUCCESS_OUTPUT"
        }

        assertEquals("SUCCESS_OUTPUT", result)
        assertEquals(2, attemptedModels.size)
        assertEquals("gemini-2.5-flash", attemptedModels[0])
        assertEquals("gemini-3.8-flash", attemptedModels[1])

        // Cached model should now be the working model
        assertEquals("gemini-3.8-flash", GeminiModelResolver.getCachedModel())
    }

    @Test(expected = RuntimeException::class)
    fun testExecuteWithModelFallback_doesNotAdvanceOnQuota429() {
        val attemptedModels = mutableListOf<String>()

        GeminiModelResolver.executeWithModelFallback(
            apiKey = "dummy_key",
            initialCandidates = listOf("model-A", "model-B")
        ) { model ->
            attemptedModels.add(model)
            throw RuntimeException("429 RESOURCE_EXHAUSTED: Rate limit exceeded")
        }

        // Should NOT advance to model-B when it's a quota issue
        assertEquals(1, attemptedModels.size)
    }
}
