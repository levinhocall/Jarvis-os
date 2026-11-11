package com.jarvis.assistant.ui.orb

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbVisualStateTest {
    @Test fun lifecycleAndAssistantStatesKeepTheirMeaning() {
        val cases = mapOf("STANDBY" to "sleeping", "CONNECTING" to "waking", "SPEAKING" to "speaking", "EXECUTING" to "executing", "INTERRUPTED" to "interrupted", "FAILED" to "error", "COMPLETED" to "success", "CONNECTED" to "listening", "RECOVERING" to "thinking")
        cases.forEach { (input, expected) -> assertEquals(expected, OrbVisualState.fromSource(input)) }
        assertEquals("idle", OrbVisualState.fromSource("unknown"))
    }
}
