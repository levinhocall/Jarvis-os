package com.jarvis.assistant.audio.turn

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.abs

class WhisperTurnFeaturesTest {
    @Test fun silenceIsFiniteAndShortAudioIsLeftPadded() {
        val f=WhisperTurnFeatures()
        val features=f.extract(ByteArray(320))
        assertEquals(64000,features.size)
        assertTrue(features.all { it.isFinite() && abs(it+1.5f)<1e-5f })
    }
    @Test fun allCoefficientsMatchOfficialPipecatReference() {
        var seed=7
        val pcm=ByteArray(64000)
        for(i in 0 until 32000) {
            seed=1664525*seed+1013904223
            val value=(seed ushr 16)-32768
            pcm[i*2]=value.toByte();pcm[i*2+1]=(value shr 8).toByte()
        }
        val expected=GZIPInputStream(javaClass.getResourceAsStream("/audio/smart-turn-pipecat-reference.f32.gz")!!).use { it.readBytes() }
        val reference=ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val actual=WhisperTurnFeatures().extract(pcm)
        var error=0f
        for(i in actual.indices) error=maxOf(error,abs(actual[i]-reference.get(i)))
        assertTrue("Maximum feature error $error",error<.001f)
    }
    @Test fun lastEightSecondsAreUsedAndBuffersAreReused() {
        val extractor=WhisperTurnFeatures()
        val a=extractor.extract(ByteArray(256000))
        val expected=a.copyOf()
        val long=ByteArray(260000) { if(it<4000) 100 else 0 }
        val b=extractor.extract(long)
        assertSame(a,b);assertArrayEquals(expected,b,0f)
    }
}
