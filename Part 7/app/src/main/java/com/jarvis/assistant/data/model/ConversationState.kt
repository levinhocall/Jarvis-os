package com.jarvis.assistant.data.model

enum class ConversationState(val displayName: String, val orbKey: String) {
    IDLE("IDLE", "idle"),
    LISTENING("LISTENING", "listening"),
    THINKING("THINKING", "thinking"),
    SPEAKING("SPEAKING", "speaking"),
    INTERRUPTED("INTERRUPTED", "listening"),
    EXECUTING("EXECUTING", "thinking"),
    WAITING_FOR_APPROVAL("WAITING_FOR_APPROVAL", "idle"),
    RECOVERING("RECOVERING", "thinking"),
    FAILED("FAILED", "idle");

    val key: String get() = displayName
    val subState: String get() = orbKey
}
