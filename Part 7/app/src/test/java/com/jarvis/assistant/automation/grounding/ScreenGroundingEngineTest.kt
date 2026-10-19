package com.jarvis.assistant.automation.grounding

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGroundingEngineTest {

    @Test
    fun testStaleObservationRejection() {
        val expiredTimestamp = System.currentTimeMillis() - 5000L
        val staleContext = ScreenContext(
            foregroundPackage = "com.android.settings",
            elements = listOf(
                GroundedElement(id = "btn_wifi", text = "Wi-Fi", isClickable = true, bounds = Rect(10, 10, 100, 50))
            ),
            capturedTimestamp = expiredTimestamp,
            ttlMs = 2500L
        )

        assertFalse("Expired context should not be fresh", staleContext.isFresh)

        val match = ScreenGroundingEngine.resolveTarget(staleContext, "Wi-Fi", ActionType.CLICK)
        assertEquals(GroundingOutcome.STALE_OBSERVATION, match.matchOutcome)
        assertNull("Target should be null when observation is stale", match.target)
    }

    @Test
    fun testManualInvalidationMarksContextStale() {
        val freshContext = ScreenContext(
            foregroundPackage = "com.google.android.youtube",
            elements = listOf(
                GroundedElement(id = "search_btn", text = "Search", isClickable = true, bounds = Rect(0, 0, 50, 50))
            ),
            capturedTimestamp = System.currentTimeMillis(),
            isInvalidated = false
        )
        assertTrue(freshContext.isFresh)

        val invalidated = freshContext.copy(isInvalidated = true)
        assertFalse(invalidated.isFresh)
        val match = ScreenGroundingEngine.resolveTarget(invalidated, "Search", ActionType.CLICK)
        assertEquals(GroundingOutcome.STALE_OBSERVATION, match.matchOutcome)
    }

    @Test
    fun testMissingElementReturnsNotFoundWithoutBlindGuessing() {
        val context = ScreenContext(
            foregroundPackage = "com.whatsapp",
            elements = listOf(
                GroundedElement(id = "chat_title", text = "Alice", isClickable = true, bounds = Rect(10, 10, 200, 60))
            ),
            capturedTimestamp = System.currentTimeMillis()
        )

        val match = ScreenGroundingEngine.resolveTarget(context, "Nonexistent Button", ActionType.CLICK)
        assertEquals(GroundingOutcome.NOT_FOUND, match.matchOutcome)
        assertNull("Should not guess or return an arbitrary element", match.target)
    }

    @Test
    fun testAmbiguousTargetsDetected() {
        // Two distinct buttons with the identical text "Confirm"
        val context = ScreenContext(
            foregroundPackage = "com.example.app",
            elements = listOf(
                GroundedElement(id = "btn_confirm_1", text = "Confirm", isClickable = true, bounds = Rect(10, 100, 100, 150)),
                GroundedElement(id = "btn_confirm_2", text = "Confirm", isClickable = true, bounds = Rect(10, 300, 100, 350))
            ),
            capturedTimestamp = System.currentTimeMillis()
        )

        val match = ScreenGroundingEngine.resolveTarget(context, "Confirm", ActionType.CLICK)
        assertEquals(GroundingOutcome.FOUND_AMBIGUOUS, match.matchOutcome)
        assertNull("Should not pick randomly between ambiguous buttons", match.target)
        assertEquals(2, match.candidates.size)
    }

    @Test
    fun testPreconditionValidationRejectsInvalidBounds() {
        val invalidElement = GroundedElement(
            id = "hidden_view",
            text = "Hidden",
            bounds = Rect(0, 0, 0, 0), // Zero width/height
            isClickable = true
        )
        val context = ScreenContext(
            foregroundPackage = "com.example.app",
            elements = listOf(invalidElement),
            capturedTimestamp = System.currentTimeMillis()
        )

        val result = ScreenGroundingEngine.validatePrecondition(invalidElement, ActionType.CLICK, context)
        assertFalse("Zero-dimension bounds must fail precondition check", result.isValid)
    }

    @Test
    fun testOutcomeVerificationDetectsPackageTransition() {
        val before = ScreenContext(
            foregroundPackage = "com.jarvis.assistant",
            elements = listOf(GroundedElement(id = "main_orb", text = "Jarvis", bounds = Rect(0, 0, 100, 100)))
        )
        val after = ScreenContext(
            foregroundPackage = "com.google.android.youtube",
            elements = listOf(GroundedElement(id = "yt_logo", text = "YouTube", bounds = Rect(0, 0, 100, 100)))
        )

        val result = ScreenGroundingEngine.verifyOutcome(ActionType.CLICK, before, after)
        assertTrue(result.isVerified)
        assertTrue(result.stateMutated)
        assertTrue(result.message.contains("transitioned from com.jarvis.assistant to com.google.android.youtube"))
    }

    @Test
    fun testOutcomeVerificationRejectsUnchangedScreen() {
        val staticContext = ScreenContext(
            foregroundPackage = "com.android.settings",
            elements = listOf(GroundedElement(id = "row_display", text = "Display", bounds = Rect(0, 0, 100, 50)))
        )

        val result = ScreenGroundingEngine.verifyOutcome(ActionType.CLICK, staticContext, staticContext)
        assertFalse("Unmutated screen must not be falsely verified", result.isVerified)
        assertFalse(result.stateMutated)
    }
}
