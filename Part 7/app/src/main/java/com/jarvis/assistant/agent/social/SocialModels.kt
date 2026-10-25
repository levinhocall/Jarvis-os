package com.jarvis.assistant.agent.social

enum class Platform {
    INSTAGRAM,
    FACEBOOK,
    WHATSAPP
}

enum class SocialAction {
    CREATE_POST,
    CREATE_REEL,
    CREATE_STORY,
    TEXT_POST,
    SEND_MESSAGE,
    READ_CHAT,
    SEARCH_CHAT,
    MUTE,
    UNMUTE,
    MARK_READ
}

enum class TaskMode {
    PUBLISH,
    DRAFT
}

enum class ControlCommand {
    CONFIRM,
    REJECT,
    RESUME,
    CANCEL,
    STATUS
}

data class TaskReport(
    val taskId: String,
    val platform: Platform,
    val action: SocialAction,
    val status: String,
    val submittedVerified: Boolean,
    val details: String = ""
)
