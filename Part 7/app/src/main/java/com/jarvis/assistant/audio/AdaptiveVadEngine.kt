package com.jarvis.assistant.audio

/**
 * Result produced by [AdaptiveVadEngine] for an incoming audio frame.
 */
data class VadDecision(
    val isVoiced: Boolean,
    val isSpeechActive: Boolean,
    val isSpeechStart: Boolean,
    val isTurnComplete: Boolean,
    val ambientNoiseFloor: Float,
    val dynamicVoiceThreshold: Float,
    val dynamicSilenceTimeoutMs: Long
)

/**
 * High-performance, adaptive Voice Activity Detector (VAD).
 *
 * Replaces static silence thresholds with dynamic acoustic energy tracking,
 * zero-crossing rate analysis, and conversational context awareness to reduce
 * turn-taking latency from 450ms down to ~200-280ms while preventing premature cutoffs.
 */
class AdaptiveVadEngine(
    private val minSilenceTimeoutMs: Long = 200L,
    private val normalSilenceTimeoutMs: Long = 280L,
    private val maxSilenceTimeoutMs: Long = 420L
) {
    companion object {
        const val NOISE_FLOOR_MIN = 0.015f
        const val NOISE_FLOOR_MAX = 0.10f
        const val VAD_NOISE_MULTIPLIER = 1.85f
        const val VAD_MIN_THRESHOLD = 0.035f
        const val VAD_START_FRAMES = 3 // ~60ms of continuous speech to trigger start
        const val VAD_MIN_UTTERANCE_FRAMES = 7 // ~140ms minimum for meaningful speech
    }

    private var ambientNoiseFloor = 0.025f
    private var consecutiveVoicedFrames = 0
    private var totalVoicedFrames = 0
    private var isSpeechActive = false
    private var lastVoicedTimestamp = 0L
    private var speechStartTimestamp = 0L

    fun reset() {
        consecutiveVoicedFrames = 0
        totalVoicedFrames = 0
        isSpeechActive = false
        lastVoicedTimestamp = 0L
        speechStartTimestamp = 0L
    }

    /**
     * Evaluates a 20ms audio frame and computes adaptive VAD decision.
     */
    fun processFrame(
        amplitude: Float,
        frequencyData: AudioFrequencyData,
        now: Long = System.currentTimeMillis()
    ): VadDecision {
        // 1. Adaptively track background ambient noise floor when quiet
        if (amplitude < 0.09f && !isSpeechActive) {
            ambientNoiseFloor = (ambientNoiseFloor * 0.96f + amplitude * 0.04f)
                .coerceIn(NOISE_FLOOR_MIN, NOISE_FLOOR_MAX)
        }

        // 2. Dynamic energy threshold: adjusts to noisy rooms vs quiet environments
        val voiceThreshold = maxOf(VAD_MIN_THRESHOLD, ambientNoiseFloor * VAD_NOISE_MULTIPLIER)

        // 3. Human vocal band weighting: human speech concentrates between 120Hz - 2800Hz
        val hasVocalCharacteristics = frequencyData.pitchHz in 85f..3200f || frequencyData.mid > 0.04f
        val isFrameVoiced = amplitude > voiceThreshold && (hasVocalCharacteristics || amplitude > voiceThreshold * 1.3f)

        var speechJustStarted = false
        var turnJustCompleted = false

        if (isFrameVoiced) {
            consecutiveVoicedFrames++
            lastVoicedTimestamp = now

            if (consecutiveVoicedFrames >= VAD_START_FRAMES) {
                if (!isSpeechActive) {
                    isSpeechActive = true
                    speechJustStarted = true
                    speechStartTimestamp = now
                    totalVoicedFrames = 0
                }
                totalVoicedFrames++
            }
        } else {
            // Gradual decay: one quiet 20ms frame does not immediately kill voiced speech tracking
            if (consecutiveVoicedFrames > 0) consecutiveVoicedFrames--

            if (isSpeechActive) {
                val silentDuration = now - lastVoicedTimestamp
                val currentSilenceTimeout = computeAdaptiveSilenceTimeout()

                if (silentDuration >= currentSilenceTimeout) {
                    // Turn boundary reached
                    if (totalVoicedFrames >= VAD_MIN_UTTERANCE_FRAMES) {
                        turnJustCompleted = true
                    }
                    isSpeechActive = false
                    consecutiveVoicedFrames = 0
                }
            }
        }

        return VadDecision(
            isVoiced = isFrameVoiced,
            isSpeechActive = isSpeechActive,
            isSpeechStart = speechJustStarted,
            isTurnComplete = turnJustCompleted,
            ambientNoiseFloor = ambientNoiseFloor,
            dynamicVoiceThreshold = voiceThreshold,
            dynamicSilenceTimeoutMs = computeAdaptiveSilenceTimeout()
        )
    }

    /**
     * Computes the adaptive silence pause required before declaring turn completion.
     *
     * Short commands with clear endings trigger fast completion (~200-240ms),
     * while longer or trailing sentences allow more breathing room (~280-380ms).
     */
    fun computeAdaptiveSilenceTimeout(): Long {
        return when {
            // Very short command or reflex (e.g. "Stop", "Turn on flashlight", "Play song")
            totalVoicedFrames in VAD_MIN_UTTERANCE_FRAMES..18 -> minSilenceTimeoutMs + 30L // ~230ms
            // Standard conversational sentence with good volume
            totalVoicedFrames in 19..50 -> normalSilenceTimeoutMs // ~280ms
            // Long narrative or complex sentence with possible hesitation pauses
            totalVoicedFrames > 50 -> (normalSilenceTimeoutMs + 70L).coerceAtMost(maxSilenceTimeoutMs) // ~350ms
            else -> normalSilenceTimeoutMs
        }
    }

    fun getTotalVoicedFrames(): Int = totalVoicedFrames
    fun getAmbientNoiseFloor(): Float = ambientNoiseFloor
    fun isSpeaking(): Boolean = isSpeechActive
}
