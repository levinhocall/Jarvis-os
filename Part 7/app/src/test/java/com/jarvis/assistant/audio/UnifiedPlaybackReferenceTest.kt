package com.jarvis.assistant.audio

import com.jarvis.assistant.background.VoicePresentationStore
import com.jarvis.assistant.data.model.AudioTimingConfidence
import com.jarvis.assistant.data.model.PlaybackSource
import com.jarvis.assistant.data.model.VoiceConnection
import com.jarvis.assistant.data.model.VoiceEnvelope
import com.jarvis.assistant.ui.orb.OrbPresentationMapper
import com.jarvis.assistant.ui.orb.PlasmaVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UnifiedPlaybackReferenceTest {

    @Test
    fun playbackQueueCarriesSourceAcrossSourcesAndClearsOnAdvance() {
        val queue = PcmPlaybackQueue(maxBytes = 64_000, maxFrames = 32)
        val ttsBytes = ByteArray(960) { 0x11 }
        val geminiBytes = ByteArray(960) { 0x22 }

        assertEquals(PcmPlaybackQueue.Offer.ACCEPTED, queue.offer(ttsBytes, 0L, PlaybackSource.ANDROID_TTS))
        assertEquals(PcmPlaybackQueue.Offer.ACCEPTED, queue.offer(geminiBytes, 0L, PlaybackSource.GEMINI_PCM))
        assertEquals(2, queue.size)

        val first = queue.poll(10, TimeUnit.MILLISECONDS)
        val second = queue.poll(10, TimeUnit.MILLISECONDS)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(PlaybackSource.ANDROID_TTS, first!!.source)
        assertEquals(PlaybackSource.GEMINI_PCM, second!!.source)

        queue.advance(1L)
        assertFalse(queue.isCurrent(first))
        assertEquals(PcmPlaybackQueue.Offer.STALE, queue.offer(ttsBytes, 0L, PlaybackSource.ANDROID_TTS))
        assertEquals(PcmPlaybackQueue.Offer.ACCEPTED, queue.offer(ttsBytes, 1L, PlaybackSource.ANDROID_TTS))
    }

    @Test
    fun audioPlayerEnqueuePcmRejectsInvalidBuffersAndSlicesUniformlyOnFlush() {
        val player = AudioPlayer(
            onPlaybackStateChanged = {},
            onPlaybackAmplitude = {}
        )
        try {
            // Below 16-byte floor is rejected without touching queue.
            assertFalse(player.enqueuePcm(ByteArray(8), sampleRateHz = 24_000, source = PlaybackSource.ANDROID_TTS))

            // 1 second of 16 kHz mono PCM16 (32,000 bytes) resamples to 24 kHz (48,000 bytes -> five 9600-byte slices).
            val oneSecond16k = ByteArray(32_000) { 0x2A }
            var drained = false
            assertTrue(
                player.enqueuePcm(
                    pcmData = oneSecond16k,
                    sampleRateHz = 16_000,
                    channels = 1,
                    source = PlaybackSource.ANDROID_TTS,
                    onDrained = { drained = true }
                )
            )
            assertTrue(player.isPlaying())

            // Barge-in flush clears pending queue immediately and resets state.
            player.flush()
            assertFalse(player.isPlaying())
            assertFalse(drained)
        } finally {
            player.release()
        }
    }

    @Test
    fun presentationStoreAndOrbMapperTreatUnifiedAndroidTtsAsExactReferenceOutput() {
        val store = VoicePresentationStore()
        val sessionId = store.begin(muted = false)
        store.update(sessionId) {
            it.copy(
                connection = VoiceConnection.LIVE,
                capturing = true,
                playing = true,
                playbackSource = PlaybackSource.ANDROID_TTS,
                hasPcmReference = true,
                timing = AudioTimingConfidence.PLAYBACK_HEAD,
                output = VoiceEnvelope(0.78f, 1000L, it.interruptionGeneration)
            )
        }

        val liveSnapshot = store.snapshots.value
        assertTrue(liveSnapshot.hasPcmReference)
        assertEquals(PlaybackSource.ANDROID_TTS, liveSnapshot.playbackSource)

        val orb = OrbPresentationMapper.map(liveSnapshot, 1020L)
        assertEquals(PlasmaVisualState.AI_SPEAKING, orb.state)
        assertEquals(0.78f, orb.output, 0.001f)

        // Direct fallback (without PCM reference) only yields state unless hardware/head clock is available.
        store.update(sessionId) {
            it.copy(hasPcmReference = false, timing = AudioTimingConfidence.STATE_ONLY)
        }
        val uncalibratedOrb = OrbPresentationMapper.map(store.snapshots.value, 1030L)
        assertEquals(PlasmaVisualState.AI_SPEAKING, uncalibratedOrb.state)
        assertEquals(0f, uncalibratedOrb.output, 0f)

        // Interruption clears hasPcmReference and output immediately.
        store.interrupt(sessionId)
        val interrupted = store.snapshots.value
        assertFalse(interrupted.hasPcmReference)
        assertFalse(interrupted.playing)
        assertEquals(PlasmaVisualState.INTERRUPTED, OrbPresentationMapper.map(interrupted, 1040L).state)
    }

    @Test
    fun bridgeTracksActiveSinkStateCleanly() {
        val fakeSink = object : AndroidTtsPcmBridge.PcmSink {
            var active = true
            override fun isActive(): Boolean = active
            override fun enqueueTtsPcm(
                pcm24kMono: ByteArray,
                source: PlaybackSource,
                onDrained: (() -> Unit)?
            ): Boolean = true
        }
        try {
            AndroidTtsPcmBridge.registerSink(fakeSink)
            assertTrue(AndroidTtsPcmBridge.hasActiveSink())

            fakeSink.active = false
            assertFalse(AndroidTtsPcmBridge.hasActiveSink())

            fakeSink.active = true
            assertTrue(AndroidTtsPcmBridge.hasActiveSink())

            AndroidTtsPcmBridge.unregisterSink(fakeSink)
            assertFalse(AndroidTtsPcmBridge.hasActiveSink())
        } finally {
            AndroidTtsPcmBridge.unregisterSink(fakeSink)
            AndroidTtsPcmBridge.cancelAll()
        }
    }
}
