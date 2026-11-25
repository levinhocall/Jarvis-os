package com.jarvis.assistant.audio.wake

import kotlin.math.max

/**
 * Pure-Kotlin state machine implementing canonical Silero VAD v4 framing (`512` samples = 32 ms @ 16 kHz),
 * adaptive noise-floor tracking, onset confirmation, playback-aware barge-in gating, and 224 ms hangover.
 */
class SileroVadStateMachine {

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLES = 512          // 32 ms @ 16 kHz (canonical Silero v4 training size)
        const val DEFAULT_NOISE_FLOOR = 0.06f
        const val MIN_NOISE_FLOOR = 0.02f
        const val MAX_NOISE_FLOOR = 0.26f
        const val HANGOVER_FRAMES = 7          // 7 * 32 ms = 224 ms natural pause hangover
        const val INSTANT_ONSET_PROB = 0.74f
    }

    data class Decision(
        val rawProbability: Float,
        val effectiveProbability: Float,
        val isSpeechActive: Boolean,
        val speechStarted: Boolean,
        val speechEnded: Boolean,
        val enterThreshold: Float,
        val exitThreshold: Float,
        val noiseFloorProbability: Float
    )

    private var noiseFloorProb = DEFAULT_NOISE_FLOOR
    private var speechActive = false
    private var onsetConfirmCount = 0
    private var hangoverRemaining = 0
    private var lastSpeechSample = -1L
    private var lastRawProb = 0f

    @Synchronized
    fun reset() {
        noiseFloorProb = DEFAULT_NOISE_FLOOR
        speechActive = false
        onsetConfirmCount = 0
        hangoverRemaining = 0
        lastSpeechSample = -1L
        lastRawProb = 0f
    }

    @Synchronized
    fun recordFrameProbability(
        rawProbability: Float,
        totalSamplesProcessed: Long,
        playbackActive: Boolean = false
    ): Decision {
        val prob = rawProbability.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        lastRawProb = prob

        // Adapt ambient noise probability baseline only during non-speech, non-playback intervals.
        if (!speechActive && !playbackActive && prob < 0.35f) {
            noiseFloorProb = (noiseFloorProb * 0.94f + prob * 0.06f)
                .coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        }

        val enterThreshold = if (playbackActive) {
            maxOf(0.62f, noiseFloorProb + 0.36f).coerceAtMost(0.78f)
        } else {
            maxOf(0.45f, noiseFloorProb + 0.28f).coerceAtMost(0.68f)
        }
        val exitThreshold = if (playbackActive) {
            (enterThreshold - 0.16f).coerceAtLeast(0.42f)
        } else {
            (enterThreshold - 0.14f).coerceAtLeast(0.28f)
        }

        var speechStarted = false
        var speechEnded = false

        if (!speechActive) {
            if (prob >= enterThreshold) {
                onsetConfirmCount++
                lastSpeechSample = totalSamplesProcessed
                val strongOnset = prob >= maxOf(INSTANT_ONSET_PROB, enterThreshold + 0.12f)
                if (strongOnset || onsetConfirmCount >= 2) {
                    speechActive = true
                    speechStarted = true
                    hangoverRemaining = HANGOVER_FRAMES
                    onsetConfirmCount = 0
                }
            } else {
                onsetConfirmCount = 0
            }
        } else {
            if (prob >= exitThreshold) {
                hangoverRemaining = HANGOVER_FRAMES
                lastSpeechSample = totalSamplesProcessed
            } else if (hangoverRemaining > 0) {
                hangoverRemaining--
            } else {
                speechActive = false
                speechEnded = true
                onsetConfirmCount = 0
            }
        }

        val effectiveProb = if (speechActive) {
            max(prob, 0.55f)
        } else {
            prob
        }

        return Decision(
            rawProbability = prob,
            effectiveProbability = effectiveProb,
            isSpeechActive = speechActive,
            speechStarted = speechStarted,
            speechEnded = speechEnded,
            enterThreshold = enterThreshold,
            exitThreshold = exitThreshold,
            noiseFloorProbability = noiseFloorProb
        )
    }

    @Synchronized
    fun isSpeechRecent(totalSamplesProcessed: Long, windowSamples: Long = 8000L): Boolean =
        speechActive || (lastSpeechSample >= 0L && (totalSamplesProcessed - lastSpeechSample) <= windowSamples)

    @Synchronized
    fun isCurrentlyActive(): Boolean = speechActive
}
