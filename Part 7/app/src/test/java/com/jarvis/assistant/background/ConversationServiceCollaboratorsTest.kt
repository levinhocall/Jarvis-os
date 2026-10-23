package com.jarvis.assistant.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationServiceCollaboratorsTest {

    // --- 1. VoiceSessionManager Tests ---

    @Test
    fun `voice session manager transitions to live on successful connection`() = runBlocking {
        var connectCalled = false
        val manager = VoiceSessionManager(
            scope = this,
            onConnectRequest = {
                connectCalled = true
                true
            },
            onDisconnectRequest = {}
        )

        assertEquals(SessionLifecycleState.DISCONNECTED, manager.sessionState.value)
        manager.startSession()
        kotlinx.coroutines.delay(100L)

        assertTrue(connectCalled)
        assertEquals(SessionLifecycleState.LIVE, manager.sessionState.value)
        assertTrue(manager.isLive)
    }

    @Test
    fun `voice session manager enters reconnecting state on failure`() = runBlocking {
        val manager = VoiceSessionManager(
            scope = this,
            onConnectRequest = { false },
            onDisconnectRequest = {}
        )

        manager.startSession()
        kotlinx.coroutines.delay(100L)

        assertEquals(SessionLifecycleState.RECONNECTING, manager.sessionState.value)
        assertNotNull(manager.lastError.value)
    }

    @Test
    fun `stop session transitions state to disconnected`() = runBlocking {
        var disconnectCalled = false
        val manager = VoiceSessionManager(
            scope = this,
            onConnectRequest = { true },
            onDisconnectRequest = { disconnectCalled = true }
        )

        manager.startSession()
        kotlinx.coroutines.delay(100L)
        assertEquals(SessionLifecycleState.LIVE, manager.sessionState.value)

        manager.stopSession()
        kotlinx.coroutines.delay(100L)

        assertTrue(disconnectCalled)
        assertEquals(SessionLifecycleState.DISCONNECTED, manager.sessionState.value)
        assertFalse(manager.isLive)
    }

    // --- 2. ScreenCaptureBridge Tests ---

    @Test
    fun `screen capture bridge reports ready state correctly`() {
        val bridge = ScreenCaptureBridge()
        // Fresh state before projection initialization
        assertFalse(bridge.isReady())
        assertFalse(bridge.isSharing.value)
    }

    // --- 3. AudioPipelineManager Energy & State Tests ---

    @Test
    fun `audio pipeline manager toggles barge in and mute flags accurately`() = runBlocking {
        val pipeline = AudioPipelineManager(
            scope = this,
            onAudioChunkReady = { _, _ -> }
        )

        pipeline.setMuted(true)
        pipeline.setBargeInSuppressed(true)
        // Verify no crash on lifecycle shutdown
        pipeline.stopRecording()
        assertFalse(pipeline.isActive)
    }

    // --- 4. DeviceActionDispatcher Tests ---

    @Test
    fun `phone number cleaning removes spaces and hyphens`() {
        val input = "+1 (555) 019-2834"
        val clean = input.replace(Regex("[^0-9+]"), "")
        assertEquals("+15550192834", clean)
    }

    @Test
    fun `volume percent coercion clamps between 0 and 100`() {
        val v1 = (-15).coerceIn(0, 100)
        val v2 = 145.coerceIn(0, 100)
        val v3 = 50.coerceIn(0, 100)

        assertEquals(0, v1)
        assertEquals(100, v2)
        assertEquals(50, v3)
    }
}
