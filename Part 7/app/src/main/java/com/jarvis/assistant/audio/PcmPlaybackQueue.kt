package com.jarvis.assistant.audio

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Byte and frame bounded; generation travels with the frame all the way to the hardware write. */
internal class PcmPlaybackQueue(private val maxBytes: Int = 2 * 1024 * 1024, private val maxFrames: Int = 512) {
    data class Frame(val bytes: ByteArray, val epoch: Long, val generation: Long)
    enum class Offer { ACCEPTED, STALE, CLOSED, OVERFLOW }
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val frames = ArrayDeque<Frame>()
    private var bytes = 0
    private var epoch = 0L
    private var generation = 0L
    private var closed = false
    init { require(maxBytes > 0 && maxFrames > 0) }
    val queuedBytes: Int get() = lock.withLock { bytes }
    val size: Int get() = lock.withLock { frames.size }
    fun isEmpty(): Boolean = lock.withLock { frames.isEmpty() }
    fun offer(data: ByteArray, frameEpoch: Long): Offer = lock.withLock {
        if (closed) return Offer.CLOSED
        if (epoch != frameEpoch) return Offer.STALE
        val length = data.size and 1.inv()
        if (length > maxBytes - bytes || frames.size >= maxFrames) return Offer.OVERFLOW
        frames.addLast(Frame(data.copyOf(length), frameEpoch, generation))
        bytes += length; changed.signal()
        Offer.ACCEPTED
    }
    @Throws(InterruptedException::class)
    fun poll(timeout: Long, unit: TimeUnit): Frame? = lock.withLock {
        var remaining = unit.toNanos(timeout)
        while (frames.isEmpty() && !closed && remaining > 0) remaining = changed.awaitNanos(remaining)
        if (frames.isEmpty()) return null
        frames.removeFirst().also { bytes -= it.bytes.size }
    }
    fun isCurrent(frame: Frame): Boolean = lock.withLock { !closed && epoch == frame.epoch && generation == frame.generation }
    fun advance(nextEpoch: Long) = lock.withLock {
        epoch = maxOf(epoch, nextEpoch); generation++; frames.clear(); bytes = 0; changed.signalAll()
    }
    fun close() = lock.withLock { closed = true; generation++; frames.clear(); bytes = 0; changed.signalAll() }
}
