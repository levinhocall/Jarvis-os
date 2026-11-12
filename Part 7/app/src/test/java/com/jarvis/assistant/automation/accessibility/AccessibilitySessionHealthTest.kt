package com.jarvis.assistant.automation.accessibility

import org.junit.Assert.*
import org.junit.Test

class AccessibilitySessionHealthTest {
    @Test fun enabledIsNotConnectedAndRebindIsNotReady() {
        val h = AccessibilitySessionHealth { 10 }
        h.reconcile(true, null)
        assertFalse(h.snapshots.value.connected)
        assertEquals(AccessibilityState.ENABLED_NOT_CONNECTED, h.snapshots.value.state)
        h.connecting()
        assertEquals(AccessibilityState.CONNECTING, h.snapshots.value.state)
        val token = h.connected()
        assertTrue(h.owns(token))
        assertEquals(AccessibilityState.CONNECTED, h.snapshots.value.state)
    }
    @Test fun longIdleDoesNotInvalidateReadinessAndEventsDoNotHealErrors() {
        var now = 1L
        val h = AccessibilitySessionHealth { now }; val t = h.connected()
        h.snapshot(t, true); now += 86_400_000
        h.reconcile(true, t)
        assertEquals(AccessibilityState.READY, h.snapshots.value.state)
        h.issue(t, AccessibilityIssue.INTERNAL, fatal = true)
        h.event(t); h.reconcile(true, t)
        assertEquals(AccessibilityState.ERROR, h.snapshots.value.state)
        h.snapshot(t, true)
        assertEquals(AccessibilityState.READY, h.snapshots.value.state)
    }
    @Test fun missingAndEmptyRootsAreRecoverableNotFakeReady() {
        val h = AccessibilitySessionHealth { 1 }; val t = h.connected()
        for (issue in listOf(AccessibilityIssue.NO_ROOT, AccessibilityIssue.EMPTY_TREE, AccessibilityIssue.STALE_NODE)) {
            h.snapshot(t, false, issue)
            assertEquals(AccessibilityState.DEGRADED, h.snapshots.value.state)
            assertEquals(issue, h.snapshots.value.lastIssue)
            assertEquals(0, h.snapshots.value.consecutiveFailures)
        }
        h.snapshot(t, true)
        assertEquals(AccessibilityState.READY, h.snapshots.value.state)
    }
    @Test fun staleLifecycleAndCallbacksCannotAffectReplacement() {
        val h = AccessibilitySessionHealth { 1 }; val old = h.connected()
        h.disconnected(old); val fresh = h.connected()
        h.snapshot(fresh, true)
        h.disconnected(old); h.issue(old, AccessibilityIssue.INTERNAL, fatal = true); h.event(old)
        h.action(old, true, "verified", true)
        assertEquals(AccessibilityState.READY, h.snapshots.value.state)
        assertEquals(0L, h.snapshots.value.events)
        assertEquals(0L, h.snapshots.value.lastVerifiedAt)
    }
    @Test fun operationDurationPendingAndDisconnectAreOwned() {
        var now = 5L; val h = AccessibilitySessionHealth { now }; val t = h.connected()
        h.queued(t, 3, true)
        now += 90; h.queued(t, 2, true)
        assertEquals(5L, h.snapshots.value.operationStartedAt)
        assertEquals(AccessibilityState.BUSY, h.snapshots.value.state)
        h.disconnected(t)
        assertEquals(0, h.snapshots.value.pending)
        assertEquals(0L, h.snapshots.value.operationStartedAt)
        assertFalse(h.owns(t))
    }
    @Test fun diagnosticsAreBoundedAndNeverContainCallerText() {
        val h = AccessibilitySessionHealth { 1 }; val t = h.connected()
        repeat(1000) { h.issue(t, AccessibilityIssue.ACTION_TIMEOUT, true) }
        h.action(t, true, "private OTP 123456")
        assertEquals(32, h.snapshots.value.recent.size)
        assertEquals("ui", h.snapshots.value.lastAction)
        assertFalse(h.snapshots.value.toString().contains("123456"))
    }
    @Test fun gestureCompletionIsNotActionVerification() {
        val h = AccessibilitySessionHealth { 1 }; val t = h.connected()
        h.gesture(t, true)
        assertEquals(0L, h.snapshots.value.lastVerifiedAt)
        h.action(t, true, "verified", true)
        assertEquals(1L, h.snapshots.value.lastVerifiedAt)
    }
    @Test fun permissionOffInvalidatesAnOtherwiseLiveToken() {
        val h=AccessibilitySessionHealth{1};val token=h.connected();h.snapshot(token,true)
        h.reconcile(false,token)
        assertEquals(AccessibilityState.DISABLED,h.snapshots.value.state)
        assertFalse(h.owns(token));h.event(token)
        assertEquals(0L,h.snapshots.value.events)
    }
    @Test fun readableScreenAloneDoesNotProveAFailedActionRecovered() {
        val h=AccessibilitySessionHealth{1};val token=h.connected()
        h.issue(token,AccessibilityIssue.GESTURE_CANCELLED,actionFailure=true)
        h.snapshot(token,true)
        assertEquals(AccessibilityState.DEGRADED,h.snapshots.value.state)
        h.action(token,true,"verified",true)
        assertEquals(AccessibilityState.READY,h.snapshots.value.state)
    }

    @Test fun cleanupAndNullProbesCannotHidePermissionError() {
        val h = AccessibilitySessionHealth { 5 }; val t = h.connected()
        h.issue(t, AccessibilityIssue.PERMISSION_DENIED, fatal = true)
        h.queued(t, 1, true); h.queued(t, 0, false)
        h.snapshot(t, false, AccessibilityIssue.NO_ROOT)
        h.issue(t, AccessibilityIssue.SESSION_CLOSED)
        h.recovery(t); h.action(t, true, "gesture")
        assertEquals(AccessibilityState.ERROR, h.snapshots.value.state)
        assertEquals(AccessibilityIssue.PERMISSION_DENIED, h.snapshots.value.lastIssue)
        assertEquals(1, h.snapshots.value.recoveryAttempts)
        // A real readable probe is evidence; cleanup, queued work and events are not.
        h.snapshot(t, true)
        assertEquals(AccessibilityState.READY, h.snapshots.value.state)
    }

    @Test fun durationAndRecoveryAreBoundedMetadataAndIgnoreStaleOwners() {
        var now = 10L; val h = AccessibilitySessionHealth { now }; val old = h.connected()
        h.recovery(old); now = 40; h.duration(old, 30)
        assertEquals(30L, h.snapshots.value.lastOperationDurationMs)
        assertEquals(10L, h.snapshots.value.lastRecoveryAt)
        h.disconnected(old); val fresh = h.connected()
        h.duration(old, 999); h.recovery(old)
        assertEquals(0L, h.snapshots.value.lastOperationDurationMs)
        assertEquals(0, h.snapshots.value.recoveryAttempts)
        h.duration(fresh, -1)
        assertEquals(0L, h.snapshots.value.lastOperationDurationMs)
    }

}
