package com.jarvis.assistant.automation.accessibility
import org.junit.Assert.*
import org.junit.Test
class AccessibilityCallbackFailureTest {
    @Test fun providerFailuresExposeOnlyACategory() {
        assertEquals(AccessibilityIssue.PERMISSION_DENIED,accessibilityCallbackFailure(SecurityException("OTP secret")))
        assertEquals(AccessibilityIssue.STALE_NODE,accessibilityCallbackFailure(IllegalStateException("private message")))
        assertEquals(AccessibilityIssue.INTERNAL,accessibilityCallbackFailure(Exception("private screen")))
        assertFalse(accessibilityCallbackFailure(Exception("private screen")).name.contains("private"))
    }
}
