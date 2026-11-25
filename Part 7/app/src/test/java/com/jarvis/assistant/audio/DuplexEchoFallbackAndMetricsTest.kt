package com.jarvis.assistant.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplexEchoFallbackAndMetricsTest {

    private class FakeAecBackend : EchoBackend {
        var renders = 0
        var captures = 0
        var lastDelayMs = -1

        override fun render24k(samples: ShortArray, delayMs: Int) {
            renders++
            lastDelayMs = delayMs
        }

        override fun capture16k(input: ShortArray, output: ShortArray) {
            captures++
            input.copyInto(output)
        }

        override fun reset() {}

        override fun metrics(): AecMetrics = AecMetrics(
            echoReturnLossDb = 9.5,
            echoReturnLossEnhancementDb = 16.2,
            delayMs = 42,
            activeProcessing = true,
            renderFrames = renders.toLong(),
            captureFrames = captures.toLong(),
            saturatedCaptureFrames = 0L
        )

        override fun close() {}
    }

    @Test
    fun nativeInitFailureGracefullyFallsBackWithoutBlockingCaptureWhenFallbackAllowed() {
        val session = DuplexEchoSession(
            enabled = true,
            factory = { throw UnsatisfiedLinkError("libjarvis_aec3.so unavailable on JVM host") },
            speechFactory = null,
            allowAecFallback = true
        )
        assertTrue(session.initialize())
        assertFalse(session.isAecActive)
        assertNotNull(session.fallbackReason)
        assertNull(session.echoMetrics())

        // Capture must pass PCM through rather than muting the microphone when fallback is active.
        val raw = ByteArray(640) { (it and 0x7F).toByte() }
        val processed = session.capture(raw)
        assertArrayEquals(raw, processed)
        session.close()
    }

    @Test
    fun speechGateInitFailureDoesNotCrashFallbackSession() {
        val backend = FakeAecBackend()
        val session = DuplexEchoSession(
            enabled = true,
            factory = { backend },
            speechFactory = { throw IllegalStateException("Test Silero load error") },
            allowAecFallback = true
        )
        assertTrue(session.initialize())
        assertTrue(session.isAecActive)
        assertFalse(session.hasSpeechDetector)
        assertEquals(0f, session.speechProbability, 0f)

        val pcm = ByteArray(640) { 0x12 }
        assertEquals(640, session.capture(pcm).size)
        assertEquals(2, backend.captures)
        session.close()
    }

    @Test
    fun activeSessionExposesAecMetricsAndRenderDelays() {
        val backend = FakeAecBackend()
        val session = DuplexEchoSession(
            enabled = true,
            factory = { backend },
            allowAecFallback = true
        )
        assertTrue(session.initialize())
        assertTrue(session.isAecActive)
        session.render(ByteArray(960), 0, 960, 68, 0L)
        session.capture(ByteArray(640))

        val metrics = session.echoMetrics()
        assertNotNull(metrics)
        assertTrue(metrics!!.activeProcessing)
        assertEquals(16.2, metrics.echoReturnLossEnhancementDb, 0.01)
        assertEquals(68, backend.lastDelayMs)
        assertEquals(2, metrics.renderFrames)
        assertEquals(2, metrics.captureFrames)
        session.close()
    }
}
