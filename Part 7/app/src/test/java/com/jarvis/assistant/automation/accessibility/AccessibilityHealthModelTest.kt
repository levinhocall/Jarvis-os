package com.jarvis.assistant.automation.accessibility
import org.junit.Assert.*
import org.junit.Test
class AccessibilityHealthModelTest {
    @Test fun enumWriteCannotManufactureAPlatformConnection() {
        AccessibilityHealthModel.updateState(AccessibilityState.DISABLED)
        AccessibilityHealthModel.updateState(AccessibilityState.CONNECTED)
        assertFalse(AccessibilityHealthModel.isOperational())
        assertEquals(AccessibilityState.DISABLED,AccessibilityHealthModel.state.value)
    }
    @Test fun enabledButDisconnectedHasActionableGuidance() {
        AccessibilityHealthModel.updateState(AccessibilityState.ENABLED_NOT_CONNECTED)
        assertFalse(AccessibilityHealthModel.isOperational())
        assertTrue(AccessibilityHealthModel.isConnecting())
        assertTrue(AccessibilityHealthModel.getRecoveryGuidance()!!.contains("hasn't connected"))
        AccessibilityHealthModel.updateState(AccessibilityState.DISABLED)
    }
}
