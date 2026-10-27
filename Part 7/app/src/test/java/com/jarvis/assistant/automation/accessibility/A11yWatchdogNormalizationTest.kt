package com.jarvis.assistant.automation.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the short/full component spelling stored in ENABLED_ACCESSIBILITY_SERVICES.
 * Without normalization the auto-rebind could fail to remove our own entry (short spelling) and
 * then append a duplicate full spelling, so the service was never really toggled.
 */
class A11yWatchdogNormalizationTest {

    private val full =
        "com.jarvis.assistant/com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService"
    private val short =
        "com.jarvis.assistant/.automation.accessibility.JarvisAccessibilityService"

    @Test
    fun normalizeComponent_expandsShortSpelling() {
        assertEquals(full, A11yWatchdog.normalizeComponent(short))
        assertEquals(full, A11yWatchdog.normalizeComponent(full))
    }

    @Test
    fun normalizeComponent_leavesMalformedInputUntouched() {
        assertEquals("garbage", A11yWatchdog.normalizeComponent("  garbage "))
        assertEquals("pkg/", A11yWatchdog.normalizeComponent("pkg/"))
        assertEquals("/Cls", A11yWatchdog.normalizeComponent("/Cls"))
    }

    @Test
    fun removeComponent_dropsShortSpellingWhenTargetIsFull() {
        val settings = "other.pkg/other.pkg.Svc:$short"
        assertEquals(
            "other.pkg/other.pkg.Svc",
            A11yWatchdog.removeComponentFromSettings(settings, full)
        )
    }

    @Test
    fun addComponent_doesNotDuplicateWhenShortSpellingAlreadyPresent() {
        assertEquals(full, A11yWatchdog.addComponentToSettings(short, full))
        assertEquals(
            "other.pkg/other.pkg.Svc:$full",
            A11yWatchdog.addComponentToSettings("other.pkg/other.pkg.Svc:$short", full)
        )
    }

    @Test
    fun removeComponent_neverTouchesOtherServices() {
        val other = "some.other/some.other.A11y"
        assertEquals(other, A11yWatchdog.removeComponentFromSettings("$other:$full", full))
    }
}
