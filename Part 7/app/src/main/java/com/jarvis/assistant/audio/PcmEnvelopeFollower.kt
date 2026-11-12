package com.jarvis.assistant.audio

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/** Display-only PCM analysis. Never participates in VAD, ownership or audio routing. */
class PcmEnvelopeFollower(private val attackMs: Float = 35f, private val releaseMs: Float = 200f) {
    private var lastMs = -1L
    var level = 0f
        private set

    fun reset() { level = 0f; lastMs = -1 }

    fun accept(pcm: ByteArray, nowMs: Long, enabled: Boolean = true): Float {
        if (!enabled) { reset(); return 0f }
        val target = normalize(rms(pcm, 0, pcm.size))
        // A capture gap is not held speech. Restart smoothing from silence.
        if (lastMs >= 0 && (nowMs < lastMs || nowMs - lastMs > 300)) level = 0f
        val dt = if (lastMs < 0) 20f else (nowMs - lastMs).coerceIn(0, 100).toFloat()
        lastMs = nowMs
        val tau = if (target > level) attackMs else releaseMs
        level += (target - level) * (1f - exp(-dt / tau.coerceAtLeast(1f)))
        return level.coerceIn(0f, 1f)
    }

    companion object {
        fun rms(pcm: ByteArray, offset: Int, length: Int): Float {
            val count = length / 2
            if (count == 0) return 0f
            var sum = 0.0
            var i = offset
            val end = offset + count * 2
            while (i < end) {
                val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                sum += sample.toDouble() * sample
                i += 2
            }
            return (sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
        }
        fun normalize(rms: Float): Float {
            if (!rms.isFinite() || rms <= 0f) return 0f
            val db = 20f * log10(rms.coerceAtMost(1f))
            val x = ((db + 55f) / 45f).coerceIn(0f, 1f)
            return (1.08f * x * x / (x + .08f)).coerceIn(0f, 1f)
        }
    }
}
