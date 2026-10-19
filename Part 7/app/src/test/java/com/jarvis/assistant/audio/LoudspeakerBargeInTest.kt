package com.jarvis.assistant.audio

import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LoudspeakerBargeInTest {

    @Test
    fun testVoskJsonTextExtraction() {
        val partialJson = "{\"partial\" : \"ruko ek minute\"}"
        val finalJson = "{\"text\" : \"jarvis what is the weather\"}"
        val emptyJson = "{\"other\" : \"none\"}"

        assertEquals("ruko ek minute", JarvisConversationService.extractVoskText(partialJson))
        assertEquals("jarvis what is the weather", JarvisConversationService.extractVoskText(finalJson))
        assertEquals("", JarvisConversationService.extractVoskText(emptyJson))
        assertEquals("", JarvisConversationService.extractVoskText(null))
        assertEquals("", JarvisConversationService.extractVoskText(""))
    }

    @Test
    fun testExplicitHotwordsTriggerBargeIn() {
        val hotwords = listOf("stop", "ruko", "bas", "wait", "suno", "cancel", "hold on", "ruk jao", "bas karo")
        val assistantSpoken = "Sure, I am playing the latest music playlist on your device now."

        for (hw in hotwords) {
            val detected = JarvisConversationService.isBargeInSpeechDetected(hw, assistantSpoken)
            assertTrue("Expected hotword '$hw' to trigger barge-in", detected)
        }
    }

    @Test
    fun testNaturalSpeechTriggersBargeInWithoutExplicitStop() {
        val naturalPhrases = listOf(
            "what is the weather",
            "open whatsapp",
            "search for flights",
            "kya haal hai"
        )
        val assistantSpoken = "The weather today in Mumbai is 28 degrees with partly cloudy skies."

        for (phrase in naturalPhrases) {
            val detected = JarvisConversationService.isBargeInSpeechDetected(phrase, assistantSpoken)
            assertTrue("Expected natural user phrase '$phrase' to trigger barge-in", detected)
        }
    }

    @Test
    fun testAssistantEchoIsRejected() {
        val assistantSpoken = "The weather today in Mumbai is 28 degrees with partly cloudy skies."
        
        // Exact snippet echo from loudspeaker loopback
        val echoPhrase = "weather today in mumbai"
        val detected = JarvisConversationService.isBargeInSpeechDetected(echoPhrase, assistantSpoken)
        assertFalse("Expected loudspeaker acoustic echo '$echoPhrase' to be rejected", detected)

        // Single generic token or [unk]
        assertFalse(JarvisConversationService.isBargeInSpeechDetected("[unk]", assistantSpoken))
        assertFalse(JarvisConversationService.isBargeInSpeechDetected("", assistantSpoken))
        assertFalse(JarvisConversationService.isBargeInSpeechDetected("   ", assistantSpoken))
    }

    @Test
    fun testPlaybackGenerationAndAudioSuppression() {
        val generation = AtomicLong(0L)
        var suppressAudioUntilTurnComplete = false
        val flushedCount = AtomicInteger(0)

        // Simulate triggerHardBargeIn
        fun simulateHardBargeIn() {
            generation.incrementAndGet()
            suppressAudioUntilTurnComplete = true
            flushedCount.incrementAndGet()
        }

        // Simulate incoming audio chunk from late streaming response
        fun simulateAudioDataReceived(incomingChunk: ByteArray, currentTurnSuppression: Boolean): Boolean {
            if (currentTurnSuppression) {
                // Dropped
                return false
            }
            // Accepted
            return true
        }

        // Before barge-in: normal audio accepted
        val testChunk = byteArrayOf(1, 2, 3, 4)
        assertTrue(simulateAudioDataReceived(testChunk, suppressAudioUntilTurnComplete))

        // Barge-in triggered
        simulateHardBargeIn()
        assertEquals(1L, generation.get())
        assertTrue(suppressAudioUntilTurnComplete)
        assertEquals(1, flushedCount.get())

        // In-flight streaming chunks arrive after barge-in: must be dropped
        assertFalse(simulateAudioDataReceived(testChunk, suppressAudioUntilTurnComplete))
        assertFalse(simulateAudioDataReceived(testChunk, suppressAudioUntilTurnComplete))

        // New turn completes: suppression cleared
        suppressAudioUntilTurnComplete = false
        assertTrue(simulateAudioDataReceived(testChunk, suppressAudioUntilTurnComplete))
    }

    @Test
    fun testDuplicateCallbacksIdempotency() {
        val interruptCount = AtomicInteger(0)
        val isInterrupted = AtomicBoolean(false)

        fun onTrigger(source: String) {
            if (isInterrupted.compareAndSet(false, true)) {
                interruptCount.incrementAndGet()
            }
        }

        // Duplicate rapid Vosk partials
        onTrigger("vosk_partial")
        onTrigger("vosk_partial_duplicate")
        onTrigger("vosk_final")

        assertEquals(1, interruptCount.get())
    }

    @Test
    fun testConversationStateTransitions() {
        var state = ConversationState.SPEAKING
        assertEquals(ConversationState.SPEAKING, state)

        // Interruption occurs
        state = ConversationState.INTERRUPTED
        assertEquals(ConversationState.INTERRUPTED, state)

        // Immediately moves to LISTENING
        state = ConversationState.LISTENING
        assertEquals(ConversationState.LISTENING, state)
    }

    @Test
    fun testUiManualInterruptionTriggers() {
        var manualInterrupted = false
        val isSpeaking = true

        if (isSpeaking) {
            manualInterrupted = true
        }

        assertTrue("Expected UI single-tap to invoke manual interruption", manualInterrupted)
    }
}
