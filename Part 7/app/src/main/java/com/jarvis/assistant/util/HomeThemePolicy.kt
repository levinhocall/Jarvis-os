package com.jarvis.assistant.util

object HomeThemePolicy {
    fun next(current: String): String = when (current) { "system" -> "light"; "light" -> "dark"; else -> "system" }
}
