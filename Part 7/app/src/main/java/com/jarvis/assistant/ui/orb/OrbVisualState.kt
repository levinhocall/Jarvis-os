package com.jarvis.assistant.ui.orb

/** Presentation only. Never owns audio, authorization or task execution. */
object OrbVisualState {
    fun current(value: String): String {
        val base = fromSource(value)
        val mission = com.jarvis.assistant.automation.mission.MissionEngine.activeMission.value
        return if (base == "idle" && mission?.status == com.jarvis.assistant.automation.mission.MissionOverallStatus.COMPLETED &&
            System.currentTimeMillis() - mission.updatedAt in 0L..1400L) "success" else base
    }
    fun fromSource(value: String): String = when (value.lowercase(java.util.Locale.ROOT)) {
        "standby", "sleeping", "off" -> "sleeping"
        "connecting", "waking" -> "waking"
        "connected", "listening" -> "listening"
        "interrupted" -> "interrupted"
        "thinking", "recovering" -> "thinking"
        "executing" -> "executing"
        "speaking" -> "speaking"
        "completed", "success" -> "success"
        "failed", "error" -> "error"
        else -> "idle"
    }
}
