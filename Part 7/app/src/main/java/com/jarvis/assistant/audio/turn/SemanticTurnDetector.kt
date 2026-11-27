package com.jarvis.assistant.audio.turn

import com.jarvis.assistant.core.runtime.JarvisEvent
import com.jarvis.assistant.core.runtime.JarvisEventBus
import java.util.Locale

enum class ConversationTurnState {
    IDLE,
    SPEECH_STARTED,
    SPEECH_ACTIVE,
    POSSIBLE_PAUSE,
    TURN_COMPLETE,
    RESPONSE_PENDING,
    ASSISTANT_SPEAKING,
    INTERRUPTED
}

data class SemanticTurnAssessment(
    val completionScore: Float,
    val isHesitation: Boolean,
    val isExplicitHoldOrCorrection: Boolean,
    val hasTerminalPunctuationOrIntent: Boolean,
    val suggestedHoldExtensionMs: Long,
    val reason: String
)

/**
 * Clean-room semantic token turn-completion & hesitation scorer supporting English, Hindi,
 * and Romanized Hinglish conversational flow without relying on Maya proprietary weights.
 */
object SemanticTurnDetector {

    private val HESITATION_MARKERS = setOf(
        "um", "umm", "uh", "uhh", "erm", "hmm", "matlab", "mtlb", "actually",
        "wait", "hold", "ruko", "ek", "second", "min", "minute", "sorry"
    )

    private val INCOMPLETE_TRAILING_TOKENS = setOf(
        "and", "or", "but", "so", "because", "if", "when", "while", "that", "which",
        "to", "for", "with", "from", "about", "like", "the", "a", "an", "my", "your",
        "ki", "aur", "lekin", "par", "kyunki", "agar", "jab", "toh", "to", "ko",
        "se", "mein", "me", "ka", "ke", "wala", "wali", "ya"
    )

    private val COMPLETE_TRAILING_INTERROGATIVES_OR_ENDINGS = setOf(
        "please", "now", "today", "tomorrow", "jarvis", "karo", "batao", "btao",
        "kholo", "bhejo", "hai", "hain", "hua", "kya", "why", "what", "how",
        "when", "where", "who", "thanks", "thank", "shukriya", "theek"
    )

    fun scoreTranscript(partialOrFinalText: String): SemanticTurnAssessment {
        val raw = partialOrFinalText.trim()
        if (raw.isEmpty()) {
            return SemanticTurnAssessment(
                completionScore = 0.50f,
                isHesitation = false,
                isExplicitHoldOrCorrection = false,
                hasTerminalPunctuationOrIntent = false,
                suggestedHoldExtensionMs = 0L,
                reason = "empty_transcript"
            )
        }

        val lower = raw.lowercase(Locale.ROOT)
        val hasEllipsisOrDash = lower.endsWith("...") || lower.endsWith("..") || lower.endsWith("-") || lower.endsWith(",")
        val hasTerminalMark = lower.endsWith("?") || lower.endsWith("!") || (lower.endsWith(".") && !hasEllipsisOrDash)

        val tokens = lower.replace(Regex("[^a-z0-9\\u0900-\\u097f\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        if (tokens.isEmpty()) {
            return SemanticTurnAssessment(0.35f, isHesitation = true, isExplicitHoldOrCorrection = false, hasTerminalPunctuationOrIntent = false, suggestedHoldExtensionMs = 350L, reason = "punctuation_only")
        }

        val last = tokens.last()
        val tailWindow = tokens.takeLast(3)

        val hasExplicitCorrection = lower.contains("actually wait") ||
            lower.contains("hold on") ||
            lower.contains("ek second") ||
            lower.contains("ruko") ||
            lower.contains("nahi wait")

        val trailingHesitation = last in HESITATION_MARKERS || tailWindow.any { it in setOf("um", "umm", "uh", "uhh", "erm") }
        val trailingIncompleteLinker = last in INCOMPLETE_TRAILING_TOKENS
        val completeEnding = hasTerminalMark || last in COMPLETE_TRAILING_INTERROGATIVES_OR_ENDINGS

        var score = 0.62f
        if (tokens.size >= 4) score += 0.12f
        if (completeEnding) score += 0.22f
        if (hasEllipsisOrDash) score -= 0.35f
        if (trailingIncompleteLinker) score -= 0.38f
        if (trailingHesitation) score -= 0.30f
        if (hasExplicitCorrection) score -= 0.45f

        val bounded = score.coerceIn(0.05f, 0.98f)
        val extensionMs = when {
            hasExplicitCorrection || bounded <= 0.25f -> 650L
            trailingIncompleteLinker || trailingHesitation || hasEllipsisOrDash -> 420L
            bounded >= 0.85f -> -80L
            else -> 0L
        }

        val reason = when {
            hasExplicitCorrection -> "explicit_hold_or_correction"
            trailingHesitation -> "hesitation_marker"
            trailingIncompleteLinker -> "incomplete_clause_linker($last)"
            hasEllipsisOrDash -> "trailing_pause_marker"
            completeEnding -> "complete_utterance"
            else -> "neutral_lexical"
        }

        return SemanticTurnAssessment(
            completionScore = bounded,
            isHesitation = trailingHesitation,
            isExplicitHoldOrCorrection = hasExplicitCorrection,
            hasTerminalPunctuationOrIntent = hasTerminalMark,
            suggestedHoldExtensionMs = extensionMs,
            reason = reason
        )
    }
}

data class TurnDecisionInput(
    val silenceDurationMs: Long,
    val baseVadTimeoutMs: Long,
    val acousticTurnProbability: Float?,
    val partialTranscript: String,
    val hasActiveSpeech: Boolean
)

data class TurnDecisionOutput(
    val state: ConversationTurnState,
    val shouldCompleteTurn: Boolean,
    val combinedScore: Float,
    val effectiveTimeoutMs: Long,
    val reason: String
)

/** Combines acoustic Smart Turn v3.2 + VAD silence duration + [SemanticTurnDetector] lexical cues. */
class TurnDecisionEngine {

    @Volatile var state: ConversationTurnState = ConversationTurnState.IDLE
        private set

    fun onSpeechStarted() {
        state = ConversationTurnState.SPEECH_ACTIVE
    }

    fun onResponsePending() {
        state = ConversationTurnState.RESPONSE_PENDING
    }

    fun onAssistantSpeaking() {
        state = ConversationTurnState.ASSISTANT_SPEAKING
    }

    fun onInterrupted() {
        state = ConversationTurnState.INTERRUPTED
    }

    fun reset() {
        state = ConversationTurnState.IDLE
    }

    fun evaluatePause(input: TurnDecisionInput): TurnDecisionOutput {
        val semantic = SemanticTurnDetector.scoreTranscript(input.partialTranscript)
        val acoustic = input.acousticTurnProbability?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

        val combined = if (acoustic != null) {
            (acoustic * 0.50f) + (semantic.completionScore * 0.50f)
        } else {
            semantic.completionScore
        }

        val semanticTimeout = (input.baseVadTimeoutMs + semantic.suggestedHoldExtensionMs)
            .coerceIn(180L, 950L)

        // Do not let pure silence trigger immediately when user explicitly paused mid-clause/hesitation
        val minRequiredSilence = if (combined >= 0.85f && !semantic.isHesitation && !semantic.isExplicitHoldOrCorrection) {
            minOf(input.baseVadTimeoutMs, 220L)
        } else {
            semanticTimeout
        }

        val ready = !input.hasActiveSpeech &&
            input.silenceDurationMs >= minRequiredSilence &&
            (!semantic.isExplicitHoldOrCorrection || input.silenceDurationMs >= 900L)

        if (ready) {
            state = ConversationTurnState.TURN_COMPLETE
            JarvisEventBus.tryPublish(
                JarvisEvent.TurnCompleted(
                    combinedScore = combined,
                    reason = semantic.reason,
                    eventId = JarvisEventBus.nextId(),
                    timestampMs = System.currentTimeMillis(),
                    sessionId = 0L
                )
            )
        } else {
            state = ConversationTurnState.POSSIBLE_PAUSE
        }

        return TurnDecisionOutput(
            state = state,
            shouldCompleteTurn = ready,
            combinedScore = combined,
            effectiveTimeoutMs = minRequiredSilence,
            reason = semantic.reason
        )
    }
}
