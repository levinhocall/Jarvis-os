package com.jarvis.assistant.data.personalization

import com.jarvis.assistant.util.PromptGenerator
import org.junit.Assert.*
import org.junit.Test

class AssistantPersonalityTest {
    @Test fun allPersonalitiesPreserveLanguageAndAuthorization() {
        for (personality in AssistantPersonality.entries) {
            for (language in listOf("Auto", "Hindi", "Hinglish", "English")) {
                val prompt = PromptGenerator.generateSystemPrompt(personality = personality.name, userName = "", language = language)
                assertTrue(prompt.contains(personality.instruction()))
                assertTrue(prompt.contains("Never claim to be human or have a body"))
                assertTrue(prompt.contains("never overrides authorization"))
                if (language != "Auto") assertTrue(prompt.contains("STRICT SPOKEN LANGUAGE MANDATE - $language ONLY"))
            }
        }
    }

    @Test fun legacyPreferencesAndUnknownValuesRemainSafe() {
        assertEquals(AssistantPersonality.NORMAL, AssistantPersonality.resolve("Jarvis Pro"))
        assertEquals(AssistantPersonality.GF_MODE, AssistantPersonality.resolve("Girlfriend Mode"))
        assertEquals(AssistantPersonality.PROFESSIONAL, AssistantPersonality.resolve("Focus Mode"))
        assertEquals(AssistantPersonality.NORMAL, AssistantPersonality.resolve("corrupt"))
        assertTrue(AssistantPersonality.instructionFor("NAZIB Mode").contains("[PERSONALITY: NAZIB Mode]"))
    }
}
