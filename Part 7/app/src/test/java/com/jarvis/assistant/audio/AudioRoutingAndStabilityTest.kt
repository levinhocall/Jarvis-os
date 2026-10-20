package com.jarvis.assistant.audio

import com.jarvis.assistant.data.model.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutingAndStabilityTest {

    @Test
    fun testVoiceSynthesisNormalization() {
        val raw = "The price is ₹1,500 and Rs 250 with 15% discount [urgent]."
        val normalized = VoiceSynthesisRouter.normalizeForSpeech(raw)
        
        assertFalse("Brackets should be stripped", normalized.contains("["))
        assertFalse("Currency ₹ should be converted", normalized.contains("₹"))
        assertTrue("Normalized text should contain rupaye or rupees", normalized.contains("rupaye") || normalized.contains("rupees"))
        assertTrue("Normalized text should contain percent", normalized.contains("percent"))
        assertTrue("Should include polite honorific Sir", normalized.endsWith("Sir."))
    }

    @Test
    fun testCinematicPlaybackConfiguration() {
        // Assert playback rate is brisk (>1.0f) to prevent slow/sluggish speech
        assertTrue("Playback rate must be >= 1.05f for snappy speech", VoiceSynthesisRouter.CINEMATIC_RATE >= 1.05f)
        assertTrue("Pitch must be within natural range (0.85f - 1.05f)", VoiceSynthesisRouter.CINEMATIC_PITCH in 0.85f..1.05f)
    }

    @Test
    fun testLoudspeakerBargeInPreservesSpeechWithoutEarpieceRouting() {
        val assistantSpoken = "I am processing your command now, Sir."
        // Loudspeaker mode requires explicit hotwords to interrupt
        val stopDetected = VoiceInterruptionCoordinator.evaluateBargeIn(
            recognizedText = "stop",
            assistantSpokenText = assistantSpoken,
            currentState = ConversationState.SPEAKING,
            isLoudspeaker = true
        )
        assertTrue("Stop hotword must interrupt in loudspeaker mode", stopDetected)

        val rukoDetected = VoiceInterruptionCoordinator.evaluateBargeIn(
            recognizedText = "ruko",
            assistantSpokenText = assistantSpoken,
            currentState = ConversationState.SPEAKING,
            isLoudspeaker = true
        )
        assertTrue("Ruko hotword must interrupt in loudspeaker mode", rukoDetected)
    }

    @Test
    fun testHeadsetModeAllowsNaturalSpeechBargeIn() {
        val assistantSpoken = "Playing your requested song now."
        val detected = VoiceInterruptionCoordinator.evaluateBargeIn(
            recognizedText = "play another one",
            assistantSpokenText = assistantSpoken,
            currentState = ConversationState.SPEAKING,
            isLoudspeaker = false
        )
        assertTrue("Headset mode must allow multi-token natural speech barge-in", detected)
    }

    @Test
    fun testVoiceFlightRecorderStageTracking() {
        VoiceFlightRecorder.clearHistory()
        assertEquals(0, VoiceFlightRecorder.underrunCount.get())
        assertEquals(0, VoiceFlightRecorder.bargeInCount.get())

        val sessId = VoiceFlightRecorder.startSession("test_suite")
        assertTrue("Session ID must not be blank", sessId.isNotBlank())

        VoiceFlightRecorder.recordWakeDetected()
        VoiceFlightRecorder.recordWakeAckSpoken()
        VoiceFlightRecorder.recordSpeechEnd()
        VoiceFlightRecorder.recordAiRequestSent()
        VoiceFlightRecorder.recordFirstTokenReceived()
        VoiceFlightRecorder.recordFirstAudioFrame()
        VoiceFlightRecorder.recordPlaybackComplete()
        VoiceFlightRecorder.recordBargeIn("user_speech")
        VoiceFlightRecorder.recordUnderrun()
        VoiceFlightRecorder.endSession("test_complete")

        val events = VoiceFlightRecorder.getRecentEvents()
        assertTrue("Events should be recorded", events.size >= 10)
        assertEquals(1, VoiceFlightRecorder.bargeInCount.get())
        assertEquals(1, VoiceFlightRecorder.underrunCount.get())

        val stages = events.map { it.stage }
        assertTrue(stages.contains("SESSION_START"))
        assertTrue(stages.contains("WAKE_DETECTED"))
        assertTrue(stages.contains("WAKE_ACK_SPOKEN"))
        assertTrue(stages.contains("USER_SPEECH_END"))
        assertTrue(stages.contains("AI_REQUEST_SENT"))
        assertTrue(stages.contains("AI_FIRST_TOKEN"))
        assertTrue(stages.contains("FIRST_AUDIO_FRAME"))
        assertTrue(stages.contains("PLAYBACK_COMPLETE"))
        assertTrue(stages.contains("BARGE_IN_TRIGGERED"))
        assertTrue(stages.contains("AUDIO_UNDERRUN"))
        assertTrue(stages.contains("SESSION_END"))
    }
}
