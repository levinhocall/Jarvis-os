package com.jarvis.assistant.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PcmPlaybackQueueTest {
    @Test fun byteAndFrameCapsRejectOverflowBeforeCopying() {
        val q = PcmPlaybackQueue(32, 2)
        assertEquals(PcmPlaybackQueue.Offer.ACCEPTED, q.offer(ByteArray(16), 0))
        assertEquals(PcmPlaybackQueue.Offer.ACCEPTED, q.offer(ByteArray(16), 0))
        assertEquals(PcmPlaybackQueue.Offer.OVERFLOW, q.offer(ByteArray(16), 0))
        assertEquals(32, q.queuedBytes); assertEquals(2, q.size)
        assertNotNull(q.poll(0, TimeUnit.MILLISECONDS)); assertEquals(16, q.queuedBytes)
        assertEquals(PcmPlaybackQueue.Offer.OVERFLOW, q.offer(ByteArray(1000), 0))
    }
    @Test fun producerBufferIsOwnedAndAligned() {
        val q = PcmPlaybackQueue(); val bytes = byteArrayOf(1,2,3,4,5)
        q.offer(bytes, 0); bytes.fill(0)
        assertArrayEquals(byteArrayOf(1,2,3,4), q.poll(0,TimeUnit.MILLISECONDS)!!.bytes)
    }
    @Test fun flushInvalidatesAlreadyDequeuedFrameEvenWithSameEpoch() {
        val q = PcmPlaybackQueue(); q.offer(ByteArray(16),0)
        val old = q.poll(0,TimeUnit.MILLISECONDS)!!
        q.advance(0)
        assertFalse(q.isCurrent(old)); assertEquals(0, q.queuedBytes)
        q.offer(ByteArray(16),0)
        assertTrue(q.isCurrent(q.poll(0,TimeUnit.MILLISECONDS)!!))
    }
    @Test fun lateOldEpochAndReleasedFramesAreRejected() {
        val q = PcmPlaybackQueue(); q.advance(2)
        assertEquals(PcmPlaybackQueue.Offer.STALE, q.offer(ByteArray(16),1))
        q.offer(ByteArray(16),2); val old = q.poll(0,TimeUnit.MILLISECONDS)!!
        q.close(); q.close()
        assertFalse(q.isCurrent(old))
        assertEquals(PcmPlaybackQueue.Offer.CLOSED, q.offer(ByteArray(16),2))
        assertNull(q.poll(10,TimeUnit.MILLISECONDS))
    }
    @Test fun burstOfTenThousandFramesStaysBounded() {
        val q = PcmPlaybackQueue(4096,32)
        var accepted = 0
        repeat(10_000) { if(q.offer(ByteArray(256),0)==PcmPlaybackQueue.Offer.ACCEPTED) accepted++ }
        assertEquals(16,accepted); assertEquals(4096,q.queuedBytes)
        q.advance(1); assertEquals(0,q.queuedBytes)
    }
}
