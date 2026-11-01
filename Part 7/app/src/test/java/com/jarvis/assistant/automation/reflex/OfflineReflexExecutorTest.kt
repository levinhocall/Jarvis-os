package com.jarvis.assistant.automation.reflex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineReflexExecutorTest {
    @Test
    fun hardwareResponseClassifierDoesNotTreatFailureTextAsSuccess() {
        assertTrue(OfflineReflexExecutor.actionSucceeded("Flashlight turned on."))
        assertTrue(OfflineReflexExecutor.actionSucceeded("Volume set to 50 percent."))
        assertFalse(OfflineReflexExecutor.actionSucceeded("Unable to access the flashlight."))
        assertFalse(OfflineReflexExecutor.actionSucceeded("Please grant System Settings permission in Settings to allow brightness control."))
        assertFalse(OfflineReflexExecutor.actionSucceeded("App not found"))
    }
}
