package com.jarvis.assistant.automation.accessibility

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AccessibilityHealthModelTest {

    @Before
    fun setUp() {
        // Reset to DISABLED before each test
        AccessibilityHealthModel.updateState(AccessibilityState.DISABLED, null)
    }

    @Test
    fun testInitialStateIsDisabled() {
        assertEquals(AccessibilityState.DISABLED, AccessibilityHealthModel.state.value)
        assertFalse(AccessibilityHealthModel.isOperational())
        assertNotNull(AccessibilityHealthModel.getRecoveryGuidance())
        assertTrue(AccessibilityHealthModel.getRecoveryGuidance()!!.contains("disabled", ignoreCase = true))
    }

    @Test
    fun testConnectedStateIsOperational() {
        AccessibilityHealthModel.updateState(AccessibilityState.CONNECTED)
        assertEquals(AccessibilityState.CONNECTED, AccessibilityHealthModel.state.value)
        assertTrue(AccessibilityHealthModel.isOperational())
        assertNull(AccessibilityHealthModel.getRecoveryGuidance())
    }

    @Test
    fun testDegradedStateIsOperationalWithGuidance() {
        AccessibilityHealthModel.updateState(AccessibilityState.DEGRADED, "Gesture dispatch rejected")
        assertEquals(AccessibilityState.DEGRADED, AccessibilityHealthModel.state.value)
        assertEquals("Gesture dispatch rejected", AccessibilityHealthModel.lastError.value)
        // Degraded is considered operational (best-effort)
        assertTrue(AccessibilityHealthModel.isOperational())
    }

    @Test
    fun testDisconnectedStateIsNotOperational() {
        AccessibilityHealthModel.updateState(AccessibilityState.CONNECTED)
        assertTrue(AccessibilityHealthModel.isOperational())

        AccessibilityHealthModel.updateState(AccessibilityState.DISCONNECTED, "Service unbound")
        assertEquals(AccessibilityState.DISCONNECTED, AccessibilityHealthModel.state.value)
        assertFalse(AccessibilityHealthModel.isOperational())
        assertNotNull(AccessibilityHealthModel.getRecoveryGuidance())
        assertTrue(AccessibilityHealthModel.getRecoveryGuidance()!!.contains("disconnected", ignoreCase = true))
    }

    @Test
    fun testErrorStateContainsLastErrorMessage() {
        AccessibilityHealthModel.updateState(AccessibilityState.ERROR, "Unexpected crash in event pipeline")
        assertEquals(AccessibilityState.ERROR, AccessibilityHealthModel.state.value)
        assertFalse(AccessibilityHealthModel.isOperational())
        val guidance = AccessibilityHealthModel.getRecoveryGuidance()
        assertNotNull(guidance)
        assertTrue(guidance!!.contains("Unexpected crash in event pipeline"))
    }

    @Test
    fun testStateTransitionTimestampUpdates() {
        val initialTimestamp = AccessibilityHealthModel.lastStateChange.value
        Thread.sleep(10)
        AccessibilityHealthModel.updateState(AccessibilityState.CONNECTED)
        assertTrue(AccessibilityHealthModel.lastStateChange.value >= initialTimestamp)
    }

    @Test
    fun testDuplicateTransitionSuppressed() {
        AccessibilityHealthModel.updateState(AccessibilityState.CONNECTED, null)
        val timestampAfterFirst = AccessibilityHealthModel.lastStateChange.value
        Thread.sleep(10)
        // Transition to same state with same error
        AccessibilityHealthModel.updateState(AccessibilityState.CONNECTED, null)
        assertEquals(timestampAfterFirst, AccessibilityHealthModel.lastStateChange.value)
    }

    @Test
    fun testEnabledStateProvidesGuidance() {
        AccessibilityHealthModel.updateState(AccessibilityState.ENABLED)
        assertEquals(AccessibilityState.ENABLED, AccessibilityHealthModel.state.value)
        assertFalse(AccessibilityHealthModel.isOperational())
        assertTrue(AccessibilityHealthModel.isConnecting())
        val guidance = AccessibilityHealthModel.getRecoveryGuidance()
        assertNotNull(guidance)
        assertTrue(guidance!!.contains("hasn't connected yet", ignoreCase = true))
    }

    @Test
    fun testIsConnectingReturnsTrueOnlyWhenEnabled() {
        AccessibilityHealthModel.updateState(AccessibilityState.DISABLED)
        assertFalse(AccessibilityHealthModel.isConnecting())

        AccessibilityHealthModel.updateState(AccessibilityState.ENABLED)
        assertTrue(AccessibilityHealthModel.isConnecting())

        AccessibilityHealthModel.updateState(AccessibilityState.CONNECTED)
        assertFalse(AccessibilityHealthModel.isConnecting())

        AccessibilityHealthModel.updateState(AccessibilityState.DEGRADED)
        assertFalse(AccessibilityHealthModel.isConnecting())

        AccessibilityHealthModel.updateState(AccessibilityState.DISCONNECTED)
        assertFalse(AccessibilityHealthModel.isConnecting())
    }
}
