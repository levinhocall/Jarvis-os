package com.jarvis.assistant.audio

import org.junit.Assert.*
import org.junit.Test

class AudioFrequencyOverflowTest {
    @Test fun fullScaleAlternatingPcmDoesNotOverflowFrequencyAnalysis() {
        val bytes=ByteArray(2048)
        for(i in 0 until bytes.size/2) {
            val sample=if(i%2==0) 32767 else -32768
            bytes[i*2]=sample.toByte();bytes[i*2+1]=(sample shr 8).toByte()
        }
        val result=AudioUtils.analyzeFrequency(bytes,bytes.size)
        for(value in listOf(result.amplitude,result.pitchHz,result.bass,result.mid,result.high)) assertTrue(value.toString(),value.isFinite())
    }
}
