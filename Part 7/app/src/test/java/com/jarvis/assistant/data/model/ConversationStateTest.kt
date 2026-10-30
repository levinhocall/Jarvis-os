package com.jarvis.assistant.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationStateTest {

    @Test
    fun connectionStatusesMapToLifecycleStates() {
        assertEquals(ConversationState.CONNECTING, ConversationState.fromConnectionStatus("CONNECTING..."))
        assertEquals(ConversationState.CONNECTING, ConversationState.fromConnectionStatus("CONFIGURING..."))
        assertEquals(ConversationState.CONNECTING, ConversationState.fromConnectionStatus("RECONNECTING..."))
        assertEquals(ConversationState.CONNECTED, ConversationState.fromConnectionStatus("LIVE"))
        assertEquals(ConversationState.CONNECTED, ConversationState.fromConnectionStatus("connected"))
        assertEquals(ConversationState.RECOVERING, ConversationState.fromConnectionStatus("COOLDOWN • RETRYING"))
        assertEquals(ConversationState.ERROR, ConversationState.fromConnectionStatus("ERROR • INVALID KEY"))
    }

    @Test
    fun unrelatedTransportStatusesDoNotOverwriteConversationState() {
        assertNull(ConversationState.fromConnectionStatus("OFFLINE"))
        assertNull(ConversationState.fromConnectionStatus("unknown"))
        assertNull(ConversationState.fromConnectionStatus(""))
    }

    @Test
    fun legacyStatesRemainAvailableForExistingUiAndAutomation() {
        assertEquals("IDLE", ConversationState.IDLE.displayName)
        assertEquals("LISTENING", ConversationState.LISTENING.displayName)
        assertEquals("SPEAKING", ConversationState.SPEAKING.displayName)
        assertEquals("FAILED", ConversationState.FAILED.displayName)
    }
}
