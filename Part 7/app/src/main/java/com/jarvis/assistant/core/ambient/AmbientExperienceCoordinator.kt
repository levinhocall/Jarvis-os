package com.jarvis.assistant.core.ambient

import android.util.Log
import com.jarvis.assistant.data.model.ConversationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

data class ConversationStateTransition(
    val sequence: Long,
    val from: ConversationState,
    val to: ConversationState,
    val timestampEpochMs: Long,
    val reason: String
)

object AmbientExperienceCoordinator {
    private const val TAG = "AmbientExperienceCoord"

    private const val MAX_TRANSITION_HISTORY = 100
    private const val MAX_REASON_LENGTH = 120

    private val stateMutex = Any()
    private val missionMutex = Any()
    private val transitionSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val transitionBuffer = ArrayDeque<ConversationStateTransition>()

    private val _conversationState = MutableStateFlow(ConversationState.IDLE)
    val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

    private val _transitionHistory = MutableStateFlow<List<ConversationStateTransition>>(emptyList())
    /** A bounded, immutable diagnostic snapshot. Never stores user prompts or sensitive payloads. */
    val transitionHistory: StateFlow<List<ConversationStateTransition>> = _transitionHistory.asStateFlow()

    private val _activeMissions = MutableStateFlow<Map<String, OngoingMissionInfo>>(emptyMap())
    val activeMissions: StateFlow<Map<String, OngoingMissionInfo>> = _activeMissions.asStateFlow()

    private val cancellationCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private var activeLockTag: String? = null
    private val lockMutex = Any()

    /**
     * Serializes state changes so concurrent voice, mission, and UI events cannot
     * overwrite each other’s diagnostic history. The optional reason must be a
     * short, non-sensitive event label, never a user prompt or API response.
     */
    fun transitionTo(newState: ConversationState, reason: String = "unspecified") {
        synchronized(stateMutex) {
            val oldState = _conversationState.value
            if (oldState == newState) return

            _conversationState.value = newState
            val safeReason = reason.trim().take(MAX_REASON_LENGTH).ifEmpty { "unspecified" }
            transitionBuffer.addLast(
                ConversationStateTransition(
                    sequence = transitionSequence.incrementAndGet(),
                    from = oldState,
                    to = newState,
                    timestampEpochMs = System.currentTimeMillis(),
                    reason = safeReason
                )
            )
            while (transitionBuffer.size > MAX_TRANSITION_HISTORY) transitionBuffer.removeFirst()
            _transitionHistory.value = transitionBuffer.toList()
            Log.i(TAG, "Unified state transitioned: $oldState -> $newState (reason=$safeReason)")
        }
    }

    /**
     * Thread-safe execution lock preventing concurrent tool executions from corrupting UI or memory state.
     */
    fun acquireExecutionLock(tag: String): Boolean {
        synchronized(lockMutex) {
            if (activeLockTag != null && activeLockTag != tag) {
                Log.w(TAG, "Execution lock rejected for '$tag'; currently held by '$activeLockTag'")
                return false
            }
            activeLockTag = tag
            transitionTo(ConversationState.EXECUTING)
            return true
        }
    }

    fun releaseExecutionLock(tag: String) {
        synchronized(lockMutex) {
            if (activeLockTag == tag) {
                activeLockTag = null
                if (_conversationState.value == ConversationState.EXECUTING) {
                    transitionTo(ConversationState.IDLE)
                }
            }
        }
    }

    /**
     * Registers a new ongoing mission, making it inspectable and cancellable across all UI surfaces.
     */
    fun registerMission(
        info: OngoingMissionInfo,
        onCancel: (() -> Unit)? = null
    ) {
        synchronized(missionMutex) {
            val current = _activeMissions.value.toMutableMap()
            current[info.missionId] = info
            _activeMissions.value = current
            if (onCancel != null) cancellationCallbacks[info.missionId] = onCancel
        }
        Log.i(TAG, "Mission registered: ${info.missionId} ('${info.title}')")
    }

    fun updateMissionProgress(
        missionId: String,
        currentStepDescription: String,
        progressFraction: Float
    ) {
        synchronized(missionMutex) {
            val current = _activeMissions.value.toMutableMap()
            val existing = current[missionId] ?: return
            current[missionId] = existing.copy(
                currentStepDescription = currentStepDescription,
                progressFraction = progressFraction.coerceIn(0.0f, 1.0f)
            )
            _activeMissions.value = current
        }
    }

    /**
     * Cancels an ongoing mission, invokes its cancellation hook, and removes it from active list.
     */
    fun cancelMission(missionId: String): Boolean {
        val (mission, callback) = synchronized(missionMutex) {
            val current = _activeMissions.value.toMutableMap()
            val removed = current.remove(missionId)
            _activeMissions.value = current
            removed to cancellationCallbacks.remove(missionId)
        }
        // Invoke external cancellation code after releasing internal locks.
        callback?.invoke()

        releaseExecutionLock(missionId)

        if (mission != null) {
            Log.i(TAG, "Mission cancelled: $missionId ('${mission.title}')")
            return true
        }
        return false
    }

    /**
     * Instantaneous User Interruption / Barge-in handler.
     * Transitions state to INTERRUPTED and cancels all active cancellable missions and lock holders.
     */
    fun handleUserBargeIn() {
        Log.i(TAG, "Barge-in detected! Aborting active spoken output and ongoing missions.")
        transitionTo(ConversationState.INTERRUPTED)

        // Cancel all cancellable missions
        val missionsToCancel = synchronized(missionMutex) {
            _activeMissions.value.values.filter { it.isCancellable }.map { it.missionId }
        }
        missionsToCancel.forEach { cancelMission(it) }

        synchronized(lockMutex) {
            activeLockTag = null
        }
    }

    /**
     * Enforces quiet hours (22:00 to 07:00) so audio is not played out loud at night.
     */
    fun isQuietHours(currentHourOverride: Int? = null): Boolean {
        val hour = currentHourOverride ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 7
    }

    fun canPlayAudioOutLoud(currentHourOverride: Int? = null): Boolean {
        return !isQuietHours(currentHourOverride)
    }

    fun resetForTest() {
        synchronized(stateMutex) {
            _conversationState.value = ConversationState.IDLE
            transitionBuffer.clear()
            transitionSequence.set(0L)
            _transitionHistory.value = emptyList()
        }
        synchronized(missionMutex) {
            _activeMissions.value = emptyMap()
            cancellationCallbacks.clear()
        }
        synchronized(lockMutex) { activeLockTag = null }
    }
}
