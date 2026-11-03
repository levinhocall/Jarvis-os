package com.jarvis.assistant.util

import org.junit.Assert.*
import org.junit.Test

class DirectivePoliciesTest {
    @Test fun themeCyclesSystemLightDarkWithoutDiscardingCustomBeforeFirstTap() {
        assertEquals("light", HomeThemePolicy.next("system"))
        assertEquals("dark", HomeThemePolicy.next("light"))
        assertEquals("system", HomeThemePolicy.next("dark"))
        assertEquals("system", HomeThemePolicy.next("custom"))
    }
    @Test fun systemSaverOverridesPerformanceAndDefersBackgroundReviews() {
        assertEquals(1000L, DirectivePowerPolicy.scanIntervalMs("performance", true))
        assertEquals(200L, DirectivePowerPolicy.scanIntervalMs("performance", false))
        assertEquals(500L, DirectivePowerPolicy.scanIntervalMs("balanced", false))
        assertFalse(DirectivePowerPolicy.backgroundAllowed("saver", false))
        assertFalse(DirectivePowerPolicy.backgroundAllowed("performance", true))
        assertTrue(DirectivePowerPolicy.backgroundAllowed("balanced", false))
    }
}
