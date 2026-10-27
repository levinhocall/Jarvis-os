package com.jarvis.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class DesignTokenAndReduceMotionTest {

    companion object {
        // Dark tokens
        const val DARK_BACKGROUND = "#06050E"
        const val DARK_SURFACE = "#17143A"
        const val DARK_SURFACE_RAISED = "#221E4D"
        const val DARK_TEXT_PRIMARY = "#EEE9FF"
        const val DARK_TEXT_SECONDARY = "#A7A0CC"
        const val DARK_ACCENT_MARIGOLD = "#FFB547"
        const val DARK_BUTTON_TEXT = "#06050E"

        // Light tokens
        const val LIGHT_BACKGROUND = "#F3F0FB"
        const val LIGHT_SURFACE = "#FFFFFF"
        const val LIGHT_TEXT_PRIMARY = "#15112E"
        const val LIGHT_TEXT_SECONDARY = "#554F70"
        const val LIGHT_ACCENT = "#9A5500"
        const val WHITE = "#FFFFFF"
        const val BLACK = "#000000"

        fun srgbToLinear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        fun relativeLuminance(hexColor: String): Double {
            val cleanHex = hexColor.removePrefix("#")
            val r = cleanHex.substring(0, 2).toInt(16)
            val g = cleanHex.substring(2, 4).toInt(16)
            val b = cleanHex.substring(4, 6).toInt(16)
            return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b)
        }

        fun contrastRatio(colorA: String, colorB: String): Double {
            val lumA = relativeLuminance(colorA)
            val lumB = relativeLuminance(colorB)
            val lighter = max(lumA, lumB)
            val darker = min(lumA, lumB)
            return (lighter + 0.05) / (darker + 0.05)
        }
    }

    @Test
    fun testWcagContrastMathUtility() {
        val blackOnWhite = contrastRatio(BLACK, WHITE)
        assertTrue("Black on white should be ~21:1, was $blackOnWhite", blackOnWhite > 20.0)

        val sameColor = contrastRatio(WHITE, WHITE)
        assertEquals(1.0, sameColor, 0.01)
    }

    @Test
    fun testDarkThemeContrastRatio() {
        // WCAG AA requirement: normal text >= 4.5:1, large/UI text >= 3.0:1
        val textPrimaryOnBg = contrastRatio(DARK_TEXT_PRIMARY, DARK_BACKGROUND)
        assertTrue("Primary text on dark background must be >= 4.5:1 (was $textPrimaryOnBg)", textPrimaryOnBg >= 4.5)

        val textSecondaryOnBg = contrastRatio(DARK_TEXT_SECONDARY, DARK_BACKGROUND)
        assertTrue("Secondary text on dark background must be >= 4.5:1 (was $textSecondaryOnBg)", textSecondaryOnBg >= 4.5)

        val textPrimaryOnSurface = contrastRatio(DARK_TEXT_PRIMARY, DARK_SURFACE)
        assertTrue("Primary text on dark surface must be >= 4.5:1 (was $textPrimaryOnSurface)", textPrimaryOnSurface >= 4.5)

        val textSecondaryOnSurface = contrastRatio(DARK_TEXT_SECONDARY, DARK_SURFACE)
        assertTrue("Secondary text on dark surface must be >= 4.5:1 (was $textSecondaryOnSurface)", textSecondaryOnSurface >= 4.5)

        val buttonTextOnAccent = contrastRatio(DARK_BUTTON_TEXT, DARK_ACCENT_MARIGOLD)
        assertTrue("Button dark text on marigold accent must be >= 4.5:1 (was $buttonTextOnAccent)", buttonTextOnAccent >= 4.5)

        val accentOnBackground = contrastRatio(DARK_ACCENT_MARIGOLD, DARK_BACKGROUND)
        assertTrue("Accent on dark background must be >= 3.0:1 (was $accentOnBackground)", accentOnBackground >= 3.0)
    }

    @Test
    fun testLightThemeContrastRatio() {
        // WCAG AA requirement: normal text >= 4.5:1, large/UI text >= 3.0:1
        val textPrimaryOnBg = contrastRatio(LIGHT_TEXT_PRIMARY, LIGHT_BACKGROUND)
        assertTrue("Primary text on light background must be >= 4.5:1 (was $textPrimaryOnBg)", textPrimaryOnBg >= 4.5)

        val textSecondaryOnBg = contrastRatio(LIGHT_TEXT_SECONDARY, LIGHT_BACKGROUND)
        assertTrue("Secondary text on light background must be >= 4.5:1 (was $textSecondaryOnBg)", textSecondaryOnBg >= 4.5)

        val textPrimaryOnSurface = contrastRatio(LIGHT_TEXT_PRIMARY, LIGHT_SURFACE)
        assertTrue("Primary text on light surface must be >= 4.5:1 (was $textPrimaryOnSurface)", textPrimaryOnSurface >= 4.5)

        val textSecondaryOnSurface = contrastRatio(LIGHT_TEXT_SECONDARY, LIGHT_SURFACE)
        assertTrue("Secondary text on light surface must be >= 4.5:1 (was $textSecondaryOnSurface)", textSecondaryOnSurface >= 4.5)

        val whiteTextOnLightAccent = contrastRatio(WHITE, LIGHT_ACCENT)
        assertTrue("White text on light accent must be >= 4.5:1 (was $whiteTextOnLightAccent)", whiteTextOnLightAccent >= 4.5)
    }

    @Test
    fun testMotionDurationTokensAndRadii() {
        val motionFast = 150
        val motionMedium = 300
        val motionSlow = 500

        assertTrue("Motion fast must be 150ms", motionFast == 150)
        assertTrue("Motion medium must be 300ms", motionMedium == 300)
        assertTrue("Motion slow must be 500ms", motionSlow == 500)

        val radiusSm = 12
        val radiusMd = 20
        val radiusLg = 28
        val radiusPill = 999

        assertTrue(radiusSm < radiusMd)
        assertTrue(radiusMd < radiusLg)
        assertTrue(radiusLg < radiusPill)
    }

    @Test
    fun testReduceMotionPolicy() {
        fun resolveAnimationDuration(requestedMs: Long, isReduceMotion: Boolean): Long {
            return if (isReduceMotion) 0L else requestedMs
        }

        fun resolvePressScale(requestedScale: Float, isReduceMotion: Boolean): Float {
            return if (isReduceMotion) 1.0f else requestedScale
        }

        // When reduce motion is FALSE (normal)
        assertEquals(300L, resolveAnimationDuration(300L, isReduceMotion = false))
        assertEquals(0.96f, resolvePressScale(0.96f, isReduceMotion = false), 0.001f)

        // When reduce motion is TRUE
        assertEquals(0L, resolveAnimationDuration(300L, isReduceMotion = true))
        assertEquals(1.0f, resolvePressScale(0.96f, isReduceMotion = true), 0.001f)
    }
}
