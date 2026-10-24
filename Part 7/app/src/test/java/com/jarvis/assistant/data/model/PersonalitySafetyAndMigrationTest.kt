package com.jarvis.assistant.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalitySafetyAndMigrationTest {

    @Test
    fun testLegacyConstantsMapToCorrectPersonality() {
        assertEquals(Personality.JARVIS_PRO, Personality.fromId(GeminiConstants.PERSONALITY_ASSISTANT))
        assertEquals(Personality.GIRLFRIEND, Personality.fromId(GeminiConstants.PERSONALITY_GIRLFRIEND))
        assertEquals(Personality.BOYFRIEND, Personality.fromId(GeminiConstants.PERSONALITY_BOYFRIEND))
        assertEquals(Personality.NAZIB, Personality.fromId(GeminiConstants.PERSONALITY_NAZIB))
        assertEquals(Personality.NAZIB, Personality.fromId(GeminiConstants.PERSONALITY_TECHGPT_X))
        assertEquals(Personality.COMPANION, Personality.fromId(GeminiConstants.PERSONALITY_COMPANION))
        assertEquals(Personality.PROFESSIONAL, Personality.fromId(GeminiConstants.PERSONALITY_PROFESSIONAL))
        assertEquals(Personality.PROFESSIONAL, Personality.fromId(GeminiConstants.PERSONALITY_FOCUS))

        // New personas
        assertEquals(Personality.FRIENDLY, Personality.fromId("Friendly Mode"))
        assertEquals(Personality.FUNNY, Personality.fromId("Funny Mode"))
        assertEquals(Personality.TEACHER, Personality.fromId("Teacher Mode"))
        assertEquals(Personality.DEVELOPER, Personality.fromId("Developer Mode"))
        assertEquals(Personality.HUNGRY, Personality.fromId("Foodie Mode"))
        assertEquals(Personality.NAUTANKI, Personality.fromId("Nautanki Mode"))

        // Null / blank / unknown fallback to JARVIS_PRO
        assertEquals(Personality.JARVIS_PRO, Personality.fromId(null))
        assertEquals(Personality.JARVIS_PRO, Personality.fromId(""))
        assertEquals(Personality.JARVIS_PRO, Personality.fromId("unknown_mode_xyz"))
    }

    @Test
    fun testCompanionPersonasContainMandatorySafetyDirective() {
        val companionPersonas = listOf(Personality.GIRLFRIEND, Personality.BOYFRIEND, Personality.COMPANION)

        for (p in companionPersonas) {
            assertTrue("${p.name} must be flagged as companion", p.isCompanion)
            assertTrue(
                "${p.name} must include exact safety directive",
                p.systemInstruction.contains(Personality.COMPANION_SAFETY_DIRECTIVE)
            )
            assertTrue(
                "${p.name} must declare not human and no physical body",
                p.systemInstruction.contains("I am an AI assistant, not a human. I do not have a physical body.")
            )
            assertTrue(
                "${p.name} must decline sexual content or romantic delusions",
                p.systemInstruction.contains("I do not engage in explicit sexual content or romantic delusions.")
            )
            assertTrue(
                "${p.name} must respect topic change",
                p.systemInstruction.contains("If you wish to change topic, I will immediately respect it.")
            )
        }
    }

    @Test
    fun testNonCompanionPersonasAreNotFlaggedCompanion() {
        val nonCompanions = listOf(
            Personality.JARVIS_PRO,
            Personality.FRIENDLY,
            Personality.FUNNY,
            Personality.TEACHER,
            Personality.DEVELOPER,
            Personality.HUNGRY,
            Personality.NAUTANKI,
            Personality.NAZIB,
            Personality.PROFESSIONAL
        )

        for (p in nonCompanions) {
            assertFalse("${p.name} must NOT be flagged as companion", p.isCompanion)
        }
    }

    @Test
    fun testAllPersonalitiesHaveNonBlankMetadata() {
        assertTrue("Should have at least 10 selectable personalities", Personality.entries.size >= 10)

        for (p in Personality.entries) {
            assertTrue("${p.name} id must not be blank", p.id.isNotBlank())
            assertTrue("${p.name} displayName must not be blank", p.displayName.isNotBlank())
            assertTrue("${p.name} description must not be blank", p.description.isNotBlank())
            assertTrue("${p.name} defaultVoice must not be blank", p.defaultVoice.isNotBlank())
            assertTrue("${p.name} systemInstruction must not be blank", p.systemInstruction.isNotBlank())
        }
    }

    @Test
    fun testEffectiveInstructionReplacesAssistantName() {
        val customName = "Lia"
        val instruction = Personality.JARVIS_PRO.getEffectiveInstruction(customName)
        assertTrue(instruction.contains("LIA") || instruction.contains("Lia"))
    }
}
