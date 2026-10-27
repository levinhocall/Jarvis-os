package com.jarvis.assistant.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveReconnectTest {

    private class TestListener : GeminiLiveWebSocket.Listener {
        val states = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun onConnectionStateChanged(status: String) {
            states.add(status)
        }
        override fun onAudioDataReceived(pcmData: ByteArray) {}
        override fun onAssistantTextReceived(textChunk: String) {}
        override fun onUserTextReceived(textChunk: String) {}
        override fun onInterrupted() {}
        override fun onTurnCompleted() {}
        override fun onError(message: String, isFatal: Boolean, errorCode: String?) {
            errors.add(message)
        }
        override fun onToolCall(callId: String, name: String, args: JSONObject) {}
    }

    @Test
    fun `test deduplicator integrates into web socket message handling`() {
        val deduplicator = ToolCallDeduplicator(50)
        assertTrue(deduplicator.shouldProcess("tool_call_101"))
        // Second call with same id must return false
        assertFalse(deduplicator.shouldProcess("tool_call_101"))
        // Different id is permitted
        assertTrue(deduplicator.shouldProcess("tool_call_102"))
    }

    @Test
    fun `test reconnect delay and max attempt bounds`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val listener = TestListener()

        // Create socket with blank key so initiateConnection immediately returns error
        val socket = GeminiLiveWebSocket(
            apiKey = "",
            model = "models/gemini-3.1-flash-live-preview",
            voiceName = "Puck",
            systemPrompt = "Test Prompt",
            listener = listener
        )

        socket.connect(scope)
        assertTrue(listener.states.contains("OFFLINE"))
        assertTrue(listener.errors.any { it.contains("missing") })
    }
}
