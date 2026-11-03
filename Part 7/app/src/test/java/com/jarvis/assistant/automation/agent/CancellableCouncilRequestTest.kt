package com.jarvis.assistant.automation.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CancellableCouncilRequestTest {
    private class ControlledCall : Call {
        val started = CompletableDeferred<Unit>()
        var callback: Callback? = null
        var cancelled = false
        override fun request() = Request.Builder().url("https://example.invalid").build()
        override fun execute(): Response = error("Blocking execute must not be used")
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            started.complete(Unit)
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = ControlledCall()
        fun respond(body: String) {
            callback!!.onResponse(this, Response.Builder()
                .request(request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build())
        }
    }

    @Test fun cancellingReviewerCancelsNetworkCallAndToleratesLateFailure() = runBlocking {
        val call = ControlledCall()
        val result = async { call.awaitCouncilResponse() }
        withTimeout(3000) { call.started.await() }
        result.cancelAndJoin()
        assertTrue(call.cancelled)
        call.callback!!.onFailure(call, IOException("late transport callback"))
        assertTrue(result.isCancelled)
    }

    @Test fun successfulResponseReturnsStatusAndBody() = runBlocking {
        val call = ControlledCall()
        val result = async { call.awaitCouncilResponse() }
        withTimeout(3000) { call.started.await() }
        call.respond("review body")
        val response = result.await()
        assertTrue(response.isSuccessful)
        assertEquals("review body", response.body)
    }

    @Test fun rejectsOversizedProviderResponse() = runBlocking {
        val call = ControlledCall()
        val result = async {
            runCatching { call.awaitCouncilResponse() }
        }
        withTimeout(3000) { call.started.await() }
        call.respond("x".repeat(128 * 1024 + 1))
        assertTrue(result.await().exceptionOrNull() is IOException)
    }
}
