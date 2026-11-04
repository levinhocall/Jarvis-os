package com.jarvis.assistant.ui.aura

import org.junit.Assert.*
import org.junit.Test

class AutonomyPolicyTest {
    @Test fun quietHoursIncludeElevenPmAndEndExactlyAtSevenAm() {
        for (hour in 0..23) assertEquals(hour == 23 || hour < 7, AutonomyPolicy.quietHour(hour))
    }
    @Test fun disablingOrMissingPermissionAlwaysPreventsNotification() {
        for (hour in 0..23) {
            assertFalse(AutonomyPolicy.canNotify(false,hour,true))
            assertFalse(AutonomyPolicy.canNotify(true,hour,false))
        }
        assertTrue(AutonomyPolicy.canNotify(true,7,true))
        assertFalse(AutonomyPolicy.canNotify(true,23,true))
    }
    @Test fun invalidHourIsRejected() { assertThrows(IllegalArgumentException::class.java) { AutonomyPolicy.quietHour(24) } }
}
