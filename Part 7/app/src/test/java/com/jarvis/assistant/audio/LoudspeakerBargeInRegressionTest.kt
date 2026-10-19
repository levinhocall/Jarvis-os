package com.jarvis.assistant.audio

import com.jarvis.assistant.data.model.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LoudspeakerBargeInRegressionTest {

    @Test
    fun testInstantHotwordsInterruptDuringSpeaking() {
        val hotwords = listOf("stop", "ruko", "bas", "wait", "suno", "chup", "cancel", "hold", "pause")
        val assistantSpoken = "I am currently explaining the architectural details of the neural assistant."

        for (hw in hotwords) {
            val detected = VoiceInterruptionCoordinator.evaluateBargeIn(
                recognizedText = hw,
                assistantSpokenText = assistantSpoken,
                currentState = ConversationState.SPEAKING
            )
            assertTrue("Expected hotword '$hw' to interrupt playback", detected)
        }
    }

    @Test
    fun testNaturalSpeechInterruptsWhenNotEcho() {
        val userSpeech = "what is the time now"
        val assistantSpoken = "Playing the latest playlist on Spotify for you."

        val detected = VoiceInterruptionCoordinator.evaluateBargeIn(
            recognizedText = userSpeech,
            assistantSpokenText = assistantSpoken,
            currentState = ConversationState.SPEAKING
        )
        assertTrue("Expected distinct natural speech to interrupt assistant", detected)
    }

    @Test
    fun testAssistantEchoSuppressed() {
        val assistantSpoken = "Playing the latest playlist on Spotify for you."
        // Microphone picks up the assistant's own loudspeaker audio
        val echoSpeech = "playing the latest playlist"

        val detected = VoiceInterruptionCoordinator.evaluateBargeIn(
            recognizedText = echoSpeech,
            assistantSpokenText = assistantSpoken,
            currentState = ConversationState.SPEAKING
        )
        assertFalse("Loudspeaker echo should be suppressed and not trigger barge-in", detected)
    }

    @Test
    fun testLoudspeakerIgnoresHallucinatedEnglishTokensOnHindiAudio() {
        val assistantSpoken = "नमस्ते सर, मैं आपके लिए क्या कर सकता हूँ?"
        // Vosk hallucinating short English words like "tell" or "what" from acoustic leakage
        val falseTokens = listOf("tell", "what", "open", "play", "how", "tell me")
        for (token in falseTokens) {
            val detected = VoiceInterruptionCoordinator.evaluateBargeIn(
                recognizedText = token,
                assistantSpokenText = assistantSpoken,
                currentState = ConversationState.SPEAKING,
                isLoudspeaker = true
            )
            assertFalse("Expected false English token '$token' to be ignored on loudspeaker during Hindi speech", detected)
        }
    }

    @Test
    fun testLoudspeakerInterruptsOnExplicitHotwords() {
        val assistantSpoken = "नमस्ते सर, मैं आपके लिए क्या कर सकता हूँ?"
        val hotwords = listOf("stop", "ruko", "bas", "wait", "suno", "chup", "cancel", "hold")
        for (hw in hotwords) {
            val detected = VoiceInterruptionCoordinator.evaluateBargeIn(
                recognizedText = hw,
                assistantSpokenText = assistantSpoken,
                currentState = ConversationState.SPEAKING,
                isLoudspeaker = true
            )
            assertTrue("Expected hotword '$hw' to interrupt playback even in loudspeaker mode", detected)
        }
    }

    @Test
    fun testBargeInIgnoredWhenIdle() {
        val detected = VoiceInterruptionCoordinator.evaluateBargeIn(
            recognizedText = "stop",
            assistantSpokenText = "",
            currentState = ConversationState.IDLE
        )
        assertFalse("Interruption should not trigger when conversation state is IDLE", detected)
    }

    @Test
    fun testLateAudioChunksRejectedAfterInterruption() {
        val initialEpoch = VoiceInterruptionCoordinator.activeEpoch.get()
        assertTrue(VoiceInterruptionCoordinator.isChunkValid(initialEpoch))

        // Interruption occurs
        VoiceInterruptionCoordinator.resetDebounce()
        val executed = AtomicBoolean(false)
        VoiceInterruptionCoordinator.requestBargeIn("test") { newEpoch ->
            executed.set(true)
        }
        assertTrue(executed.get())

        val postEpoch = VoiceInterruptionCoordinator.activeEpoch.get()
        assertTrue("Epoch must advance on interruption", postEpoch > initialEpoch)

        // Late chunk belonging to previous turn must be rejected
        assertFalse("Late chunk with old epoch must be invalid", VoiceInterruptionCoordinator.isChunkValid(initialEpoch))
        assertTrue("New chunk with current epoch must be valid", VoiceInterruptionCoordinator.isChunkValid(postEpoch))
    }

    @Test
    fun testDebouncePreventsDuplicateCallbacks() {
        VoiceInterruptionCoordinator.resetDebounce()
        val callbackCount = AtomicInteger(0)
        val firstTriggered = VoiceInterruptionCoordinator.requestBargeIn("call_1") {
            callbackCount.incrementAndGet()
        }
        assertTrue(firstTriggered)

        // Immediate duplicate request within debounce window should be blocked
        val secondTriggered = VoiceInterruptionCoordinator.requestBargeIn("call_2") {
            callbackCount.incrementAndGet()
        }
        assertFalse("Duplicate interruption within 350ms window must be debounced", secondTriggered)
        assertEquals(1, callbackCount.get())
    }
}
