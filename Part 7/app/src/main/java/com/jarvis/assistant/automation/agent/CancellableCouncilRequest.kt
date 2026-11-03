package com.jarvis.assistant.automation.agent

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CouncilHttpResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Cancellation aborts the network exchange, including body reads, before a worker is released. */
internal suspend fun Call.awaitCouncilResponse(): CouncilHttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    val source = it.body?.source()
                    // Reviewer output is small; reject unbounded proxy/provider bodies.
                    if (source != null) {
                        source.request(128 * 1024L + 1)
                        if (source.buffer.size > 128 * 1024L) throw IOException("Council response exceeds limit")
                    }
                    CouncilHttpResponse(it.code, source?.readUtf8().orEmpty())
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
