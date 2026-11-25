package com.jarvis.assistant.automation.proactive

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * A calendar reminder uses the time zone in which it was created. When a month lacks the
 * selected date, the reminder falls on its last day.
 */
data class CalendarReminderSchedule(
    val hour: Int,
    val minute: Int,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthDay: Int? = null,
    val zoneId: String = ZoneId.systemDefault().id,
    val intervalMinutes: Int? = null,
    val windowEndMinutes: Int? = null
) {
    fun isValid(): Boolean {
        if (hour !in 0..23 || minute !in 0..59) return false
        return when {
            intervalMinutes != null -> monthDay == null && weekdays.isEmpty() &&
                intervalMinutes in 15..1_439 && windowEndMinutes != null && windowEndMinutes in 0..1_439 &&
                windowEndMinutes != hour * 60 + minute
            windowEndMinutes != null -> false
            monthDay != null -> weekdays.isEmpty() && monthDay in 1..31
            else -> weekdays.isNotEmpty()
        }
    }

    fun nextAfter(afterMs: Long): Long {
        require(isValid())
        val zone = ZoneId.of(zoneId)
        val after = Instant.ofEpochMilli(afterMs)
        val date = after.atZone(zone).toLocalDate()
        val time = LocalTime.of(hour, minute)

        if (intervalMinutes != null) {
            fun slots(startDate: LocalDate): Sequence<Instant> {
                val start = startDate.atTime(time)
                val endTime = LocalTime.ofSecondOfDay(requireNotNull(windowEndMinutes).toLong() * 60)
                val endDate = if (endTime <= time) startDate.plusDays(1) else startDate
                val end = endDate.atTime(endTime)
                return generateSequence(start) { it.plusMinutes(intervalMinutes.toLong()) }
                    .takeWhile { it < end }
                    .map { it.atZone(zone).toInstant() }
            }
            return (-1L..1L).asSequence().flatMap { slots(date.plusDays(it)) }
                .filter { it > after }.minOrNull()!!.toEpochMilli()
        }

        if (monthDay != null) {
            val thisMonth = YearMonth.from(date)
            val candidate = thisMonth.atDay(monthDay.coerceAtMost(thisMonth.lengthOfMonth()))
                .atTime(time).atZone(zone).toInstant()
            if (candidate > after) return candidate.toEpochMilli()
            val nextMonth = thisMonth.plusMonths(1)
            return nextMonth.atDay(monthDay.coerceAtMost(nextMonth.lengthOfMonth()))
                .atTime(time).atZone(zone).toInstant().toEpochMilli()
        }

        return (0L..7L).asSequence()
            .map(date::plusDays)
            .filter { it.dayOfWeek in weekdays }
            .map { it.atTime(time).atZone(zone).toInstant() }
            .first { it > after }
            .toEpochMilli()
    }
}
