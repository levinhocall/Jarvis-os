package com.jarvis.assistant.audio.wake

import com.jarvis.assistant.audio.DuplexEchoSession
import com.jarvis.assistant.audio.EchoBackend
import com.jarvis.assistant.audio.PcmSpeechDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SileroVadStateMachineTest {

    @Test
    fun canonicalFrameSizeIs512Samples32MsAt16kHz() {
        assertEquals(512, SileroVadStateMachine.FRAME_SAMPLES)
        assertEquals(16_000, SileroVadStateMachine.SAMPLE_RATE_HZ)
    }

    @Test
    fun onsetHysteresisAndHangoverHoldDuringBriefPausesAndExitCleanly() {
        val vad = SileroVadStateMachine()
        var samples = 0L

        // Single mild frame does not immediately confirm speech.
        val mild = vad.recordFrameProbability(0.50f, 512L.also { samples = it }, playbackActive = false)
        assertFalse(mild.isSpeechActive)
        assertFalse(mild.speechStarted)

        // Second consecutive frame confirms onset cleanly.
        val started = vad.recordFrameProbability(0.52f, 1024L.also { samples = it }, playbackActive = false)
        assertTrue(started.isSpeechActive)
        assertTrue(started.speechStarted)

        // Brief dip below exitThreshold (0.20f) stays active across the 7-frame (224ms) hangover window.
        for (i in 1..SileroVadStateMachine.HANGOVER_FRAMES) {
            samples += 512L
            val dipped = vad.recordFrameProbability(0.15f, samples, playbackActive = false)
            assertTrue("Expected hangover frame $i to remain speechActive", dipped.isSpeechActive)
            assertFalse(dipped.speechEnded)
        }

        // After hangover expires, speech ends cleanly.
        samples += 512L
        val ended = vad.recordFrameProbability(0.15f, samples, playbackActive = false)
        assertFalse(ended.isSpeechActive)
        assertTrue(ended.speechEnded)
    }

    @Test
    fun strongInstantOnsetBreaksThroughWithoutTwoFrameDelay() {
        val vad = SileroVadStateMachine()
        val first = vad.recordFrameProbability(0.86f, 512L, playbackActive = true)
        assertTrue(first.isSpeechActive)
        assertTrue(first.speechStarted)
    }

    @Test
    fun playbackActiveRaisesEntryThresholdAgainstResidualSpeakerLeakage() {
        val vad = SileroVadStateMachine()

        // Moderate 0.52 probability enters when quiet, but is rejected when playback is active.
        val d1 = vad.recordFrameProbability(0.52f, 512L, playbackActive = true)
        val d2 = vad.recordFrameProbability(0.54f, 1024L, playbackActive = true)
        assertFalse("Expected playback-aware threshold >= 0.62 to block 0.54 probability", d2.isSpeechActive)
        assertTrue(d1.enterThreshold >= 0.62f)

        // Real user barge-in speech (>= 0.68) confirms cleanly during playback.
        val d3 = vad.recordFrameProbability(0.68f, 1536L, playbackActive = true)
        val d4 = vad.recordFrameProbability(0.71f, 2048L, playbackActive = true)
        assertTrue(d4.isSpeechActive)
        assertTrue(d4.speechStarted)
    }

    @Test
    fun noisyRoomRaisesAdaptiveNoiseFloorAndResetRestoresDefaults() {
        val vad = SileroVadStateMachine()
        var samples = 0L
        repeat(40) {
            samples += 512L
            vad.recordFrameProbability(0.24f, samples, playbackActive = false)
        }
        val noisy = vad.recordFrameProbability(0.24f, samples + 512L, playbackActive = false)
        assertTrue("Noise floor should climb toward 0.24 (was ${noisy.noiseFloorProbability})", noisy.noiseFloorProbability > 0.18f)
        assertTrue("Adaptive enterThreshold should scale above default 0.45 (was ${noisy.enterThreshold})", noisy.enterThreshold > 0.46f)

        vad.reset()
        assertFalse(vad.isCurrentlyActive())
        assertFalse(vad.isSpeechRecent(samples + 512L))
        val clean = vad.recordFrameProbability(0.10f, 512L, playbackActive = false)
        assertEquals(0.45f, clean.enterThreshold, 0.02f)
    }

    @Test
    fun duplexSessionRoutesPlaybackStateAndResetToSpeechDetector() {
        var sawPlaybackTrue = false
        var resets = 0
        var activeFlag = false
        val fakeSpeech = object : PcmSpeechDetector {
            override fun accept(pcm: ByteArray): Float = accept(pcm, false)
            override fun accept(pcm: ByteArray, playbackActive: Boolean): Float {
                if (playbackActive) sawPlaybackTrue = true
                return if (playbackActive) 0.72f else 0.1f
            }
            override fun isSpeechActive(): Boolean = activeFlag
            override fun reset() { resets++; activeFlag = false }
            override fun close() {}
        }
        val fakeAec = object : EchoBackend {
            override fun render24k(samples: ShortArray, delayMs: Int) {}
            override fun capture16k(input: ShortArray, output: ShortArray) { input.copyInto(output) }
            override fun reset() {}
            override fun close() {}
        }

        val session = DuplexEchoSession(enabled = true, factory = { fakeAec }, speechFactory = { fakeSpeech })
        assertTrue(session.initialize())
        session.isPlaybackActiveForVad = true
        activeFlag = true
        session.capture(ByteArray(320))
        assertTrue(sawPlaybackTrue)
        assertTrue(session.isSpeechConfirmed)
        assertEquals(0.72f, session.speechProbability, 0.001f)

        session.resetSpeechDetector()
        assertEquals(1, resets)
        assertFalse(session.isSpeechConfirmed)
        assertEquals(0f, session.speechProbability, 0f)
        session.close()
    }
}
