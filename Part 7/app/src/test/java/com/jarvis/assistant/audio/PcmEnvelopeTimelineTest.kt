package com.jarvis.assistant.audio

import org.junit.Assert.*
import org.junit.Test

class PcmEnvelopeTimelineTest {
    private fun pcm(frames: Int, value: Int) = ByteArray(frames * 2) { if(it % 2 == 0) value.toByte() else (value shr 8).toByte() }
    @Test fun queuedIsNotAudibleAndPartialWritesHaveExactFramePositions() {
        val timeline = PcmEnvelopeTimeline(blockFrames = 10)
        val data = pcm(100, 16384)
        assertNull(timeline.levelAt(1, 0))
        timeline.accepted(data, 0, 20, 0)
        timeline.accepted(data, 20, 30, 0)
        assertEquals(25L, timeline.writtenFrames)
        assertNull(timeline.levelAt(0, 0))
        assertEquals(1f, timeline.levelAt(12, 0)!!, .001f)
        assertNull(timeline.levelAt(25, 0))
        assertNull(timeline.levelAt(100, 0))
    }
    @Test fun amplitudeUsesCurrentFrameNotNewestWrittenChunk() {
        val timeline = PcmEnvelopeTimeline(blockFrames = 10)
        timeline.accepted(pcm(10, 100), 0, 20, 0)
        timeline.accepted(pcm(10, 16000), 0, 20, 0)
        assertTrue(timeline.levelAt(5, 0)!! < .2f)
        assertTrue(timeline.levelAt(12, 0)!! > .9f)
    }
    @Test fun flushReleaseGenerationAndRouteChangeDiscardOldEnvelopes() {
        val t = PcmEnvelopeTimeline()
        val data = pcm(100, 20000)
        t.accepted(data, 0, data.size, 0)
        t.reset(3)
        assertFalse(t.accepted(data, 0, data.size, 0))
        assertNull(t.levelAt(10, 0))
        t.accepted(data, 0, data.size, 3)
        t.discardEnvelopes()
        assertEquals(100L, t.writtenFrames)
        assertNull(t.levelAt(20, 3))
        t.accepted(data, 0, data.size, 3)
        assertNull(t.levelAt(20, 3))
        assertNotNull(t.levelAt(150, 3))
    }
    @Test fun metadataAndMalformedWritesAreBounded() {
        val t = PcmEnvelopeTimeline(capacity = 4, blockFrames = 10)
        val data = pcm(100, 1000)
        assertFalse(t.accepted(data, 0, 3, 0))
        t.accepted(data, 0, data.size, 0)
        assertEquals(4, t.size)
        assertNull(t.levelAt(2, 0))
        assertNotNull(t.levelAt(90, 0))
    }
    @Test fun timestampValidationHeadWrapAndReset() {
        val clock = PlaybackFrameClock()
        assertEquals(4294967294L, clock.head(-2))
        assertEquals(4294967298L, clock.head(2))
        clock.reset();assertEquals(2L, clock.head(2))
        assertEquals(340L, clock.timestamp(100, 1_000_000_000, 1_010_000_000, 1000, 24000))
        assertNull(clock.timestamp(100, 1_000_000_000, 1_300_000_000, 1000, 24000))
        assertNull(clock.timestamp(100, 1_000_000_000, 999_000_000, 1000, 24000))
        assertNull(clock.timestamp(100, 1_000_000_000, 1_010_000_000, 150, 24000))
    }
}
