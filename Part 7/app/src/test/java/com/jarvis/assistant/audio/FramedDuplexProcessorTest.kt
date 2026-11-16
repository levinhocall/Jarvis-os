package com.jarvis.assistant.audio

import org.junit.Assert.*
import org.junit.Test

class FramedDuplexProcessorTest {
    private class Backend : EchoBackend {
        val renders=mutableListOf<ShortArray>()
        var captures=0;var resets=0;var closes=0
        override fun render24k(samples: ShortArray, delayMs: Int) { renders+=samples.copyOf() }
        override fun capture16k(input: ShortArray, output: ShortArray) { captures++;input.copyInto(output) }
        override fun reset() { resets++ }
        override fun close() { closes++ }
    }
    @Test fun partialOddWritesAndCapturesRetainEverySample() {
        val backend=Backend();val processor=FramedDuplexProcessor(backend)
        val bytes=ByteArray(960) { it.toByte() }
        processor.renderAccepted(bytes,0,137,80);processor.renderAccepted(bytes,137,823,80)
        assertEquals(2,backend.renders.size)
        assertEquals(((bytes[0].toInt() and 255) or (bytes[1].toInt() shl 8)).toShort(),backend.renders[0][0])
        val a=processor.capture(bytes.copyOfRange(0,137))
        val b=processor.capture(bytes.copyOfRange(137,960))
        assertArrayEquals(bytes,a+b);assertEquals(3,backend.captures)
    }
    @Test fun resetDropsPartialFramesAndCloseRejectsStaleAudio() {
        val backend=Backend();val processor=FramedDuplexProcessor(backend)
        processor.capture(ByteArray(100));processor.requestReset()
        assertEquals(0,backend.resets)
        assertEquals(320,processor.capture(ByteArray(320)).size);assertEquals(1,backend.resets)
        processor.close();processor.close()
        assertEquals(0,processor.capture(ByteArray(320)).size);assertEquals(1,backend.closes)
    }
    @Test fun disabledAndRetiredSessionsNeverAllocateNativeEngine() {
        var created=0
        val session=DuplexEchoSession(false,factory={ created++;Backend() })
        assertTrue(session.initialize());assertArrayEquals(byteArrayOf(1,2),session.capture(byteArrayOf(1,2)))
        session.close();assertFalse(session.initialize());assertEquals(0,created)
    }
    @Test fun oldPlaybackEpochCannotReachReferenceAfterFlush() {
        val backend=Backend();val session=DuplexEchoSession(true,factory={backend})
        session.initialize();session.reset(2)
        session.render(ByteArray(480),0,480,30,1)
        assertEquals(0,backend.renders.size)
        session.render(ByteArray(480),0,480,30,2)
        assertEquals(1,backend.renders.size)
        session.close()
    }

}
