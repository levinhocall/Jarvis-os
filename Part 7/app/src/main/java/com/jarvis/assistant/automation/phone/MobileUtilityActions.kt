package com.jarvis.assistant.automation.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import com.jarvis.assistant.util.ActivityLauncherHelper

/**
 * User-mediated device actions. These methods do not silently send SMS or bypass
 * Android's alarm/clock application.
 *
 * Integrate with the existing command dispatcher after voice intent tests pass.
 */
class MobileUtilityActions(private val context: Context) {

    fun composeSms(phoneNumber: String, message: String): String {
        val number = phoneNumber.trim()
        if (!Regex("^\\+?[0-9][0-9 ()-]{5,24}$").matches(number)) {
            return "Please choose a valid phone number before composing an SMS."
        }
        if (message.isBlank()) return "What message should I write?"
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.fromParts("smsto", number, null)
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (ActivityLauncherHelper.startActivitySafely(context, intent)) {
            "SMS draft opened. Review the recipient and message, then tap Send."
        } else {
            "No compatible SMS app was available."
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String = ""): String {
        if (hour !in 0..23 || minute !in 0..59) {
            return "Please specify a valid alarm time."
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (ActivityLauncherHelper.startActivitySafely(context, intent)) {
            "Alarm request opened in your clock app."
        } else {
            "Could not open a clock app to set the alarm."
        }
    }

    fun setTimer(seconds: Int, label: String = ""): String {
        if (seconds <= 0 || seconds > 86_400) {
            return "Please choose a timer between 1 second and 24 hours."
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (ActivityLauncherHelper.startActivitySafely(context, intent)) {
            "Timer request opened in your clock app."
        } else {
            "Could not open a clock app to set the timer."
        }
    }
}
