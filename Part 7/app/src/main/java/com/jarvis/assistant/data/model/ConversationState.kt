package com.jarvis.assistant.data.model

import java.util.Locale

/**
 * Conversation lifecycle exposed to the UI and service.
 *
 * IDLE/LISTENING/FAILED are retained for compatibility with existing screens and
 * automation code. The explicit lifecycle states are used for connection and
 * standby transitions without changing the app's public package or UI contract.
 */
enum class ConversationState(val displayName: String, val orbKey: String) {
    STANDBY("STANDBY", "idle"),
    CONNECTING("CONNECTING", "thinking"),
    CONNECTED("CONNECTED", "listening"),
    ERROR("ERROR", "idle"),

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

    companion object {
        /**
         * Converts transport status strings into stable lifecycle states.
         * Unknown statuses intentionally return null so UI-only status text
         * cannot accidentally overwrite the active conversation state.
         */
        fun fromConnectionStatus(status: String): ConversationState? {
            val normalized = status.trim().uppercase(Locale.ROOT)
            return when {
                normalized in setOf("CONNECTING...", "CONFIGURING...", "RECONNECTING...") -> CONNECTING
                normalized == "LIVE" || normalized == "CONNECTED" -> CONNECTED
                normalized.startsWith("COOLDOWN") -> RECOVERING
                normalized.startsWith("ERROR") -> ERROR
                else -> null
            }
        }
    }
}
