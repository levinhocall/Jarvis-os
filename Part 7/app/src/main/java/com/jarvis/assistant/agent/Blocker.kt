package com.jarvis.assistant.agent

enum class Blocker {
    LOGIN,
    CAPTCHA,
    TWO_FACTOR,
    SECURITY_CHECK,
    PERMISSION_PROMPT
}

sealed class ExecutionResult {
    object Success : ExecutionResult()
    data class Failed(val reason: String) : ExecutionResult()
    data class Blocked(val blocker: Blocker) : ExecutionResult()
    object Stale : ExecutionResult()
}
