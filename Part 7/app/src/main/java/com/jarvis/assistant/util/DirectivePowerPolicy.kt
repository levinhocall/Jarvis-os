package com.jarvis.assistant.util

object DirectivePowerPolicy {
    fun scanIntervalMs(profile: String, systemSaver: Boolean): Long =
        if (systemSaver || profile == "saver") 1000L else if (profile == "performance") 200L else 500L
    fun backgroundAllowed(profile: String, systemSaver: Boolean) = !systemSaver && profile != "saver"
}
