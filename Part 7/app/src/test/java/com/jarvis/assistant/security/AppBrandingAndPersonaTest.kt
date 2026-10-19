package com.jarvis.assistant.security

import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.util.PromptGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBrandingAndPersonaTest {

    @Test
    fun testDeveloperInfoIdentifiesNazib() {
        assertEquals("NAZIB", DeveloperInfo.DEVELOPER_NAME)
        assertEquals("© 2026 NAZIB. All Rights Reserved.", DeveloperInfo.COPYRIGHT_NOTICE)
        assertEquals("NAZIB — Official JARVIS Series", DeveloperInfo.VERIFIED_DEVELOPER)
        assertEquals("com.jarvis.assistant", DeveloperInfo.APP_OFFICIAL_ID)

        // Rule 8: Upstream license credit must be preserved
        assertEquals("TAMCL v1.0", DeveloperInfo.LICENSE)
        assertTrue(DeveloperInfo.LICENSE_DETAIL.contains("Tech GPTx"))
    }

    @Test
    fun testSecurityManagerIdentifiesNazib() {
        assertEquals("NAZIB", SecurityManager.DEVELOPER_NAME)
        assertEquals("Copyright © 2026 NAZIB. All Rights Reserved.", SecurityManager.COPYRIGHT_NOTICE)

        // Rule 8: Upstream attribution preserved
        assertTrue(SecurityManager.LICENSE_NAME.contains("TAMCL v1.0"))
        assertTrue(SecurityManager.LICENSE_NAME.contains("Tech GPTx"))
    }

    @Test
    fun testPersonalityConstantsAreNazibBranded() {
        assertEquals("NAZIB Mode", GeminiConstants.PERSONALITY_NAZIB)
        assertEquals(GeminiConstants.PERSONALITY_NAZIB, GeminiConstants.PERSONALITY_TECHGPT_X)
        assertTrue(GeminiConstants.SUPPORTED_PERSONALITIES.contains(GeminiConstants.PERSONALITY_NAZIB))
    }

    @Test
    fun testPromptGeneratorDeclaresNazibAsCreator() {
        val systemPrompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_NAZIB,
            userName = "NAZIB",
            userLocation = "Delhi, India"
        )

        // Verify NAZIB branding in prompt
        assertTrue("Prompt should contain NAZIB Mode", systemPrompt.contains("[PERSONALITY: NAZIB Mode]"))
        assertTrue("Prompt should declare NAZIB as developer/creator", systemPrompt.contains("created by NAZIB") || systemPrompt.contains("NAZIB, an expert AI creator"))
        assertFalse("Prompt should not contain legacy persona name TechGPT X Mode", systemPrompt.contains("TechGPT X Mode"))
    }
}
