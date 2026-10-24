package com.jarvis.assistant.audio

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceEchoGuardTest {

    private class FakeTimeProvider(var currentTime: Long = 1000L) : VoiceInterruptionCoordinator.TimeProvider {
        override fun currentTimeMillis(): Long = currentTime
        fun advanceTime(millis: Long) {
            currentTime += millis
        }
    }

    private lateinit var fakeClock: FakeTimeProvider

    @Before
    fun setUp() {
        fakeClock = FakeTimeProvider(10_000L)
        VoiceInterruptionCoordinator.timeProvider = fakeClock
        VoiceInterruptionCoordinator.playbackBufferCount.set(0)
        VoiceInterruptionCoordinator.lastPlaybackFinishTime.set(0L)
    }

    @After
    fun tearDown() {
        VoiceInterruptionCoordinator.timeProvider = VoiceInterruptionCoordinator.SystemTimeProvider
        VoiceInterruptionCoordinator.playbackBufferCount.set(0)
        VoiceInterruptionCoordinator.lastPlaybackFinishTime.set(0L)
    }

    @Test
    fun `test mic is muted during active audio playback`() {
        assertFalse("Initially should not be muted", VoiceInterruptionCoordinator.isEchoGuardMuted())

        VoiceInterruptionCoordinator.onPlaybackBufferEnqueued()
        assertTrue("Muted when playback buffer enqueued", VoiceInterruptionCoordinator.isEchoGuardMuted())

        VoiceInterruptionCoordinator.onPlaybackBufferEnqueued()
        assertTrue("Still muted with multiple buffers", VoiceInterruptionCoordinator.isEchoGuardMuted())

        VoiceInterruptionCoordinator.onPlaybackBufferCompleted()
        assertTrue("Still muted with 1 buffer remaining", VoiceInterruptionCoordinator.isEchoGuardMuted())
    }

    @Test
    fun `test 450ms acoustic dissipation tail mutes mic after playback finishes`() {
        VoiceInterruptionCoordinator.onPlaybackBufferEnqueued()
        VoiceInterruptionCoordinator.onPlaybackBufferCompleted()

        // Counter is now 0, but time hasn't advanced: within 450ms tail
        assertTrue("Should be muted within 450ms tail", VoiceInterruptionCoordinator.isEchoGuardMuted())

        fakeClock.advanceTime(200L)
        assertTrue("Still muted at +200ms tail", VoiceInterruptionCoordinator.isEchoGuardMuted())

        fakeClock.advanceTime(249L) // total 449ms
        assertTrue("Still muted at +449ms tail", VoiceInterruptionCoordinator.isEchoGuardMuted())

        fakeClock.advanceTime(2L) // total 451ms
        assertFalse("Should unmute past 450ms tail", VoiceInterruptionCoordinator.isEchoGuardMuted())
    }

    @Test
    fun `test 60s watchdog force-unmutes stalled playback buffers`() {
        VoiceInterruptionCoordinator.onPlaybackBufferEnqueued()
        assertTrue(VoiceInterruptionCoordinator.isEchoGuardMuted())

        // Advance 59 seconds: still muted
        fakeClock.advanceTime(59_000L)
        assertTrue("Still muted under 60s watchdog threshold", VoiceInterruptionCoordinator.isEchoGuardMuted())

        // Advance past 60s watchdog timeout (total 61s)
        fakeClock.advanceTime(2_000L)
        assertFalse("Watchdog should force unmute when buffer stalls past 60s", VoiceInterruptionCoordinator.isEchoGuardMuted())
    }

    @Test
    fun `test onPlaybackStopped immediately clears playback count and sets tail`() {
        VoiceInterruptionCoordinator.onPlaybackBufferEnqueued()
        VoiceInterruptionCoordinator.onPlaybackBufferEnqueued()
        assertTrue(VoiceInterruptionCoordinator.isEchoGuardMuted())

        VoiceInterruptionCoordinator.onPlaybackStopped()
        // Within tail:
        assertTrue(VoiceInterruptionCoordinator.isEchoGuardMuted())

        fakeClock.advanceTime(500L)
        assertFalse(VoiceInterruptionCoordinator.isEchoGuardMuted())
    }
}
