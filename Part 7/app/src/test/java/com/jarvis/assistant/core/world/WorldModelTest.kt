package com.jarvis.assistant.core.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorldModelTest {

    @Before
    fun setUp() {
        WorldModelCoordinator.resetForTest()
    }

    @Test
    fun testSetAndGetActiveGoal() {
        val goal = ActiveGoal(
            goalId = "g_101",
            description = "Book rideshare to airport",
            constraints = listOf("Cost under $50", "Arrival before 18:00")
        )
        WorldModelCoordinator.setActiveGoal(goal)

        val retrieved = WorldModelCoordinator.getActiveGoal()
        assertNotNull(retrieved)
        assertEquals("g_101", retrieved?.goalId)
        assertEquals(2, retrieved?.constraints?.size)
    }

    @Test
    fun testRecordVerifiedFactAndInferredHypothesis() {
        WorldModelCoordinator.recordFact(
            key = "battery_level",
            value = 85,
            source = "BatteryManager"
        )
        WorldModelCoordinator.recordHypothesis(
            key = "user_intent",
            value = "Wants to play music",
            source = "GeminiReasoner"
        )

        val fact = WorldModelCoordinator.getObservation<Int>("battery_level")
        assertNotNull(fact)
        assertEquals(85, fact?.value)
        assertEquals(ObservationNature.VERIFIED_FACT, fact?.nature)

        val hypothesis = WorldModelCoordinator.getObservation<String>("user_intent")
        assertNotNull(hypothesis)
        assertEquals(ObservationNature.INFERRED_HYPOTHESIS, hypothesis?.nature)
    }

    @Test
    fun testStaleObservationExpires() {
        // Record observation with 5ms TTL
        WorldModelCoordinator.recordFact(
            key = "transient_state",
            value = "temp_value",
            source = "Sensor",
            ttlMs = 5L
        )

        Thread.sleep(15L) // Exceeds TTL

        val observation = WorldModelCoordinator.getObservation<String>("transient_state")
        assertNull("Stale observation should return null and be purged", observation)
    }

    @Test
    fun testNavigationInvalidatesScreenContextAndHypotheses() {
        WorldModelCoordinator.updateScreenObservation(
            foregroundPackage = "com.whatsapp",
            visibleLabels = listOf("Chats", "Status", "Calls")
        )
        WorldModelCoordinator.recordHypothesis(
            key = "predicted_next_click",
            value = "Chats tab",
            source = "HeuristicModel"
        )
        WorldModelCoordinator.recordFact(
            key = "device_model",
            value = "Pixel 8",
            source = "BuildInfo"
        )

        assertNotNull(WorldModelCoordinator.getScreenContext())
        assertNotNull(WorldModelCoordinator.getObservation<String>("predicted_next_click"))

        // Navigation event to YouTube
        WorldModelCoordinator.onNavigation("com.google.android.youtube")

        // Screen context and hypotheses should be invalidated
        assertNull("Screen context should be invalidated after navigation", WorldModelCoordinator.getScreenContext())
        assertNull("Inferred hypothesis should be invalidated after navigation", WorldModelCoordinator.getObservation<String>("predicted_next_click"))
        // Verified ground-truth facts remain intact
        assertNotNull("Ground truth facts should survive navigation", WorldModelCoordinator.getObservation<String>("device_model"))
    }

    @Test
    fun testVerifyScreenExpectationMatches() {
        WorldModelCoordinator.updateScreenObservation(
            foregroundPackage = "com.google.android.youtube",
            visibleLabels = listOf("Search YouTube", "Subscriptions", "Library")
        )

        val result = WorldModelCoordinator.verifyScreenExpectation(
            expectedPackage = "com.google.android.youtube",
            requiredElementSubstrings = listOf("Search", "Subscriptions")
        )

        assertTrue(result is ScreenVerificationResult.Matches)
    }

    @Test
    fun testVerifyScreenExpectationDetectsPackageMismatch() {
        WorldModelCoordinator.updateScreenObservation(
            foregroundPackage = "com.android.settings",
            visibleLabels = listOf("Network & internet", "Apps", "Battery")
        )

        val result = WorldModelCoordinator.verifyScreenExpectation(
            expectedPackage = "com.whatsapp"
        )

        assertTrue(result is ScreenVerificationResult.Mismatch)
        val mismatch = result as ScreenVerificationResult.Mismatch
        assertTrue(mismatch.reason.contains("Expected foreground package 'com.whatsapp'"))
    }

    @Test
    fun testVerifyScreenExpectationDetectsMissingElements() {
        WorldModelCoordinator.updateScreenObservation(
            foregroundPackage = "com.example.app",
            visibleLabels = listOf("Welcome", "Terms of Service")
        )

        val result = WorldModelCoordinator.verifyScreenExpectation(
            expectedPackage = "com.example.app",
            requiredElementSubstrings = listOf("Submit Payment")
        )

        assertTrue(result is ScreenVerificationResult.Mismatch)
        val mismatch = result as ScreenVerificationResult.Mismatch
        assertTrue(mismatch.reason.contains("Missing expected UI elements"))
        assertTrue(mismatch.reason.contains("Submit Payment"))
    }

    @Test
    fun testSensitivePasswordNodesSanitized() {
        WorldModelCoordinator.updateScreenObservation(
            foregroundPackage = "com.bank.app",
            visibleLabels = listOf("Account Balance", "Enter Password", "Enter PIN"),
            containsPasswordFields = true
        )

        val screen = WorldModelCoordinator.getScreenContext()
        assertNotNull(screen)
        assertTrue(screen!!.visibleElementLabels.contains("Account Balance"))
        assertFalse("Password label should be redacted", screen.visibleElementLabels.any { it.contains("Password", ignoreCase = true) })
        assertFalse("PIN label should be redacted", screen.visibleElementLabels.any { it.contains("PIN", ignoreCase = true) })
    }
}
