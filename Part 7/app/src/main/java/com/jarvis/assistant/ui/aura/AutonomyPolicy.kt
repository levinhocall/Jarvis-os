package com.jarvis.assistant.ui.aura

object AutonomyPolicy {
    fun quietHour(hour: Int): Boolean { require(hour in 0..23); return hour >= 23 || hour < 7 }
    fun canNotify(enabled: Boolean, hour: Int, notificationPermission: Boolean) = enabled && !quietHour(hour) && notificationPermission
}
