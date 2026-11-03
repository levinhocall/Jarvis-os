package com.jarvis.assistant.audio

import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class BoundedAudioResponseTest {
    @Test fun acceptsExactLimitAndRejectsEmptyBody() {
        "12345678".toResponseBody().use { assertArrayEquals("12345678".toByteArray(), readBoundedAudio(it, 8)) }
        "".toResponseBody().use { assertNull(readBoundedAudio(it, 8)) }
        assertNull(readBoundedAudio(null, 8))
    }

    @Test fun rejectsOversizedKnownLength() {
        "123456789".toResponseBody().use { assertNull(readBoundedAudio(it, 8)) }
    }

    @Test fun rejectsOversizedChunkedResponseBeforeFullAllocation() {
        val buffer = Buffer().writeUtf8("123456789")
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = buffer
        }
        body.use { assertNull(readBoundedAudio(it, 8)) }
    }
}
