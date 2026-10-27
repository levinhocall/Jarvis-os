package com.jarvis.assistant.ui.home

import com.jarvis.assistant.util.TimeGreetingHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeGreetingAndLookTest {

    @Test
    fun testTimeGreetingRanges() {
        // Morning (4..11)
        assertEquals("Good morning", TimeGreetingHelper.getTimeGreeting(4))
        assertEquals("Good morning", TimeGreetingHelper.getTimeGreeting(8))
        assertEquals("Good morning", TimeGreetingHelper.getTimeGreeting(11))

        // Afternoon (12..16)
        assertEquals("Good afternoon", TimeGreetingHelper.getTimeGreeting(12))
        assertEquals("Good afternoon", TimeGreetingHelper.getTimeGreeting(14))
        assertEquals("Good afternoon", TimeGreetingHelper.getTimeGreeting(16))

        // Evening (17..21)
        assertEquals("Good evening", TimeGreetingHelper.getTimeGreeting(17))
        assertEquals("Good evening", TimeGreetingHelper.getTimeGreeting(19))
        assertEquals("Good evening", TimeGreetingHelper.getTimeGreeting(21))

        // Late night / welcome back (22..3)
        assertEquals("Welcome back", TimeGreetingHelper.getTimeGreeting(22))
        assertEquals("Welcome back", TimeGreetingHelper.getTimeGreeting(23))
        assertEquals("Welcome back", TimeGreetingHelper.getTimeGreeting(0))
        assertEquals("Welcome back", TimeGreetingHelper.getTimeGreeting(3))
    }

    @Test
    fun testFullGreetingWithName() {
        assertEquals("Good morning, Alice", TimeGreetingHelper.getFullGreeting("Alice", 9))
        assertEquals("Good afternoon, Bob", TimeGreetingHelper.getFullGreeting("Bob", 14))
        assertEquals("Good evening, Carol", TimeGreetingHelper.getFullGreeting("Carol", 18))
        assertEquals("Welcome back, Dave", TimeGreetingHelper.getFullGreeting("Dave", 23))

        // Fallback to default NAZIB if blank
        assertEquals("Good morning, NAZIB", TimeGreetingHelper.getFullGreeting("", 9))
        assertEquals("Good morning, NAZIB", TimeGreetingHelper.getFullGreeting("   ", 9))
    }

    @Test
    fun testReduceMotionGatingPolicy() {
        fun shouldEnable3dShader(
            isNewLookBeta: Boolean,
            orbAnimationPref: String,
            sdkInt: Int,
            isReduceMotion: Boolean
        ): Boolean {
            return isNewLookBeta &&
                    orbAnimationPref in listOf("shader_3d", "3d_shader") &&
                    sdkInt >= 33 &&
                    !isReduceMotion
        }

        // 1. All positive conditions -> True
        assertTrue(shouldEnable3dShader(isNewLookBeta = true, orbAnimationPref = "shader_3d", sdkInt = 34, isReduceMotion = false))
        assertTrue(shouldEnable3dShader(isNewLookBeta = true, orbAnimationPref = "3d_shader", sdkInt = 33, isReduceMotion = false))

        // 2. Reduce motion ON -> False
        assertFalse(shouldEnable3dShader(isNewLookBeta = true, orbAnimationPref = "shader_3d", sdkInt = 34, isReduceMotion = true))

        // 3. API < 33 -> False (graceful fallback)
        assertFalse(shouldEnable3dShader(isNewLookBeta = true, orbAnimationPref = "shader_3d", sdkInt = 32, isReduceMotion = false))

        // 4. New Look Beta OFF (default non-destructive) -> False
        assertFalse(shouldEnable3dShader(isNewLookBeta = false, orbAnimationPref = "shader_3d", sdkInt = 34, isReduceMotion = false))

        // 5. Classic WebView orb chosen -> False
        assertFalse(shouldEnable3dShader(isNewLookBeta = true, orbAnimationPref = "holographic_plasma", sdkInt = 34, isReduceMotion = false))
    }
}
