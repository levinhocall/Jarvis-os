package com.jarvis.assistant.network

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GeminiTransportEpochTest {
    private class Socket(private val request: Request) : WebSocket {
        var closed = false
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
    }

    private class Factory : WebSocket.Factory {
        val sockets = mutableListOf<Socket>()
        val listeners = mutableListOf<WebSocketListener>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            listeners.add(listener)
            return Socket(request).also { sockets.add(it) }
        }
    }

    private class Listener : GeminiLiveWebSocket.Listener {
        val statuses = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun onConnectionStateChanged(status: String) { statuses.add(status) }
        override fun onError(message: String, isFatal: Boolean, errorCode: String?) { errors.add(message) }
        override fun onAudioDataReceived(pcmData: ByteArray) = Unit
        override fun onAssistantTextReceived(textChunk: String) = Unit
        override fun onUserTextReceived(textChunk: String) = Unit
        override fun onInterrupted() = Unit
        override fun onTurnCompleted() = Unit
        override fun onToolCall(callId: String, name: String, args: JSONObject) = Unit
    }

    @Test fun oldFailureAndCloseCannotDisconnectReplacement() = runBlocking {
        val factory = Factory()
        val listener = Listener()
        val transport = GeminiLiveWebSocket("test-key", "models/test-live", "Aoede", "test", listener,
            externalReconnectOwner = true, socketFactory = factory)
        transport.connect(this)
        transport.disconnect()
        transport.connect(this)
        listener.statuses.clear()
        factory.listeners[0].onClosed(factory.sockets[0], 1000, "late close")
        factory.listeners[0].onFailure(factory.sockets[0], IOException("late failure"), null)
        assertTrue(listener.statuses.isEmpty())
        assertTrue(listener.errors.isEmpty())
        factory.listeners[1].onFailure(factory.sockets[1], IOException("current failure"), null)
        assertFalse(listener.errors.isEmpty())
        assertFalse(listener.statuses.isEmpty())
        transport.disconnect()
    }

    @Test fun handshakeArrivingAfterStopClosesItsOwnSocket() = runBlocking {
        val factory = Factory()
        val listener = Listener()
        val transport = GeminiLiveWebSocket("test-key", "models/test-live", "Aoede", "test", listener,
            externalReconnectOwner = true, socketFactory = factory)
        transport.connect(this)
        transport.disconnect()
        listener.statuses.clear()
        val socket = factory.sockets.single()
        val response = Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build()
        factory.listeners.single().onOpen(socket, response)
        assertTrue(socket.closed)
        assertTrue(listener.statuses.isEmpty())
        assertFalse(transport.isSessionReady())
    }

    @Test fun connectionUsesTheAccountWhoseModelWasDiscovered() = runBlocking {
        val factory = Factory()
        val transport = GeminiLiveWebSocket("initial-test-key", "models/test-live", "Aoede", "test", Listener(),
            apiKeyProvider = { "different-test-key" }, externalReconnectOwner = true, socketFactory = factory)
        transport.connect(this)
        assertEquals("initial-test-key", factory.sockets.single().request().header("x-goog-api-key"))
        transport.disconnect()
    }

    @Test fun quotaRotationDiscoversTheNewAccountBeforeOpeningSocket() = runBlocking {
        val factory = Factory()
        val transport = GeminiLiveWebSocket("initial-test-key", "models/test-live", "Aoede", "test", Listener(),
            apiKeyProvider = { "alternate-test-key" }, externalReconnectOwner = true, socketFactory = factory)
        transport.connect(this)
        val response = Response.Builder().request(factory.sockets[0].request()).protocol(Protocol.HTTP_1_1)
            .code(429).message("Too Many Requests").body("quota".let { okhttp3.ResponseBody.create(null, it) }).build()
        factory.listeners[0].onFailure(factory.sockets[0], IOException("quota"), response)
        transport.connectDiscovered(this) { key ->
            assertEquals("alternate-test-key", key)
            assertEquals(1, factory.sockets.size)
            "models/alternate-test-live"
        }
        assertEquals("alternate-test-key", factory.sockets.last().request().header("x-goog-api-key"))
        transport.disconnect()
    }
}
