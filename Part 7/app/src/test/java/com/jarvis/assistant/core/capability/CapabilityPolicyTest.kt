package com.jarvis.assistant.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityPolicyTest {
    @Test fun nativePathIsPreferredForBrightness() {
        assertEquals(
            ExecutionPath.ANDROID_API,
            CapabilityPolicy.preferredPath(
                DeviceCapability.SET_BRIGHTNESS,
                CapabilityAvailability(setOf(ExecutionPath.ROOT, ExecutionPath.ANDROID_API))
            )
        )
    }

    @Test fun accessibilityIsPreferredForUiAutomation() {
        assertEquals(
            ExecutionPath.ACCESSIBILITY,
            CapabilityPolicy.preferredPath(
                DeviceCapability.UI_INTERACTION,
                CapabilityAvailability(setOf(ExecutionPath.ACCESSIBILITY, ExecutionPath.ADB))
            )
        )
    }

    @Test fun unavailableCapabilityDoesNotFallbackToUnsupportedPath() {
        assertNull(
            CapabilityPolicy.preferredPath(
                DeviceCapability.PRIVILEGED_SHELL,
                CapabilityAvailability(setOf(ExecutionPath.ANDROID_API))
            )
        )
    }

    @Test fun noShizukuExecutionPathExists() {
        assertFalse(ExecutionPath.entries.any { it.name.contains("SHIZUKU", ignoreCase = true) })
    }
}
