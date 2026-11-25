package com.jarvis.assistant.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure PCM16 / RIFF-WAV conversion utilities for the unified 24 kHz mono playback
 * pipeline and synchronized 16 kHz DSP render reference path.
 */
object PcmAudioConverter {

    private const val RIFF_HEADER = 0x46464952 // "RIFF" LE
    private const val WAVE_HEADER = 0x45564157 // "WAVE" LE
    private const val FMT_CHUNK = 0x20746d66   // "fmt " LE
    private const val DATA_CHUNK = 0x61746164  // "data" LE

    fun downmixToMonoPcm16(pcm: ByteArray, channels: Int): ByteArray {
        val safeChannels = channels.coerceAtLeast(1)
        val alignedBytes = pcm.size and 1.inv()
        if (safeChannels == 1) return pcm.copyOf(alignedBytes)
        val frameBytes = safeChannels * 2
        val frameCount = alignedBytes / frameBytes
        if (frameCount <= 0) return ByteArray(0)
        val out = ByteArray(frameCount * 2)
        val inBuf = ByteBuffer.wrap(pcm, 0, frameCount * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            var sum = 0L
            for (ch in 0 until safeChannels) {
                sum += inBuf.short.toLong()
            }
            val avg = (sum / safeChannels).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
            outBuf.putShort(avg.toShort())
        }
        return out
    }

    fun resampleMonoPcm16(pcmMono: ByteArray, srcRateHz: Int, dstRateHz: Int): ByteArray {
        require(srcRateHz > 0 && dstRateHz > 0) { "Sample rates must be positive ($srcRateHz -> $dstRateHz)" }
        val usableBytes = pcmMono.size and 1.inv()
        if (usableBytes < 2) return ByteArray(0)
        if (srcRateHz == dstRateHz) return pcmMono.copyOf(usableBytes)

        val srcCount = usableBytes / 2
        val inBuf = ByteBuffer.wrap(pcmMono, 0, usableBytes).order(ByteOrder.LITTLE_ENDIAN)
        val src = ShortArray(srcCount) { inBuf.short }

        // Apply light 3-tap anti-alias FIR when downsampling before linear interpolation.
        val filtered = if (srcRateHz > dstRateHz && srcCount >= 3) {
            ShortArray(srcCount).also { dst ->
                dst[0] = src[0]
                for (i in 1 until srcCount - 1) {
                    val v = (src[i - 1].toInt() + 2 * src[i].toInt() + src[i + 1].toInt()) / 4
                    dst[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                dst[srcCount - 1] = src[srcCount - 1]
            }
        } else {
            src
        }

        val dstCount = ((srcCount.toLong() * dstRateHz) / srcRateHz).toInt().coerceAtLeast(1)
        val out = ByteArray(dstCount * 2)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val step = srcRateHz.toDouble() / dstRateHz.toDouble()
        for (i in 0 until dstCount) {
            val pos = i * step
            val idx = pos.toInt().coerceIn(0, filtered.lastIndex)
            val next = (idx + 1).coerceAtMost(filtered.lastIndex)
            val frac = pos - idx
            val sample = filtered[idx] * (1.0 - frac) + filtered[next] * frac
            val clamped = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outBuf.putShort(clamped.toShort())
        }
        return out
    }

    fun to24kMonoPcm16(pcm: ByteArray, srcRateHz: Int, channels: Int = 1): ByteArray {
        val mono = downmixToMonoPcm16(pcm, channels)
        return resampleMonoPcm16(mono, srcRateHz, AudioPlayer.SAMPLE_RATE)
    }

    fun resample24kTo16k(pcm24kMono: ByteArray, offset: Int = 0, length: Int = pcm24kMono.size - offset): ByteArray {
        if (length <= 0 || offset < 0 || offset + length > pcm24kMono.size) return ByteArray(0)
        val slice = pcm24kMono.copyOfRange(offset, offset + length)
        return resampleMonoPcm16(slice, AudioPlayer.SAMPLE_RATE, 16_000)
    }

    /**
     * Parses a standard PCM16 RIFF/WAVE payload (handling arbitrary metadata chunks before `data`)
     * and converts it to 24 kHz mono PCM16 bytes ready for [AudioPlayer]. Returns null if invalid or non-PCM16.
     */
    fun decodeWavToMono24kPcm16(wavBytes: ByteArray): ByteArray? {
        if (wavBytes.size < 44) return null
        val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != RIFF_HEADER || buf.getInt(8) != WAVE_HEADER) return null

        var cursor = 12
        var audioFormat = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataLength = 0

        while (cursor + 8 <= wavBytes.size) {
            val chunkId = buf.getInt(cursor)
            val chunkSizeLong = buf.getInt(cursor + 4).toLong() and 0xFFFFFFFFL
            val payloadStart = cursor + 8
            val available = (wavBytes.size - payloadStart).coerceAtLeast(0).toLong()
            val clampedSize = minOf(chunkSizeLong, available).toInt()

            if (chunkId == FMT_CHUNK && clampedSize >= 16) {
                audioFormat = buf.getShort(payloadStart).toInt() and 0xFFFF
                channels = buf.getShort(payloadStart + 2).toInt() and 0xFFFF
                sampleRate = buf.getInt(payloadStart + 4)
                bitsPerSample = buf.getShort(payloadStart + 14).toInt() and 0xFFFF
            } else if (chunkId == DATA_CHUNK) {
                dataOffset = payloadStart
                dataLength = clampedSize and 1.inv()
                break
            }

            val step = 8L + chunkSizeLong + (chunkSizeLong and 1L)
            if (step <= 0L || cursor + step > wavBytes.size) break
            cursor = (cursor + step).toInt()
        }

        if (audioFormat != 1 || bitsPerSample != 16 || channels !in 1..8 || sampleRate !in 8000..96000) {
            return null
        }
        if (dataOffset < 0 || dataLength < 2) return null

        val rawPcm = wavBytes.copyOfRange(dataOffset, dataOffset + dataLength)
        return to24kMonoPcm16(rawPcm, sampleRate, channels)
    }
}
