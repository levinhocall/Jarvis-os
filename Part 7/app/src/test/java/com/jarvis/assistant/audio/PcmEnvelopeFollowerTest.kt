package com.jarvis.assistant.audio

import org.junit.Assert.*
import org.junit.Test

class PcmEnvelopeFollowerTest {
    private fun constant(sample: Int) = ByteArray(640) { if (it % 2 == 0) sample.toByte() else (sample shr 8).toByte() }
    @Test fun silenceNoiseClippingAndInvalidValuesAreBounded() {
        assertEquals(0f, PcmEnvelopeFollower.normalize(0f), 0f)
        assertEquals(0f, PcmEnvelopeFollower.normalize(.001f), 0f)
        assertEquals(0f, PcmEnvelopeFollower.normalize(Float.NaN), 0f)
        assertEquals(1f, PcmEnvelopeFollower.normalize(1f), .001f)
        assertEquals(1f, PcmEnvelopeFollower.rms(constant(-32768), 0, 640), 0f)
        assertEquals(.5f, PcmEnvelopeFollower.rms(constant(16384), 0, 640), .001f)
    }
    @Test fun attackReleaseMuteAndCaptureRestart() {
        val follower = PcmEnvelopeFollower()
        val loud = constant(16384); val silence = constant(0)
        follower.accept(loud, 0)
        val attack = follower.accept(loud, 20)
        assertTrue(attack > .6f && attack < 1f)
        val release = follower.accept(silence, 40)
        assertTrue(release > attack * .8f && release < attack)
        assertEquals(0f, follower.accept(loud, 60, false), 0f)
        assertTrue(follower.accept(loud, 1000) < attack)
        follower.reset(); assertEquals(0f, follower.level, 0f)
        repeat(100) { follower.accept(loud, it * 20L); follower.reset() }
        assertEquals(0f, follower.level, 0f)
    }
    @Test fun staleSamplesDecayInPresentationAndGapDoesNotRetainPeak() {
        val envelope = com.jarvis.assistant.data.model.VoiceEnvelope(1f, 1000, 2)
        assertEquals(.5f, envelope.fresh(1200, 2), .001f)
        assertEquals(0f, envelope.fresh(1300, 2), 0f)
        val follower = PcmEnvelopeFollower()
        repeat(10) { follower.accept(constant(30000), it * 20L) }
        assertEquals(0f, follower.accept(constant(0), 1000), 0f)
    }
}
