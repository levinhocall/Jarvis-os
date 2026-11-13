package com.jarvis.assistant.audio

/** Fixed-size metadata only. PCM remains in the existing bounded playback queue/AudioTrack. */
class PcmEnvelopeTimeline(private val capacity: Int = 256, private val blockFrames: Int = 240) {
    init { require(capacity > 0 && blockFrames > 0) }
    private val starts = LongArray(capacity)
    private val ends = LongArray(capacity)
    private val levels = FloatArray(capacity)
    private var first = 0
    var size = 0
        private set
    var writtenFrames = 0L
        private set
    var epoch = 0L
        private set

    fun reset(epoch: Long) { first = 0; size = 0; writtenFrames = 0; this.epoch = epoch }
    /** Route changes discard timing metadata without pretending that the track's frame count reset. */
    fun discardEnvelopes() { first = 0; size = 0 }

    fun accepted(pcm: ByteArray, offset: Int, byteCount: Int, epoch: Long): Boolean {
        if (epoch != this.epoch || offset < 0 || byteCount < 0 || offset + byteCount > pcm.size || byteCount % 2 != 0) return false
        var cursor = offset
        val end = offset + byteCount
        while (cursor < end) {
            val length = minOf(blockFrames * 2, end - cursor)
            if (size == capacity) { first = (first + 1) % capacity; size-- }
            val index = (first + size) % capacity
            starts[index] = writtenFrames
            writtenFrames += length / 2
            ends[index] = writtenFrames
            levels[index] = PcmEnvelopeFollower.normalize(PcmEnvelopeFollower.rms(pcm, cursor, length))
            size++; cursor += length
        }
        return true
    }

    /** Head is the next frame to play. A queued chunk, stationary zero head or drained track is not audible. */
    fun levelAt(playedFrame: Long, epoch: Long): Float? {
        if (epoch != this.epoch || playedFrame <= 0 || playedFrame >= writtenFrames) return null
        while (size > 0 && ends[first] <= playedFrame) { first = (first + 1) % capacity; size-- }
        if (size == 0 || playedFrame < starts[first]) return null
        return levels[first]
    }
}

/** Android's unsigned 32-bit head wraps; flush/new track explicitly resets this clock. */
class PlaybackFrameClock {
    private var previous = 0L
    private var wraps = 0L
    fun reset() { previous = 0; wraps = 0 }
    fun head(raw: Int): Long {
        val unsigned = raw.toLong() and 0xffffffffL
        if (unsigned < previous && previous - unsigned > 0x80000000L) wraps++
        previous = unsigned
        return unsigned + (wraps shl 32)
    }
    fun timestamp(frame: Long, timestampNs: Long, nowNs: Long, written: Long, sampleRate: Int): Long? {
        val age = nowNs - timestampNs
        if (frame < 0 || timestampNs <= 0 || age !in 0..250_000_000L) return null
        val projected = frame + age * sampleRate / 1_000_000_000L
        return projected.takeIf { it in 1..written }
    }
}
