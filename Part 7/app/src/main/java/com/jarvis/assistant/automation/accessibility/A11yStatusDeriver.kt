package com.jarvis.assistant.automation.accessibility

enum class LiveProbeResult {
    SUCCESS,
    NULL_ROOT,
    EXCEPTION,
    NO_INSTANCE
}

enum class TruthfulA11yStatus {
    NOT_ENABLED,
    ENABLED_NOT_CONNECTED,
    CONNECTED_NO_EVENTS,
    WORKING,
    DEGRADED,
    CRASH_LOOP
}

/**
 * Pure function to derive the truthful accessibility status based on Android settings,
 * live binder instance, live window inspection probe, and historical stability signals.
 */
object A11yStatusDeriver {

    fun deriveStatus(
        settingsEnabled: Boolean,
        instancePresent: Boolean,
        probeResult: LiveProbeResult,
        msSinceEnabledSeen: Long = 0L,
        isConnectedReceivingEvents: Boolean = true,
        isDegraded: Boolean = false,
        lastExitReason: Int? = null,
        consecutiveCrashCount: Int = 0
    ): TruthfulA11yStatus {
        if (!settingsEnabled) {
            return TruthfulA11yStatus.NOT_ENABLED
        }

        if (consecutiveCrashCount >= 3) {
            return TruthfulA11yStatus.CRASH_LOOP
        }

        if (!instancePresent || probeResult == LiveProbeResult.NO_INSTANCE) {
            return TruthfulA11yStatus.ENABLED_NOT_CONNECTED
        }

        if (probeResult == LiveProbeResult.EXCEPTION) {
            return TruthfulA11yStatus.DEGRADED
        }

        if (isDegraded) {
            return TruthfulA11yStatus.DEGRADED
        }

        if (!isConnectedReceivingEvents && msSinceEnabledSeen > 60_000L) {
            return TruthfulA11yStatus.CONNECTED_NO_EVENTS
        }

        return TruthfulA11yStatus.WORKING
    }
}
