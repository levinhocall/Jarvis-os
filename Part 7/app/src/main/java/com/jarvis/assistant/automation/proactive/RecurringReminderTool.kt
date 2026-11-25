package com.jarvis.assistant.automation.proactive

import org.json.JSONObject
import java.time.DayOfWeek
import java.util.UUID

internal object RecurringReminderTool {
    fun schedule(repository: TriggerRepository, args: JSONObject): Boolean {
        val message = args.getString("message").trim()
        require(message.length in 1..300) { "The reminder must have 1–300 characters." }
        val days = args.optJSONArray("weekdays")
        val monthDay = if (args.has("month_day")) args.getInt("month_day") else null
        val interval = if (args.has("interval_minutes")) args.getInt("interval_minutes") else null
        val endHour = if (args.has("end_hour")) args.getInt("end_hour") else null
        val endMinute = if (args.has("end_minute")) args.getInt("end_minute") else null
        require((endHour == null) == (endMinute == null)) { "Specify both components of the ending time." }
        require(endHour == null || (endHour in 0..23 && endMinute != null && endMinute in 0..59)) {
            "Invalid ending time."
        }
        require(days == null || days.length() in 1..7) { "Choose one or more weekdays." }
        val weekdays = buildSet {
            if (days != null) {
                for (index in 0 until days.length()) {
                    val day = (days.get(index) as? Number)?.toDouble()
                    require(day != null && day in 1.0..7.0 && day % 1 == 0.0) { "Weekdays must be whole numbers from 1 to 7." }
                    add(DayOfWeek.of(day.toInt()))
                }
            }
        }.toSortedSet()
        val schedule = CalendarReminderSchedule(
            hour = args.getInt("hour"),
            minute = args.getInt("minute"),
            weekdays = weekdays,
            monthDay = monthDay,
            intervalMinutes = interval,
            windowEndMinutes = endHour?.let { it * 60 + requireNotNull(endMinute) }
        )
        require(schedule.isValid()) { "Choose a valid weekly, monthly, or interval schedule." }

        // An identical retry from Live should reuse the same encrypted reminder.
        val id = UUID.nameUUIDFromBytes("$message\n$schedule".toByteArray(Charsets.UTF_8)).toString()
        val trigger = ScheduledTrigger(
            triggerId = id,
            goalOrAction = message,
            triggerType = TriggerType.CALENDAR_REMINDER,
            calendarSchedule = schedule
        )
        return repository.scheduleTrigger(trigger)
    }
}
