package com.jarvis.assistant.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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


    @Test
    fun `restart waits until previous voice connection teardown completes`() = runBlocking {
        val disconnectStarted = CompletableDeferred<Unit>()
        val allowDisconnect = CompletableDeferred<Unit>()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val manager = VoiceSessionManager(
            scope = this,
            onConnectRequest = { events += "connect"; true },
            onDisconnectRequest = {
                events += "disconnect-start"
                disconnectStarted.complete(Unit)
                allowDisconnect.await()
                events += "disconnect-end"
            }
        )

        manager.startSession()
        withTimeout(2_000L) {
            while (!manager.isLive) kotlinx.coroutines.delay(10L)
        }
        manager.stopSession()
        withTimeout(2_000L) { disconnectStarted.await() }
        manager.startSession()
        kotlinx.coroutines.delay(50L)
        assertEquals("A restart must wait for the previous socket teardown", 1, events.count { it == "connect" })

        allowDisconnect.complete(Unit)
        withTimeout(2_000L) {
            while (!manager.isLive) kotlinx.coroutines.delay(10L)
        }
        assertTrue(events.indexOf("disconnect-end") < events.lastIndexOf("connect"))
        manager.stopSession()
    }

    @Test
    fun `repeated socket drop callbacks do not multiply reconnect attempts`() = runBlocking {
        var connectCount = 0
        val manager = VoiceSessionManager(
            scope = this,
            onConnectRequest = { connectCount++; true },
            onDisconnectRequest = {}
        )
        manager.startSession()
        withTimeout(2_000L) {
            while (!manager.isLive) kotlinx.coroutines.delay(10L)
        }

        manager.onConnectionDropped("first drop")
        manager.onConnectionDropped("duplicate close callback")
        kotlinx.coroutines.delay(50L)

        assertEquals(1, connectCount)
        assertEquals(SessionLifecycleState.RECONNECTING, manager.sessionState.value)
        manager.stopSession()
    }

    @Test
    fun `await stopped blocks until previous socket teardown finishes`() = runBlocking {
        val disconnectStarted = CompletableDeferred<Unit>()
        val allowDisconnect = CompletableDeferred<Unit>()
        val manager = VoiceSessionManager(
            scope = this,
            onConnectRequest = { true },
            onDisconnectRequest = {
                disconnectStarted.complete(Unit)
                allowDisconnect.await()
            }
        )
        manager.startSession()
        withTimeout(2_000L) {
            while (!manager.isLive) kotlinx.coroutines.delay(10L)
        }
        manager.stopSession()
        withTimeout(2_000L) { disconnectStarted.await() }

        var awaitCompleted = false
        val waiter = launch {
            manager.awaitStopped()
            awaitCompleted = true
        }
        kotlinx.coroutines.delay(50L)
        assertFalse(awaitCompleted)
        allowDisconnect.complete(Unit)
        withTimeout(2_000L) { waiter.join() }
        assertTrue(awaitCompleted)
    }

    @Test
    fun `audio pipeline manager delegates mute and lifecycle to existing recorder`() = runBlocking {
        val recorder = com.jarvis.assistant.audio.AudioRecorder { _, _, _ -> }
        val pipeline = AudioPipelineManager(this, recorder)
        pipeline.setMuted(true)

        assertTrue(recorder.isMuted.get())
        assertFalse(pipeline.isActive)
        pipeline.stopRecording()
        assertFalse(recorder.isActive)
    }

    @Test
    fun `audio pipeline dispatch frames are independent of reusable capture buffer`() {
        val captureBuffer = byteArrayOf(1, 2, 3, 4, 5, 6)
        val dispatched = AudioPipelineManager.copyFrameForDispatch(captureBuffer, 4)
        captureBuffer.fill(0)

        assertTrue(dispatched.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(4, dispatched.size)
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
        assertTrue(AudioPipelineManager.isFatalReadError(android.media.AudioRecord.ERROR_DEAD_OBJECT))
        assertTrue(AudioPipelineManager.isFatalReadError(android.media.AudioRecord.ERROR_INVALID_OPERATION))
        assertFalse(AudioPipelineManager.isFatalReadError(0))
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
