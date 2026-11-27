package com.jarvis.assistant.audio.wake

import com.jarvis.assistant.core.runtime.JarvisEvent
import com.jarvis.assistant.core.runtime.JarvisEventBus
import java.util.Locale

enum class WakePhraseId(val displayLabel: String) {
    HEY_JARVIS("Hey Jarvis"),
    HELLO_JARVIS("Hello Jarvis"),
    WAKE_UP_JARVIS("Wake Up Jarvis"),
    CUSTOM("Custom Wake")
}

data class WakeCandidateScore(
    val phrase: WakePhraseId,
    val confidence: Float,
    val source: String
)

data class WakeTriggerResult(
    val triggered: Boolean,
    val phrase: WakePhraseId? = null,
    val confidence: Float = 0f,
    val smoothedConfidence: Float = 0f,
    val reason: String
)

/**
 * Coordinates 3-phrase JARVIS activation ("Hey Jarvis", "Hello Jarvis", "Wake Up Jarvis") across
 * LiteRT OpenWakeWord heads, Sherpa-ONNX keyword spotting, and Vosk/Kaldi fallback with
 * EMA confidence smoothing, consecutive frame confirmation, cooldown, and playback rejection.
 */
class WakeWordDecisionManager(
    private val cooldownMs: Long = 2_500L,
    private val requiredConfirmations: Int = 2,
    private val warmupFrames: Int = 15
) {
    companion object {
        val PRIMARY_PHRASES = listOf("hey jarvis", "hello jarvis", "wake up jarvis")

        fun matchPhraseFromTranscript(transcript: String, customAliases: List<String> = emptyList()): WakePhraseId? {
            val norm = transcript.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (norm.isEmpty()) return null
            return when {
                norm == "hey jarvis" || norm.startsWith("hey jarvis ") -> WakePhraseId.HEY_JARVIS
                norm == "hello jarvis" || norm.startsWith("hello jarvis ") -> WakePhraseId.HELLO_JARVIS
                norm == "wake up jarvis" || norm.startsWith("wake up jarvis ") -> WakePhraseId.WAKE_UP_JARVIS
                customAliases.any { alias ->
                    val cleanAlias = WakeMatcher.normalize(alias)
                    cleanAlias.isNotEmpty() && (norm == cleanAlias || norm.startsWith("$cleanAlias "))
                } -> WakePhraseId.CUSTOM
                else -> null
            }
        }
    }

    private var frameCounter = 0
    private val smoothedByPhrase = HashMap<WakePhraseId, Float>()
    private val consecutiveHits = HashMap<WakePhraseId, Int>()
    private var lastTriggerMs = Long.MIN_VALUE

    @Synchronized
    fun evaluateScores(
        candidates: List<WakeCandidateScore>,
        sensitivity: Int,
        speechRecent: Boolean,
        isPlaybackActive: Boolean,
        hasEchoReference: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): WakeTriggerResult {
        frameCounter++
        if (frameCounter <= warmupFrames) {
            return WakeTriggerResult(triggered = false, reason = "Warmup active ($frameCounter/$warmupFrames)")
        }
        if (isPlaybackActive && !hasEchoReference) {
            consecutiveHits.clear()
            return WakeTriggerResult(triggered = false, reason = "Suppressed during unreferenced playback")
        }
        if (!speechRecent) {
            decayAll()
            return WakeTriggerResult(triggered = false, reason = "Silero speech gate closed")
        }
        if (lastTriggerMs != Long.MIN_VALUE && (nowMs - lastTriggerMs) < cooldownMs) {
            return WakeTriggerResult(triggered = false, reason = "Cooldown active")
        }

        val baseThreshold = (0.88f - sensitivity.coerceIn(0, 100) * 0.0052f).coerceIn(0.32f, 0.88f)
        val effectiveThreshold = if (isPlaybackActive) (baseThreshold + 0.08f).coerceAtMost(0.92f) else baseThreshold

        var bestWinner: WakeTriggerResult? = null
        val presentIds = HashSet<WakePhraseId>(candidates.size)

        for (candidate in candidates) {
            val raw = candidate.confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            presentIds.add(candidate.phrase)
            val prevSmooth = smoothedByPhrase[candidate.phrase] ?: raw
            val smoothed = (prevSmooth * 0.45f) + (raw * 0.55f)
            smoothedByPhrase[candidate.phrase] = smoothed

            val hits = if (smoothed >= effectiveThreshold) {
                (consecutiveHits[candidate.phrase] ?: 0) + 1
            } else {
                0
            }
            consecutiveHits[candidate.phrase] = hits

            if (hits >= requiredConfirmations) {
                if (bestWinner == null || smoothed > bestWinner.smoothedConfidence) {
                    bestWinner = WakeTriggerResult(
                        triggered = true,
                        phrase = candidate.phrase,
                        confidence = raw,
                        smoothedConfidence = smoothed,
                        reason = "Confirmed ${candidate.phrase.displayLabel} via ${candidate.source}"
                    )
                }
            }
        }

        for (missing in WakePhraseId.entries) {
            if (missing !in presentIds) {
                consecutiveHits[missing] = 0
                smoothedByPhrase[missing] = (smoothedByPhrase[missing] ?: 0f) * 0.7f
            }
        }

        if (bestWinner != null) {
            consecutiveHits.clear()
            lastTriggerMs = nowMs
            JarvisEventBus.tryPublish(
                JarvisEvent.WakeWordDetected(
                    phrase = bestWinner.phrase?.displayLabel ?: "Jarvis",
                    confidence = bestWinner.smoothedConfidence,
                    eventId = JarvisEventBus.nextId(),
                    timestampMs = nowMs,
                    sessionId = 0L
                )
            )
            return bestWinner
        }

        val maxSmooth = smoothedByPhrase.values.maxOrNull() ?: 0f
        return WakeTriggerResult(triggered = false, smoothedConfidence = maxSmooth, reason = "Below confirmation threshold")
    }

    private fun decayAll() {
        consecutiveHits.clear()
        smoothedByPhrase.keys.forEach { key ->
            smoothedByPhrase[key] = (smoothedByPhrase[key] ?: 0f) * 0.6f
        }
    }

    @Synchronized
    fun reset() {
        frameCounter = 0
        lastTriggerMs = Long.MIN_VALUE
        smoothedByPhrase.clear()
        consecutiveHits.clear()
    }
}
