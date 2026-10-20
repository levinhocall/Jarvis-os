package com.jarvis.assistant.audio

import com.jarvis.assistant.data.model.ConversationState
import org.junit.Assert.*
import org.junit.Test

class VoiceInterruptionCoordinatorEnhancedTest {

    @Test
    fun testExtendedHindiCompoundHotwords() {
        val hindiPhrases = listOf(
            "ek second",
            "bas bas",
            "chup ho jao",
            "nahi chahiye",
            "rehne do",
            "arey ruko",
            "wait wait",
            "ruk ruk",
            "suno na",
            "pause karo",
            "ruko ruko",
            "chup kar",
            "chup raho"
        )
        val assistantSpoken = "Main aapke liye YouTube par song chala raha hoon, thoda wait kijiye."

        for (phrase in hindiPhrases) {
            val isBargeIn = VoiceInterruptionCoordinator.evaluateBargeIn(
                recognizedText = phrase,
                assistantSpokenText = assistantSpoken,
                currentState = ConversationState.SPEAKING,
                isLoudspeaker = true
            )
            assertTrue("Expected extended Hindi hotword '$phrase' to trigger barge-in on loudspeaker", isBargeIn)
        }
    }

    @Test
    fun testAcousticLeakageSubtraction() {
        // Speaker is silent (0.0 amp): mic amplitude should not be altered
        val effectiveSilent = VoiceInterruptionCoordinator.calculateEffectiveMicAmplitude(
            rawMicAmplitude = 0.20f,
            speakerPlaybackAmplitude = 0.0f
        )
        assertEquals(0.20f, effectiveSilent, 0.001f)

        // Assistant is speaking loud (0.30 amp) and mic picks up 0.22 amp
        // Coupling factor 0.55 -> estimated leakage = 0.30 * 0.55 = 0.165
        // Effective external amplitude = 0.22 - 0.165 = 0.055
        val effectiveLoud = VoiceInterruptionCoordinator.calculateEffectiveMicAmplitude(
            rawMicAmplitude = 0.22f,
            speakerPlaybackAmplitude = 0.30f,
            couplingFactor = 0.55f
        )
        assertTrue("Effective external amplitude should be reduced due to acoustic leakage subtraction", effectiveLoud < 0.22f)
        assertEquals(0.055f, effectiveLoud, 0.005f)

        // If mic amplitude is less than leakage, it should clamp to 0f without negative numbers
        val clampedZero = VoiceInterruptionCoordinator.calculateEffectiveMicAmplitude(
            rawMicAmplitude = 0.10f,
            speakerPlaybackAmplitude = 0.30f,
            couplingFactor = 0.55f
        )
        assertEquals(0f, clampedZero, 0.001f)
    }

    @Test
    fun testPocketDetectionStateSuppression() {
        // Test that PocketDetectionManager tracks in-pocket state safely
        assertFalse(PocketDetectionManager.isDeviceInPocket())
    }
}
