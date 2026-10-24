package com.jarvis.assistant.network

/**
 * Thread-safe bounded deduplicator for tool/function calls received from Gemini Live WebSocket.
 * Prevents double-execution of duplicate or re-transmitted tool calls within a rolling window.
 */
class ToolCallDeduplicator(private val maxCapacity: Int = 100) {

    private val seenCallIds = object : LinkedHashMap<String, Boolean>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > maxCapacity
        }
    }

    /**
     * Checks if a tool call ID has already been seen.
     * If not seen, registers it and returns true (call should be processed).
     * If already seen or blank, returns false (call should be skipped).
     */
    @Synchronized
    fun shouldProcess(callId: String): Boolean {
        if (callId.isBlank()) return false
        return if (seenCallIds.containsKey(callId)) {
            false
        } else {
            seenCallIds[callId] = true
            true
        }
    }

    /**
     * Resets the deduplication cache.
     */
    @Synchronized
    fun clear() {
        seenCallIds.clear()
    }

    /**
     * Returns the current number of tracked call IDs.
     */
    @Synchronized
    fun size(): Int = seenCallIds.size
}
