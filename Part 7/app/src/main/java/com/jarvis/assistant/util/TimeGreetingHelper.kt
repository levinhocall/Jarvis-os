package com.jarvis.assistant.util

import java.util.Calendar

/**
 * Pure-logic time-of-day greeting generator.
 */
object TimeGreetingHelper {

    fun getTimeGreeting(hour: Int): String {
        return when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Welcome back"
        }
    }

    fun getFullGreeting(
        userName: String,
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): String {
        val greeting = getTimeGreeting(hour)
        val name = userName.trim().ifBlank { "NAZIB" }
        return "$greeting, $name"
    }
}
