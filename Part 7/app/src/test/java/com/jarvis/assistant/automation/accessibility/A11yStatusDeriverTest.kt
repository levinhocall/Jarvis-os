package com.jarvis.assistant.automation.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class A11yStatusDeriverTest {

    @Test
    fun testNotEnabled_whenSettingsDisabled() {
        val status = A11yStatusDeriver.deriveStatus(
            settingsEnabled = false,
            instancePresent = false,
            probeResult = LiveProbeResult.NO_INSTANCE
        )
        assertEquals(TruthfulA11yStatus.NOT_ENABLED, status)
    }

    @Test
    fun testEnabledNotConnected_whenSettingsEnabledButInstanceNull() {
        val status = A11yStatusDeriver.deriveStatus(
            settingsEnabled = true,
            instancePresent = false,
            probeResult = LiveProbeResult.NO_INSTANCE,
            msSinceEnabledSeen = 5000L
        )
        assertEquals(TruthfulA11yStatus.ENABLED_NOT_CONNECTED, status)
    }

    @Test
    fun testCrashLoop_whenConsecutiveCrashesExceedThreshold() {
        val status = A11yStatusDeriver.deriveStatus(
            settingsEnabled = true,
            instancePresent = true,
            probeResult = LiveProbeResult.SUCCESS,
            consecutiveCrashCount = 3
        )
        assertEquals(TruthfulA11yStatus.CRASH_LOOP, status)
    }

    @Test
    fun testDegraded_whenProbeThrowsException() {
        val status = A11yStatusDeriver.deriveStatus(
            settingsEnabled = true,
            instancePresent = true,
            probeResult = LiveProbeResult.EXCEPTION
        )
        assertEquals(TruthfulA11yStatus.DEGRADED, status)
    }

    @Test
    fun testDegraded_whenHealthModelMarksDegraded() {
        val status = A11yStatusDeriver.deriveStatus(
            settingsEnabled = true,
            instancePresent = true,
            probeResult = LiveProbeResult.SUCCESS,
            isDegraded = true
        )
        assertEquals(TruthfulA11yStatus.DEGRADED, status)
    }

    @Test
    fun testConnectedNoEvents_whenIdleForTooLong() {
        val status = A11yStatusDeriver.deriveStatus(
            settingsEnabled = true,
            instancePresent = true,
            probeResult = LiveProbeResult.SUCCESS,
            msSinceEnabledSeen = 120_000L,
            isConnectedReceivingEvents = false
        )
        assertEquals(TruthfulA11yStatus.CONNECTED_NO_EVENTS, status)
    }

    @Test
    fun testWorking_whenEverythingOperational() {
        val status = A11yStatusDeriver.deriveStatus(
            settingsEnabled = true,
            instancePresent = true,
            probeResult = LiveProbeResult.SUCCESS,
            isConnectedReceivingEvents = true
        )
        assertEquals(TruthfulA11yStatus.WORKING, status)
    }

    @Test
    fun testWorking_whenNullRootOnTransientScreen_stillConsideredWorking() {
        val status = A11yStatusDeriver.deriveStatus(
            settingsEnabled = true,
            instancePresent = true,
            probeResult = LiveProbeResult.NULL_ROOT,
            isConnectedReceivingEvents = true
        )
        assertEquals(TruthfulA11yStatus.WORKING, status)
    }
}
