package com.jarvis.assistant.data.model

enum class ConversationState(val displayName: String, val orbKey: String) {
    IDLE("IDLE", "idle"),
    LISTENING("LISTENING", "listening"),
    THINKING("THINKING", "thinking"),
    SPEAKING("SPEAKING", "speaking"),
    INTERRUPTED("INTERRUPTED", "listening");

    val key: String get() = displayName
    val subState: String get() = orbKey
}
