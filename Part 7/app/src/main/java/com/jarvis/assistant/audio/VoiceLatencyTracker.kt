package com.jarvis.assistant.audio

import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks real-time voice pipeline turn latencies (TTFT, TTFA, total turn duration).
 * Enforces strict sequential validation to ignore out-of-order or late-arriving timestamps.
 */
class VoiceLatencyTracker(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class TurnLatency(
        val turnId: Long,
        val userSpeechEndedMs: Long,
        val firstTextChunkMs: Long? = null,
        val firstAudioChunkMs: Long? = null,
        val turnCompletedMs: Long? = null
    ) {
        val timeToFirstTokenMs: Long?
            get() = firstTextChunkMs?.let { it - userSpeechEndedMs }?.takeIf { it >= 0 }

        val timeToFirstAudioMs: Long?
            get() = firstAudioChunkMs?.let { it - userSpeechEndedMs }?.takeIf { it >= 0 }

        val totalTurnDurationMs: Long?
            get() = turnCompletedMs?.let { it - userSpeechEndedMs }?.takeIf { it >= 0 }
    }

    private val turnCounter = AtomicLong(0L)
    private val currentTurn = AtomicReference<TurnLatency?>(null)
    private val recentTurns = Collections.synchronizedList(LinkedList<TurnLatency>())
    private val maxHistory = 20

    /**
     * Marks the moment the user stopped speaking (VAD speech end).
     * Initializes a new turn tracker.
     */
    fun markUserSpeechEnded(timestampMs: Long = clock()): Long {
        val turnId = turnCounter.incrementAndGet()
        val turn = TurnLatency(
            turnId = turnId,
            userSpeechEndedMs = timestampMs
        )
        currentTurn.set(turn)
        return turnId
    }

    /**
     * Marks the first token/text chunk received from the model.
     * Ignored if no turn is active or if timestamp is before user speech ended.
     */
    fun markFirstTextReceived(timestampMs: Long = clock()) {
        currentTurn.updateAndGet { turn ->
            if (turn != null && turn.firstTextChunkMs == null && timestampMs >= turn.userSpeechEndedMs) {
                turn.copy(firstTextChunkMs = timestampMs)
            } else {
                turn
            }
        }
    }

    /**
     * Marks the first playable audio chunk received from the model.
     * Ignored if no turn is active or if timestamp is before user speech ended.
     */
    fun markFirstAudioReceived(timestampMs: Long = clock()) {
        currentTurn.updateAndGet { turn ->
            if (turn != null && turn.firstAudioChunkMs == null && timestampMs >= turn.userSpeechEndedMs) {
                turn.copy(firstAudioChunkMs = timestampMs)
            } else {
                turn
            }
        }
    }

    /**
     * Marks the completion of the assistant's turn.
     * Archives the completed turn to the recent history buffer.
     */
    fun markTurnCompleted(timestampMs: Long = clock()): TurnLatency? {
        val completed = currentTurn.getAndUpdate { null } ?: return null
        if (timestampMs < completed.userSpeechEndedMs) {
            // Out of order: turn completed before speech ended
            return null
        }
        val finalTurn = completed.copy(turnCompletedMs = timestampMs)
        synchronized(recentTurns) {
            recentTurns.add(finalTurn)
            while (recentTurns.size > maxHistory) {
                recentTurns.removeAt(0)
            }
        }
        return finalTurn
    }

    /**
     * Discards the current in-flight turn (e.g. on user barge-in / interruption).
     */
    fun cancelCurrentTurn() {
        currentTurn.set(null)
    }

    /**
     * Returns the latest completed turn metrics.
     */
    fun getLatestTurn(): TurnLatency? {
        synchronized(recentTurns) {
            return recentTurns.lastOrNull()
        }
    }

    /**
     * Computes average Time To First Token (TTFT) across recent valid turns.
     */
    fun getAverageTtftMs(): Long? {
        synchronized(recentTurns) {
            val valid = recentTurns.mapNotNull { it.timeToFirstTokenMs }
            return if (valid.isNotEmpty()) valid.average().toLong() else null
        }
    }

    /**
     * Computes average Time To First Audio (TTFA) across recent valid turns.
     */
    fun getAverageTtfaMs(): Long? {
        synchronized(recentTurns) {
            val valid = recentTurns.mapNotNull { it.timeToFirstAudioMs }
            return if (valid.isNotEmpty()) valid.average().toLong() else null
        }
    }
}
