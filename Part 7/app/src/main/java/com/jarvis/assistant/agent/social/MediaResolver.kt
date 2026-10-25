package com.jarvis.assistant.agent.social

object MediaResolver {

    /**
     * Verifies that the media URI is a valid content:// URI shared into the app sandbox.
     * File paths or arbitrary remote URLs are rejected for safety.
     */
    fun isValidMediaUri(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        val clean = uri.trim()
        return clean.startsWith("content://", ignoreCase = true)
    }
}
