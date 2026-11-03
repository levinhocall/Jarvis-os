package com.jarvis.assistant.audio

import okhttp3.ResponseBody

/** Bound bytes before allocation, including chunked responses without Content-Length. */
internal fun readBoundedAudio(body: ResponseBody?, maxBytes: Long = 12_000_000L): ByteArray? {
    require(maxBytes in 1..Int.MAX_VALUE.toLong())
    if (body == null || body.contentLength() > maxBytes) return null
    val source = body.source()
    source.request(maxBytes + 1)
    if (source.buffer.size == 0L || source.buffer.size > maxBytes) return null
    return source.readByteArray()
}
