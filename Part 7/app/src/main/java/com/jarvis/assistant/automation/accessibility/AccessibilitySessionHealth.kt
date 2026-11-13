package com.jarvis.assistant.automation.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccessibilityState {
    DISABLED, ENABLED_NOT_CONNECTED, CONNECTING, CONNECTED, READY, BUSY, DEGRADED, DISCONNECTED, ERROR
}

enum class AccessibilityIssue {
    NONE, NO_ROOT, EMPTY_TREE, STALE_NODE, PROTECTED_SCREEN, GESTURE_CANCELLED,
    GESTURE_REJECTED, ACTION_TIMEOUT, QUEUE_OVERLOAD, INTERRUPTED, SESSION_CLOSED, PERMISSION_DENIED, UNEXPECTED_SCREEN, RECOVERY_REQUESTED, INTERNAL
}

data class AccessibilityHealthSnapshot(
    val state: AccessibilityState = AccessibilityState.DISABLED,
    val token: Long = 0,
    val connected: Boolean = false,
    val stateChangedAt: Long = 0,
    val events: Long = 0,
    val lastEventAt: Long = 0,
    val lastSnapshotAt: Long = 0,
    val lastActionAt: Long = 0,
    val lastGestureAt: Long = 0,
    val lastVerifiedAt: Long = 0,
    val operationStartedAt: Long = 0,
    val pending: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastIssue: AccessibilityIssue = AccessibilityIssue.NONE,
    val lastIssueAt: Long = 0,
    val lastAction: String? = null,
    val lastOperationDurationMs: Long = 0,
    val recoveryAttempts: Int = 0,
    val lastRecoveryAt: Long = 0,
    val recent: List<AccessibilityDiagnostic> = emptyList()
)

data class AccessibilityDiagnostic(val elapsedMs: Long, val category: AccessibilityIssue, val state: AccessibilityState, val durationMs: Long? = null)

/** Reducer for the existing health facade. No Android objects, polling, restarts or raw screen text. */
internal class AccessibilitySessionHealth(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(AccessibilityHealthSnapshot())
    val snapshots = mutable.asStateFlow()
    private var nextToken = 0L

    @Synchronized fun connected(): Long {
        val token = ++nextToken
        mutable.value = AccessibilityHealthSnapshot(state = AccessibilityState.CONNECTED, token = token,
            connected = true, stateChangedAt = now())
        return token
    }

    @Synchronized fun reconcile(enabled: Boolean, liveToken: Long?) {
        val s = mutable.value
        if (!enabled) {
            if (s.connected) disconnected(s.token)
            publish(mutable.value.copy(state = AccessibilityState.DISABLED, connected = false, pending = 0, operationStartedAt = 0))
            return
        }
        if (liveToken == s.token && s.connected) return // A UI refresh is not a successful probe.
        if (s.connected) disconnected(s.token)
        val current = mutable.value
        publish(current.copy(state = if (enabled) AccessibilityState.ENABLED_NOT_CONNECTED else AccessibilityState.DISABLED,
            connected = false, pending = 0, operationStartedAt = 0))
    }

    @Synchronized fun connecting() {
        if (!mutable.value.connected) publish(mutable.value.copy(state = AccessibilityState.CONNECTING))
    }

    @Synchronized fun disconnected(token: Long) {
        if (!owns(token)) return
        publish(mutable.value.copy(connected = false, pending = 0, operationStartedAt = 0,
            state = AccessibilityState.DISCONNECTED), AccessibilityIssue.SESSION_CLOSED)
    }

    @Synchronized fun event(token: Long) {
        if (!owns(token)) return
        val s = mutable.value
        // Idle or active events alone cannot heal a failed snapshot/action.
        mutable.value = s.copy(events = s.events + 1, lastEventAt = now())
    }

    @Synchronized fun snapshot(token: Long, readable: Boolean, issue: AccessibilityIssue = AccessibilityIssue.NO_ROOT) {
        if (!owns(token)) return
        val s = mutable.value
        if (readable) publish(s.copy(lastSnapshotAt = now(),
            lastIssue = if (s.consecutiveFailures > 0) s.lastIssue else AccessibilityIssue.NONE,
            state = if (s.consecutiveFailures > 0) AccessibilityState.DEGRADED else if (s.pending > 0) AccessibilityState.BUSY else AccessibilityState.READY))
        else if (s.state != AccessibilityState.ERROR) publish(s.copy(state = AccessibilityState.DEGRADED), issue)
    }

    @Synchronized fun queued(token: Long, pending: Int, running: Boolean) {
        if (!owns(token)) return
        val s = mutable.value
        publish(s.copy(pending = pending.coerceAtLeast(0),
            operationStartedAt = if (running) s.operationStartedAt.takeIf { it > 0 } ?: now() else 0,
            state = when {
                s.state == AccessibilityState.ERROR -> AccessibilityState.ERROR
                running -> AccessibilityState.BUSY
                s.lastIssue != AccessibilityIssue.NONE -> AccessibilityState.DEGRADED
                s.lastSnapshotAt > 0 -> AccessibilityState.READY
                else -> AccessibilityState.CONNECTED
            }))
    }

    @Synchronized fun action(token: Long, success: Boolean, category: String, verified: Boolean = false) {
        if (!owns(token)) return
        val s = mutable.value
        // Accept only fixed operation categories, never caller-provided labels/arguments.
        val safe = category.takeIf { it in setOf("tap", "scroll", "type", "key", "gesture", "verified", "ui") } ?: "ui"
        if (success) publish(s.copy(lastActionAt = now(), lastAction = safe,
            lastVerifiedAt = if (verified) now() else s.lastVerifiedAt,
            consecutiveFailures = if (s.state == AccessibilityState.ERROR) s.consecutiveFailures else 0,
            lastIssue = if (s.state == AccessibilityState.ERROR) s.lastIssue else AccessibilityIssue.NONE,
            state = if (s.state == AccessibilityState.ERROR) AccessibilityState.ERROR else if (s.pending > 0) AccessibilityState.BUSY else if (s.lastSnapshotAt > 0) AccessibilityState.READY else AccessibilityState.CONNECTED))
        else issue(token, AccessibilityIssue.INTERNAL, actionFailure = true)
    }

    @Synchronized fun gesture(token: Long, completed: Boolean) {
        if (!owns(token)) return
        mutable.value = mutable.value.copy(lastGestureAt = now())
        // A completed gesture is not a verified app-side result.
        if (!completed) issue(token, AccessibilityIssue.GESTURE_CANCELLED, true)
    }

    @Synchronized fun issue(token: Long, issue: AccessibilityIssue, actionFailure: Boolean = false, fatal: Boolean = false) {
        if (!owns(token)) return
        val s = mutable.value
        publish(s.copy(consecutiveFailures = s.consecutiveFailures + if (actionFailure) 1 else 0,
            state = if (fatal || s.state == AccessibilityState.ERROR) AccessibilityState.ERROR else AccessibilityState.DEGRADED),
            if (s.state == AccessibilityState.ERROR && !fatal) s.lastIssue else issue)
    }

    @Synchronized fun duration(token: Long, elapsedMs: Long) {
        if (!owns(token)) return
        val s = mutable.value
        mutable.value = s.copy(lastOperationDurationMs = elapsedMs.coerceAtLeast(0), operationStartedAt = 0,
            recent = (s.recent + AccessibilityDiagnostic(now(), AccessibilityIssue.NONE, s.state, elapsedMs.coerceAtLeast(0))).takeLast(32))
    }

    @Synchronized fun recovery(token: Long) {
        if (!owns(token)) return
        val s = mutable.value
        publish(s.copy(recoveryAttempts = (s.recoveryAttempts.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            lastRecoveryAt = now()), if (s.state == AccessibilityState.ERROR) s.lastIssue else AccessibilityIssue.RECOVERY_REQUESTED)
    }

    @Synchronized fun owns(token: Long): Boolean = token != 0L && mutable.value.connected && mutable.value.token == token

    private fun publish(next: AccessibilityHealthSnapshot, issue: AccessibilityIssue = AccessibilityIssue.NONE) {
        val time = now()
        val transitioned = next.state != mutable.value.state
        mutable.value = next.copy(
            stateChangedAt = if (transitioned) time else next.stateChangedAt,
            lastIssue = if (issue != AccessibilityIssue.NONE) issue else next.lastIssue,
            lastIssueAt = if (issue != AccessibilityIssue.NONE) time else next.lastIssueAt,
            recent = if (transitioned || issue != AccessibilityIssue.NONE)
                (next.recent + AccessibilityDiagnostic(time, issue, next.state)).takeLast(32) else next.recent)
    }
}
