package com.jarvis.assistant.automation.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

class CalendarReminderScheduleTest {
    @Test
    fun weeklyReminderUsesTheNextSelectedLocalDay() {
        val schedule = CalendarReminderSchedule(
            9, 15, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), zoneId = "Asia/Kolkata"
        )
        assertEquals(instant("2026-11-27T03:45:00Z"), schedule.nextAfter(instant("2026-11-25T10:00:00Z")))
        assertEquals(instant("2026-11-30T03:45:00Z"), schedule.nextAfter(instant("2026-11-27T03:45:00Z")))
    }

    @Test
    fun monthlyReminderUsesTheLastDayOfShorterMonths() {
        val schedule = CalendarReminderSchedule(8, 0, monthDay = 31, zoneId = "UTC")
        assertEquals(instant("2028-02-29T08:00:00Z"), schedule.nextAfter(instant("2028-01-31T08:00:00Z")))
        assertEquals(instant("2028-03-31T08:00:00Z"), schedule.nextAfter(instant("2028-02-29T08:00:00Z")))
    }

    @Test
    fun weeklyReminderKeepsWallTimeAcrossAClockChange() {
        val schedule = CalendarReminderSchedule(
            9, 0, setOf(DayOfWeek.SUNDAY), zoneId = "America/New_York"
        )
        assertEquals(instant("2026-03-01T14:00:00Z"), schedule.nextAfter(instant("2026-02-28T15:00:00Z")))
        assertEquals(instant("2026-03-08T13:00:00Z"), schedule.nextAfter(instant("2026-03-01T14:00:00Z")))
    }

    @Test
    fun intervalRespectsAnOvernightWindowAndAnExclusiveEnd() {
        val schedule = CalendarReminderSchedule(
            22, 0, zoneId = "UTC", intervalMinutes = 60, windowEndMinutes = 2 * 60
        )
        assertEquals(instant("2026-11-25T01:00:00Z"), schedule.nextAfter(instant("2026-11-25T00:30:00Z")))
        assertEquals(instant("2026-11-25T22:00:00Z"), schedule.nextAfter(instant("2026-11-25T01:00:00Z")))
    }

    @Test
    fun invalidRecurrenceAndTimesAreRejected() {
        assertFalse(CalendarReminderSchedule(8, 0).isValid())
        assertFalse(CalendarReminderSchedule(8, 0, setOf(DayOfWeek.MONDAY), 20).isValid())
        assertFalse(CalendarReminderSchedule(8, 0, monthDay = 32).isValid())
        assertFalse(CalendarReminderSchedule(24, 0, monthDay = 1).isValid())
        assertFalse(CalendarReminderSchedule(8, 60, monthDay = 1).isValid())
        assertFalse(CalendarReminderSchedule(8, 0, intervalMinutes = 14, windowEndMinutes = 900).isValid())
        assertFalse(CalendarReminderSchedule(8, 0, intervalMinutes = 15, windowEndMinutes = 480).isValid())
    }

    private fun instant(value: String): Long = Instant.parse(value).toEpochMilli()
}
