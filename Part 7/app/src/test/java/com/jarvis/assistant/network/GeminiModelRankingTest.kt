package com.jarvis.assistant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same-version ranking: the plain stable flash model must beat lite / preview / experimental
 * variants (previously "gemini-3.8-flash-lite" sorted ahead of "gemini-3.8-flash" because the
 * tie-break was a plain string comparison where the longer id wins).
 */
class GeminiModelRankingTest {

    private val json = """
        {"models":[
          {"name":"models/gemini-3.8-flash-lite","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.8-flash-preview","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.8-flash-exp","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}
        ]}
    """.trimIndent()

    @Test
    fun plainFlashIsFirstAndLiteIsLastAmongSameVersion() {
        val result = GeminiModelResolver.parseCandidateModels(json)
        assertEquals("gemini-3.8-flash", result.first())
        assertTrue(result.indexOf("gemini-3.8-flash-preview") < result.indexOf("gemini-3.8-flash-lite"))
        assertTrue(result.indexOf("gemini-3.8-flash-exp") < result.indexOf("gemini-3.8-flash-lite"))
    }

    @Test
    fun retiredTwoPointFiveIsStillExcludedWhenModernModelsExist() {
        val result = GeminiModelResolver.parseCandidateModels(json)
        assertFalse(result.contains("gemini-2.5-flash"))
    }

    @Test
    fun newerVersionStillBeatsPlainOlderVersion() {
        val mixed = """
            {"models":[
              {"name":"models/gemini-3.1-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3.8-flash-lite","supportedGenerationMethods":["generateContent"]}
            ]}
        """.trimIndent()
        val result = GeminiModelResolver.parseCandidateModels(mixed)
        assertEquals("gemini-3.8-flash-lite", result.first())
    }
}
