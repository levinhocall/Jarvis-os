package com.jarvis.assistant.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reusable timestamped 16 kHz mono PCM16 capture/processing frame backed by a pooled byte buffer.
 * Zero per-frame heap allocation on hot audio paths when returned via [AudioFramePool.recycle].
 */
class AudioFrame(capacityBytes: Int = 640) {
    val pcmBytes: ByteArray = ByteArray(capacityBytes)
    val directBuffer: ByteBuffer = ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.LITTLE_ENDIAN)
    @Volatile var validBytes: Int = 0
    @Volatile var sampleRateHz: Int = 16_000
    @Volatile var timestampNs: Long = 0L
    @Volatile var frameIndex: Long = 0L

    fun loadFrom(src: ByteArray, offset: Int, length: Int, sampleRate: Int, monotonicNs: Long, index: Long) {
        val copyLen = length.coerceIn(0, pcmBytes.size)
        System.arraycopy(src, offset, pcmBytes, 0, copyLen)
        directBuffer.clear()
        directBuffer.put(pcmBytes, 0, copyLen)
        directBuffer.flip()
        validBytes = copyLen
        sampleRateHz = sampleRate
        timestampNs = monotonicNs
        frameIndex = index
    }
}

/** Bounded non-blocking frame pool preventing GC churn on real-time audio capture threads. */
class AudioFramePool(
    private val capacity: Int = 64,
    private val maxFrameBytes: Int = 640
) {
    private val available = ArrayBlockingQueue<AudioFrame>(capacity)
    private val totalAllocated = AtomicInteger(0)

    init {
        repeat(capacity.coerceAtMost(16)) {
            available.offer(AudioFrame(maxFrameBytes))
            totalAllocated.incrementAndGet()
        }
    }

    fun acquire(): AudioFrame {
        available.poll()?.let { return it }
        return if (totalAllocated.incrementAndGet() <= capacity) {
            AudioFrame(maxFrameBytes)
        } else {
            totalAllocated.decrementAndGet()
            AudioFrame(maxFrameBytes)
        }
    }

    fun recycle(frame: AudioFrame) {
        frame.validBytes = 0
        available.offer(frame)
    }

    fun pooledCount(): Int = available.size
}

/**
 * Bounded lock-free-friendly PCM ring buffer holding recent speech frames (e.g. barge-in pre-roll
 * or echo reference history) without allocating during continuous streaming.
 */
class AudioRingBuffer(private val capacityBytes: Int) {
    private val ring = ByteArray(capacityBytes.coerceAtLeast(320))
    private var writePos = 0
    private var filled = 0
    private val totalWritten = AtomicLong(0L)

    @Synchronized
    fun write(src: ByteArray, offset: Int = 0, length: Int = src.size) {
        if (length <= 0) return
        val effectiveLen = length.coerceAtMost(ring.size)
        val srcOffset = if (length > ring.size) offset + (length - ring.size) else offset
        val first = minOf(effectiveLen, ring.size - writePos)
        System.arraycopy(src, srcOffset, ring, writePos, first)
        if (first < effectiveLen) {
            System.arraycopy(src, srcOffset + first, ring, 0, effectiveLen - first)
        }
        writePos = (writePos + effectiveLen) % ring.size
        filled = minOf(ring.size, filled + effectiveLen)
        totalWritten.addAndGet(effectiveLen.toLong())
    }

    @Synchronized
    fun snapshotRecent(maxBytes: Int = filled): ByteArray {
        val count = maxBytes.coerceIn(0, filled)
        if (count == 0) return ByteArray(0)
        val start = (writePos - count + ring.size) % ring.size
        val out = ByteArray(count)
        val first = minOf(count, ring.size - start)
        System.arraycopy(ring, start, out, 0, first)
        if (first < count) {
            System.arraycopy(ring, 0, out, first, count - first)
        }
        return out
    }

    @Synchronized
    fun clear() {
        writePos = 0
        filled = 0
        ring.fill(0)
    }

    @Synchronized
    fun availableBytes(): Int = filled

    fun totalBytesWritten(): Long = totalWritten.get()
}
