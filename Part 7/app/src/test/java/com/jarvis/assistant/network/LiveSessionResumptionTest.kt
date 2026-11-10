package com.jarvis.assistant.network

import org.junit.Assert.*
import org.junit.Test

class LiveSessionResumptionTest {
    @Test fun resumeHandleSurvivesSameAccountReconnectOnly() {
        val state = LiveSessionResumption()
        assertNull(state.forAccount("first"))
        state.update(true, "opaque-handle")
        assertEquals("opaque-handle", state.forAccount("first"))
        assertNull(state.forAccount("second"))
    }

    @Test fun serverCanRevokeResumabilityAndCannotStoreOversizeHandle() {
        val state = LiveSessionResumption()
        state.forAccount("first")
        state.update(true, "a".repeat(8193))
        assertNull(state.forAccount("first"))
        state.update(true, "valid")
        state.update(false, "invalid")
        assertNull(state.forAccount("first"))
    }
}
