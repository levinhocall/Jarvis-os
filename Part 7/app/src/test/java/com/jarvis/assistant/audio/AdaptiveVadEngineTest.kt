package com.jarvis.assistant.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdaptiveVadEngineTest {

    private lateinit var vad: AdaptiveVadEngine

    @Before
    fun setUp() {
        vad = AdaptiveVadEngine()
    }

    @Test
    fun testInitialQuietFramesAdaptNoiseFloor() {
        val quietFreq = AudioFrequencyData(amplitude = 0.02f)
        var decision = vad.processFrame(0.02f, quietFreq, now = 1000L)

        assertFalse("Quiet frame should not be voiced", decision.isVoiced)
        assertFalse("Speech should not be active on quiet frame", decision.isSpeechActive)
        assertTrue("Noise floor should be tracked", decision.ambientNoiseFloor > 0f)
    }

    @Test
    fun testSpeechOnsetDetectionRequiresConsecutiveFrames() {
        val speechFreq = AudioFrequencyData(amplitude = 0.18f, pitchHz = 220f, mid = 0.15f)

        // Frame 1
        var d1 = vad.processFrame(0.18f, speechFreq, now = 1000L)
        assertTrue(d1.isVoiced)
        assertFalse("Speech start should not trigger on single frame", d1.isSpeechActive)

        // Frame 2
        var d2 = vad.processFrame(0.18f, speechFreq, now = 1020L)
        assertTrue(d2.isVoiced)
        assertFalse(d2.isSpeechActive)

        // Frame 3 (VAD_START_FRAMES = 3)
        var d3 = vad.processFrame(0.18f, speechFreq, now = 1040L)
        assertTrue(d3.isVoiced)
        assertTrue("Speech should be active after 3 consecutive voiced frames", d3.isSpeechActive)
        assertTrue("Speech start flag should be true", d3.isSpeechStart)
    }

    @Test
    fun testAdaptiveSilenceTimeoutScalesWithUtteranceLength() {
        val speechFreq = AudioFrequencyData(amplitude = 0.20f, pitchHz = 180f, mid = 0.16f)

        // Short command (10 voiced frames)
        for (i in 0 until 10) {
            vad.processFrame(0.20f, speechFreq, now = 1000L + (i * 20L))
        }
        val shortTimeout = vad.computeAdaptiveSilenceTimeout()

        // Longer utterance (30 voiced frames)
        for (i in 10 until 30) {
            vad.processFrame(0.20f, speechFreq, now = 1000L + (i * 20L))
        }
        val normalTimeout = vad.computeAdaptiveSilenceTimeout()

        assertTrue("Longer utterance should have equal or larger silence timeout", normalTimeout >= shortTimeout)
        assertTrue("Short timeout should be fast (< 250ms)", shortTimeout <= 250L)
    }

    @Test
    fun testTurnCompletionTriggeredWhenSilenceExceedsAdaptiveTimeout() {
        val speechFreq = AudioFrequencyData(amplitude = 0.22f, pitchHz = 190f, mid = 0.18f)
        val quietFreq = AudioFrequencyData(amplitude = 0.01f)

        // Speak for 12 frames (~240ms)
        for (i in 0 until 12) {
            vad.processFrame(0.22f, speechFreq, now = 1000L + (i * 20L))
        }
        assertTrue("Speech should be active", vad.isSpeaking())

        // Silence for 260ms (exceeding short adaptive timeout ~230ms)
        val endSpeechTime = 1000L + (12 * 20L)
        var turnCompletedObserved = false
        for (i in 1..15) {
            val now = endSpeechTime + (i * 20L)
            val decision = vad.processFrame(0.01f, quietFreq, now = now)
            if (decision.isTurnComplete) {
                turnCompletedObserved = true
            }
        }

        assertTrue("Turn completion should trigger after silence timeout", turnCompletedObserved)
        assertFalse("Speech should no longer be active", vad.isSpeaking())
    }

    @Test
    fun testResetClearsUtteranceState() {
        val speechFreq = AudioFrequencyData(amplitude = 0.25f, pitchHz = 200f)
        for (i in 0 until 5) {
            vad.processFrame(0.25f, speechFreq, now = 1000L + (i * 20L))
        }
        assertTrue(vad.isSpeaking())

        vad.reset()
        assertFalse("State should be inactive after reset", vad.isSpeaking())
        assertEquals(0, vad.getTotalVoicedFrames())
    }
}
