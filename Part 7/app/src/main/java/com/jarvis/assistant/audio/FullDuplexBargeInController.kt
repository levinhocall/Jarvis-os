package com.jarvis.assistant.audio

import com.jarvis.assistant.data.model.PlaybackSource

/**
 * Coordinates WebRTC AEC3 metrics, Dual DTLN status, Silero VAD v4 probability,
 * hardware fallback AEDP windows, and pre-roll gating for full-duplex barge-in.
 */
class FullDuplexBargeInController {

    companion object {
        const val FALLBACK_AEDP_WINDOW_MS = 150L
        const val SATURATION_COOLDOWN_FRAMES = 8
    }

    enum class DecisionKind {
        ALLOW_NORMAL_FLOW,
        HOLD_AEDP_DECAY,
        TRIGGER_NEURAL_BARGE_IN
    }

    data class FrameInput(
        val nowMs: Long,
        val lastPlaybackEndTimeMs: Long,
        val isPlaybackActive: Boolean,
        val hasPcmReference: Boolean,
        val playbackSource: PlaybackSource,
        val isAecActive: Boolean,
        val isDtlnActive: Boolean,
        val hasSpeechDetector: Boolean,
        val speechProbability: Float,
        val isSpeechConfirmed: Boolean,
        val aecMetrics: AecMetrics?
    )

    data class Decision(
        val kind: DecisionKind,
        val reason: String = "",
        val consecutiveSpeechFrames: Int = 0,
        val saturationActive: Boolean = false
    )

    private var consecutiveNeuralSpeechFrames = 0
    private var lastObservedSaturatedFrames = 0L
    private var saturationCooldownRemaining = 0

    @Synchronized
    fun reset() {
        consecutiveNeuralSpeechFrames = 0
        lastObservedSaturatedFrames = 0L
        saturationCooldownRemaining = 0
    }

    /**
     * Checks post-playback decay protection cleanly: removes blind 150 ms deafening whenever
     * hardware-isolated AEC3 + Silero VAD v4 are healthy without mic clipping saturation.
     */
    @Synchronized
    fun shouldDropPostPlaybackDecay(input: FrameInput): Boolean {
        updateSaturationState(input.aecMetrics)
        val elapsedSinceStop = input.nowMs - input.lastPlaybackEndTimeMs
        if (elapsedSinceStop !in 0L until FALLBACK_AEDP_WINDOW_MS) return false

        val cleanFullDuplex = input.isAecActive &&
            input.hasSpeechDetector &&
            saturationCooldownRemaining == 0
        return !cleanFullDuplex
    }

    /**
     * Evaluates neural AEC3 + DTLN + Silero v4 barge-in during active speaker playback.
     */
    @Synchronized
    fun evaluateDuringPlayback(input: FrameInput): Decision {
        updateSaturationState(input.aecMetrics)
        val saturating = saturationCooldownRemaining > 0

        if (!input.isPlaybackActive ||
            !input.isAecActive ||
            !input.hasSpeechDetector ||
            (!input.hasPcmReference && input.playbackSource != PlaybackSource.GEMINI_PCM)
        ) {
            consecutiveNeuralSpeechFrames = 0
            return Decision(DecisionKind.ALLOW_NORMAL_FLOW, saturationActive = saturating)
        }

        val prob = input.speechProbability.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val threshold = when {
            saturating -> 0.78f
            input.isDtlnActive -> 0.65f
            else -> 0.68f
        }
        val frameVoiced = if (saturating) {
            prob >= threshold
        } else {
            input.isSpeechConfirmed || prob >= threshold
        }

        if (frameVoiced) {
            consecutiveNeuralSpeechFrames++
        } else {
            consecutiveNeuralSpeechFrames = 0
            return Decision(DecisionKind.ALLOW_NORMAL_FLOW, saturationActive = saturating)
        }

        val requiredFrames = when {
            saturating -> 4
            input.isDtlnActive -> 2
            else -> 3
        }
        val instantHighConfidence = !saturating && input.isDtlnActive && prob >= 0.84f

        return if (instantHighConfidence || consecutiveNeuralSpeechFrames >= requiredFrames) {
            val frames = consecutiveNeuralSpeechFrames
            consecutiveNeuralSpeechFrames = 0
            val sourceTag = if (input.isDtlnActive) "aec3_dtln_silero_speech" else "aec3_silero_speech"
            Decision(
                kind = DecisionKind.TRIGGER_NEURAL_BARGE_IN,
                reason = sourceTag,
                consecutiveSpeechFrames = frames,
                saturationActive = saturating
            )
        } else {
            Decision(
                kind = DecisionKind.ALLOW_NORMAL_FLOW,
                consecutiveSpeechFrames = consecutiveNeuralSpeechFrames,
                saturationActive = saturating
            )
        }
    }

    private fun updateSaturationState(metrics: AecMetrics?) {
        if (metrics != null) {
            if (metrics.saturatedCaptureFrames > lastObservedSaturatedFrames) {
                saturationCooldownRemaining = SATURATION_COOLDOWN_FRAMES
                lastObservedSaturatedFrames = metrics.saturatedCaptureFrames
            } else if (metrics.saturatedCaptureFrames < lastObservedSaturatedFrames) {
                // AEC3 reset cleared cumulative frame counters.
                lastObservedSaturatedFrames = metrics.saturatedCaptureFrames
            }
        }
        if (saturationCooldownRemaining > 0) {
            saturationCooldownRemaining--
        }
    }
}
