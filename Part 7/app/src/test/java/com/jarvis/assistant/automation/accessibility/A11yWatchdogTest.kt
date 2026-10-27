package com.jarvis.assistant.automation.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class A11yWatchdogTest {

    @Test
    fun testShouldNotifyUser_rateLimitsTo6Hours() {
        val now = 1_000_000_000L
        val fiveHoursAgo = now - TimeUnit.HOURS.toMillis(5)
        val sevenHoursAgo = now - TimeUnit.HOURS.toMillis(7)

        // Case 1: Settings disabled -> should NOT notify
        assertFalse(
            A11yWatchdog.shouldNotifyUser(
                settingsEnabled = false,
                instancePresent = false,
                nowMs = now,
                lastNotifiedMs = sevenHoursAgo,
                uptimeMs = 10_000L
            )
        )

        // Case 2: Instance present -> should NOT notify
        assertFalse(
            A11yWatchdog.shouldNotifyUser(
                settingsEnabled = true,
                instancePresent = true,
                nowMs = now,
                lastNotifiedMs = sevenHoursAgo,
                uptimeMs = 10_000L
            )
        )

        // Case 3: Uptime inside grace period (3s < 5s) -> should NOT notify
        assertFalse(
            A11yWatchdog.shouldNotifyUser(
                settingsEnabled = true,
                instancePresent = false,
                nowMs = now,
                lastNotifiedMs = sevenHoursAgo,
                uptimeMs = 3_000L
            )
        )

        // Case 4: Notified 5 hours ago (too soon) -> should NOT notify
        assertFalse(
            A11yWatchdog.shouldNotifyUser(
                settingsEnabled = true,
                instancePresent = false,
                nowMs = now,
                lastNotifiedMs = fiveHoursAgo,
                uptimeMs = 10_000L
            )
        )

        // Case 5: Stale binding, uptime > 5s, last notified > 6 hours -> MUST notify!
        assertTrue(
            A11yWatchdog.shouldNotifyUser(
                settingsEnabled = true,
                instancePresent = false,
                nowMs = now,
                lastNotifiedMs = sevenHoursAgo,
                uptimeMs = 10_000L
            )
        )
    }

    @Test
    fun testShouldAttemptAutoRebind_guards() {
        // Case 1: Missing permission -> false
        assertFalse(
            A11yWatchdog.shouldAttemptAutoRebind(
                hasWriteSecureSettingsPermission = false,
                isAutoRepairPrefEnabled = true,
                truthfulStatus = TruthfulA11yStatus.ENABLED_NOT_CONNECTED,
                rebindCountThisHour = 0
            )
        )

        // Case 2: User opt-in pref false -> false
        assertFalse(
            A11yWatchdog.shouldAttemptAutoRebind(
                hasWriteSecureSettingsPermission = true,
                isAutoRepairPrefEnabled = false,
                truthfulStatus = TruthfulA11yStatus.ENABLED_NOT_CONNECTED,
                rebindCountThisHour = 0
            )
        )

        // Case 3: Status not ENABLED_NOT_CONNECTED (e.g. WORKING) -> false
        assertFalse(
            A11yWatchdog.shouldAttemptAutoRebind(
                hasWriteSecureSettingsPermission = true,
                isAutoRepairPrefEnabled = true,
                truthfulStatus = TruthfulA11yStatus.WORKING,
                rebindCountThisHour = 0
            )
        )

        // Case 4: Rebind attempts reached cap of 3 -> false
        assertFalse(
            A11yWatchdog.shouldAttemptAutoRebind(
                hasWriteSecureSettingsPermission = true,
                isAutoRepairPrefEnabled = true,
                truthfulStatus = TruthfulA11yStatus.ENABLED_NOT_CONNECTED,
                rebindCountThisHour = 3
            )
        )

        // Case 5: All prerequisites satisfied -> true
        assertTrue(
            A11yWatchdog.shouldAttemptAutoRebind(
                hasWriteSecureSettingsPermission = true,
                isAutoRepairPrefEnabled = true,
                truthfulStatus = TruthfulA11yStatus.ENABLED_NOT_CONNECTED,
                rebindCountThisHour = 1
            )
        )
    }

    @Test
    fun testSettingsComponentStringTransformations() {
        val target = "com.jarvis.assistant/com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService"
        val other = "com.android.talkback/com.google.android.marvin.talkback.TalkBackService"

        // Remove target
        val combined = "$other:$target"
        val removed = A11yWatchdog.removeComponentFromSettings(combined, target)
        assertEquals(other, removed)

        // Add target
        val added = A11yWatchdog.addComponentToSettings(removed, target)
        assertEquals("$other:$target", added)

        // Empty string handling
        assertEquals(target, A11yWatchdog.addComponentToSettings("", target))
        assertEquals("", A11yWatchdog.removeComponentFromSettings(target, target))
    }
}
