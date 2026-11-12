package com.jarvis.assistant.automation.accessibility

import org.junit.Assert.*
import org.junit.Test

class AccessibilityGestureGateTest {
    @Test fun overlapIsRejectedAndCancellationReleasesAdmission() {
        val g = AccessibilityGestureGate(); val first = g.begin()!!
        assertNull(g.begin()); assertTrue(g.finish(first)); assertNotNull(g.begin())
    }
    @Test fun unknownTimeoutCannotTriggerBlindReplay() {
        val g = AccessibilityGestureGate(); val first = g.begin()!!
        assertTrue(g.isActive(first)); assertNull(g.begin())
        g.reset() // Explicit recovery/session replacement, not an automatic retry.
        assertNotNull(g.begin())
    }
    @Test fun lateCallbackCannotFinishReplacement() {
        val g = AccessibilityGestureGate(); val old = g.begin()!!
        g.reset(); val fresh = g.begin()!!
        assertFalse(g.finish(old)); assertTrue(g.isActive(fresh)); assertTrue(g.finish(fresh))
    }
    @Test fun duplicateCallbackIsIgnored() {
        val g = AccessibilityGestureGate(); val t = g.begin()!!
        assertTrue(g.finish(t)); assertFalse(g.finish(t))
    }
}
