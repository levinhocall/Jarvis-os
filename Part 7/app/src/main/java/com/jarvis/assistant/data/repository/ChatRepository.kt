package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.preferences.AppPreferences

/** Read/clear bridge for pre-Room history. Never accepts new conversation writes. */
class ChatRepository(private val preferences: AppPreferences) {
    fun readLegacyJson(): String? = preferences.readLegacyChatJson()
    fun clearMigratedJson(expectedJson: String) = preferences.clearMigratedChatJson(expectedJson)
    fun clearHistory() = preferences.clearChatHistory()
}
