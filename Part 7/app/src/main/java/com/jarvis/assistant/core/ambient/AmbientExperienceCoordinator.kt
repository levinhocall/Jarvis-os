package com.jarvis.assistant.core.ambient

import android.util.Log
import com.jarvis.assistant.data.model.ConversationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

object AmbientExperienceCoordinator {
    private const val TAG = "AmbientExperienceCoord"

    private val _conversationState = MutableStateFlow(ConversationState.IDLE)
    val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

    private val _activeMissions = MutableStateFlow<Map<String, OngoingMissionInfo>>(emptyMap())
    val activeMissions: StateFlow<Map<String, OngoingMissionInfo>> = _activeMissions.asStateFlow()

    private val cancellationCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private var activeLockTag: String? = null
    private val lockMutex = Any()

    /**
     * Atomically transitions assistant state.
     */
    fun transitionTo(newState: ConversationState) {
        val oldState = _conversationState.value
        if (oldState == newState) return

        _conversationState.value = newState
        Log.i(TAG, "Unified state transitioned: $oldState -> $newState")
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
        val current = _activeMissions.value.toMutableMap()
        current[info.missionId] = info
        _activeMissions.value = current

        if (onCancel != null) {
            cancellationCallbacks[info.missionId] = onCancel
        }
        Log.i(TAG, "Mission registered: ${info.missionId} ('${info.title}')")
    }

    fun updateMissionProgress(
        missionId: String,
        currentStepDescription: String,
        progressFraction: Float
    ) {
        val current = _activeMissions.value.toMutableMap()
        val existing = current[missionId] ?: return
        current[missionId] = existing.copy(
            currentStepDescription = currentStepDescription,
            progressFraction = progressFraction.coerceIn(0.0f, 1.0f)
        )
        _activeMissions.value = current
    }

    /**
     * Cancels an ongoing mission, invokes its cancellation hook, and removes it from active list.
     */
    fun cancelMission(missionId: String): Boolean {
        val current = _activeMissions.value.toMutableMap()
        val mission = current.remove(missionId)
        _activeMissions.value = current

        val callback = cancellationCallbacks.remove(missionId)
        callback?.invoke()

        if (activeLockTag == missionId) {
            releaseExecutionLock(missionId)
        }

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
        val missionsToCancel = _activeMissions.value.values.filter { it.isCancellable }.map { it.missionId }
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
        _conversationState.value = ConversationState.IDLE
        _activeMissions.value = emptyMap()
        cancellationCallbacks.clear()
        synchronized(lockMutex) {
            activeLockTag = null
        }
    }
}
