package com.jarvis.assistant.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class PcmAudioConverterTest {

    private fun shortsToLeBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putShort(it) }
        return out
    }

    private fun leBytesToShorts(bytes: ByteArray): ShortArray {
        val count = bytes.size / 2
        val buf = ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(count) { buf.short }
    }

    private fun sineSamples(sampleRate: Int, freqHz: Double, numSamples: Int, amp: Double = 12000.0): ShortArray {
        return ShortArray(numSamples) { i ->
            (amp * sin(2.0 * PI * freqHz * i / sampleRate)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun goertzelMagnitude(samples: ShortArray, sampleRate: Int, freqHz: Double): Double {
        val k = (0.5 + (samples.size * freqHz) / sampleRate).toInt()
        val omega = (2.0 * PI * k) / samples.size
        val coeff = 2.0 * cos(omega)
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        for (s in samples) {
            q0 = coeff * q1 - q2 + s.toDouble()
            q2 = q1
            q1 = q0
        }
        return sqrt(q1 * q1 + q2 * q2 - q1 * q2 * coeff)
    }

    private fun buildWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        audioFormat: Int = 1,
        bitsPerSample: Int = 16,
        extraMetadataChunkId: String? = null,
        extraMetadataPayload: ByteArray = ByteArray(0)
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        fun writeLeInt(v: Int) {
            baos.write(v and 0xFF)
            baos.write((v ushr 8) and 0xFF)
            baos.write((v ushr 16) and 0xFF)
            baos.write((v ushr 24) and 0xFF)
        }
        fun writeLeShort(v: Int) {
            baos.write(v and 0xFF)
            baos.write((v ushr 8) and 0xFF)
        }

        val fmtSize = 16
        val extraChunkTotal = if (extraMetadataChunkId != null) {
            8 + extraMetadataPayload.size + (extraMetadataPayload.size and 1)
        } else 0
        val riffSize = 4 + (8 + fmtSize) + extraChunkTotal + (8 + pcmData.size)

        baos.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLeInt(riffSize)
        baos.write("WAVE".toByteArray(Charsets.US_ASCII))

        baos.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeLeInt(fmtSize)
        writeLeShort(audioFormat)
        writeLeShort(channels)
        writeLeInt(sampleRate)
        writeLeInt(sampleRate * channels * (bitsPerSample / 8))
        writeLeShort(channels * (bitsPerSample / 8))
        writeLeShort(bitsPerSample)

        if (extraMetadataChunkId != null) {
            baos.write(extraMetadataChunkId.toByteArray(Charsets.US_ASCII))
            writeLeInt(extraMetadataPayload.size)
            baos.write(extraMetadataPayload)
            if ((extraMetadataPayload.size and 1) == 1) baos.write(0)
        }

        baos.write("data".toByteArray(Charsets.US_ASCII))
        writeLeInt(pcmData.size)
        baos.write(pcmData)
        return baos.toByteArray()
    }

    @Test
    fun downmixToMonoPreservesMonoAndAveragesStereo() {
        val monoBytes = shortsToLeBytes(shortArrayOf(1000, -2000, 3000))
        assertArrayEquals(monoBytes, PcmAudioConverter.downmixToMonoPcm16(monoBytes, 1))

        val stereo = shortsToLeBytes(shortArrayOf(2000, 4000, -3000, 1000))
        val downmixed = leBytesToShorts(PcmAudioConverter.downmixToMonoPcm16(stereo, 2))
        assertArrayEquals(shortArrayOf(3000, -1000), downmixed)
    }

    @Test
    fun resamplePreserves24kTo16kExactFrameLengthAndToneFrequency() {
        // 20 ms at 24 kHz = 480 samples -> 320 samples (640 bytes) at 16 kHz
        val pcm24k = shortsToLeBytes(sineSamples(24_000, 500.0, 480))
        val pcm16k = PcmAudioConverter.resample24kTo16k(pcm24k)
        assertEquals(320 * 2, pcm16k.size)

        val outShorts = leBytesToShorts(pcm16k)
        val magTarget = goertzelMagnitude(outShorts, 16_000, 500.0)
        val magOff = goertzelMagnitude(outShorts, 16_000, 2000.0)
        assertTrue("Expected dominant 500 Hz component after 24k->16k downsample", magTarget > magOff * 5.0)
    }

    @Test
    fun resample16kTo24kAnd48kTo24kPreservePitch() {
        val tone16k = shortsToLeBytes(sineSamples(16_000, 440.0, 320))
        val up24k = leBytesToShorts(PcmAudioConverter.to24kMonoPcm16(tone16k, 16_000, 1))
        assertEquals(480, up24k.size)
        assertTrue(goertzelMagnitude(up24k, 24_000, 440.0) > goertzelMagnitude(up24k, 24_000, 1500.0) * 5.0)

        val tone48k = shortsToLeBytes(sineSamples(48_000, 1000.0, 960))
        val down24k = leBytesToShorts(PcmAudioConverter.to24kMonoPcm16(tone48k, 48_000, 1))
        assertEquals(480, down24k.size)
        assertTrue(goertzelMagnitude(down24k, 24_000, 1000.0) > goertzelMagnitude(down24k, 24_000, 3500.0) * 5.0)
    }

    @Test
    fun decodeWavHandlesExtraOddSizedChunksAndStereo44100Hz() {
        val rawSamples = sineSamples(44_100, 660.0, 441 * 2) // 10ms stereo = 441 frames * 2 ch
        val wav = buildWav(
            pcmData = shortsToLeBytes(rawSamples),
            sampleRate = 44_100,
            channels = 2,
            extraMetadataChunkId = "JUNK",
            extraMetadataPayload = byteArrayOf(1, 2, 3) // odd size -> tests RIFF word alignment padding
        )
        val decoded = PcmAudioConverter.decodeWavToMono24kPcm16(wav)
        assertNotNull(decoded)
        val outShorts = leBytesToShorts(decoded!!)
        assertTrue("Expected ~240 samples for 10ms at 24 kHz, got ${outShorts.size}", abs(outShorts.size - 240) <= 2)
    }

    @Test
    fun decodeWavRejectsNonPcmOrCorruptedPayloads() {
        val pcm = shortsToLeBytes(sineSamples(16_000, 440.0, 160))
        assertNull(PcmAudioConverter.decodeWavToMono24kPcm16(buildWav(pcm, 16_000, 1, audioFormat = 3))) // IEEE float
        assertNull(PcmAudioConverter.decodeWavToMono24kPcm16(buildWav(pcm, 16_000, 1, bitsPerSample = 8)))
        assertNull(PcmAudioConverter.decodeWavToMono24kPcm16(byteArrayOf(0, 1, 2, 3)))
        assertNull(PcmAudioConverter.decodeWavToMono24kPcm16(buildWav(ByteArray(0), 16_000, 1)))
    }
}
