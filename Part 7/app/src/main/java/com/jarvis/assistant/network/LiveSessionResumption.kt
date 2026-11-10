package com.jarvis.assistant.network

/** Opaque provider handle, in memory only. Never logs or serializes transcripts. */
internal class LiveSessionResumption {
    private var key: String? = null
    private var handle: String? = null

    @Synchronized fun forAccount(accountKey: String): String? {
        if (key != accountKey) { key = accountKey; handle = null }
        return handle
    }

    @Synchronized fun update(resumable: Boolean, newHandle: String?) {
        if (!resumable) { handle = null; return }
        if (!newHandle.isNullOrBlank() && newHandle.length <= 8192) handle = newHandle
    }
}
