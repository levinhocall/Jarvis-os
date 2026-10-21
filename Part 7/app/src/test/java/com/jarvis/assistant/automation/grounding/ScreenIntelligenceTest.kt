package com.jarvis.assistant.automation.grounding

import android.graphics.Rect
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.accessibility.AccessibilityState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScreenIntelligenceTest {

    @Before
    fun setUp() {
        AccessibilityHealthModel.updateState(AccessibilityState.DISABLED)
    }

    @Test
    fun testAccessibilityState_distinguishesEnabledFromConnectedAndOperational() {
        // 1. Initially DISABLED
        assertEquals(AccessibilityState.DISABLED, AccessibilityHealthModel.state.value)
        assertFalse(AccessibilityHealthModel.isOperational())
        assertFalse(AccessibilityHealthModel.isConnecting())
        assertTrue(AccessibilityHealthModel.getRecoveryGuidance()?.contains("disabled", ignoreCase = true) == true)

        // 2. ENABLED in Settings (user turned on switch, but binder not connected)
        AccessibilityHealthModel.updateState(AccessibilityState.ENABLED)
        assertEquals(AccessibilityState.ENABLED, AccessibilityHealthModel.state.value)
        assertFalse(AccessibilityHealthModel.isOperational())
        assertTrue(AccessibilityHealthModel.isConnecting())
        assertTrue(AccessibilityHealthModel.getRecoveryGuidance()?.contains("hasn't connected", ignoreCase = true) == true)

        // 3. CONNECTED (onServiceConnected called)
        AccessibilityHealthModel.updateState(AccessibilityState.CONNECTED)
        assertEquals(AccessibilityState.CONNECTED, AccessibilityHealthModel.state.value)
        assertTrue(AccessibilityHealthModel.isOperational())
        assertFalse(AccessibilityHealthModel.isConnecting())
        assertNull(AccessibilityHealthModel.getRecoveryGuidance())

        // 4. DEGRADED (operational but some gestures rejected)
        AccessibilityHealthModel.updateState(AccessibilityState.DEGRADED, "Gesture rejected")
        assertEquals(AccessibilityState.DEGRADED, AccessibilityHealthModel.state.value)
        assertTrue(AccessibilityHealthModel.isOperational())
        assertNull(AccessibilityHealthModel.getRecoveryGuidance())
    }

    @Test
    fun testAccessibilityHealthModel_tracksEventsAndGesturesAndVerification() {
        AccessibilityHealthModel.updateState(AccessibilityState.CONNECTED)

        assertEquals(0L, AccessibilityHealthModel.totalEventsReceived.value)
        assertFalse(AccessibilityHealthModel.isReceivingEvents.value)

        // Receive event
        AccessibilityHealthModel.recordEventReceived()
        assertEquals(1L, AccessibilityHealthModel.totalEventsReceived.value)
        assertTrue(AccessibilityHealthModel.isReceivingEvents.value)
        assertTrue(AccessibilityHealthModel.lastEventReceivedTimestamp.value > 0)

        // Dispatch gesture
        AccessibilityHealthModel.recordGestureDispatched(true, "clickAt(500, 500)")
        assertTrue(AccessibilityHealthModel.lastGestureDispatchedTimestamp.value > 0)

        // Action verification
        AccessibilityHealthModel.recordActionVerified(true, "Text confirmed")
        assertTrue(AccessibilityHealthModel.lastActionVerifiedTimestamp.value > 0)

        val summary = AccessibilityHealthModel.getOperationalStatusSummary()
        assertEquals(AccessibilityState.CONNECTED, summary.state)
        assertTrue(summary.isOperational)
        assertTrue(summary.isReceivingEvents)
        assertTrue(summary.lastEventReceivedAgeMs >= 0)
        assertTrue(summary.lastGestureDispatchedAgeMs >= 0)
        assertTrue(summary.lastActionVerifiedAgeMs >= 0)
    }

    @Test
    fun testSemanticGrounding_exactMatch_resolvesSemanticNode() {
        val button = GroundedElement(
            id = "com.app:id/submit_button",
            text = "Submit Order",
            contentDescription = "Confirm and Submit Order",
            screenBounds = ScreenBounds(100, 200, 400, 300),
            isClickable = true,
            className = "android.widget.Button"
        )
        val screen = ScreenContext(
            foregroundPackage = "com.app",
            elements = listOf(button)
        )

        val match = ScreenGroundingEngine.resolveTarget(screen, "Submit Order", ActionType.CLICK)
        assertEquals(GroundingOutcome.FOUND_EXACT, match.matchOutcome)
        assertNotNull(match.target)
        assertEquals("Submit Order", match.target?.displayLabel)
        assertEquals(100, match.target?.screenBounds?.left)
        assertEquals(200, match.target?.screenBounds?.top)
        assertEquals(400, match.target?.screenBounds?.right)
        assertEquals(300, match.target?.screenBounds?.bottom)
    }

    @Test
    fun testSemanticGrounding_ambiguousTarget_flagsAmbiguityInsteadOfGuessing() {
        val button1 = GroundedElement(
            id = "com.app:id/btn1",
            text = "Delete",
            screenBounds = ScreenBounds(50, 100, 200, 150),
            isClickable = true
        )
        val button2 = GroundedElement(
            id = "com.app:id/btn2",
            text = "Delete",
            screenBounds = ScreenBounds(50, 300, 200, 350),
            isClickable = true
        )
        val screen = ScreenContext(
            foregroundPackage = "com.app",
            elements = listOf(button1, button2)
        )

        val match = ScreenGroundingEngine.resolveTarget(screen, "Delete", ActionType.CLICK)
        assertEquals(GroundingOutcome.FOUND_AMBIGUOUS, match.matchOutcome)
        assertNull(match.target)
        assertEquals(2, match.candidates.size)
    }

    @Test
    fun testCoordinateGrounding_insideBounds_resolvesToInnermostNode() {
        val container = GroundedElement(
            id = "com.app:id/card",
            text = "Card Container",
            screenBounds = ScreenBounds(0, 0, 1000, 1000),
            className = "android.widget.FrameLayout"
        )
        val childButton = GroundedElement(
            id = "com.app:id/btn_action",
            text = "Click Me",
            screenBounds = ScreenBounds(100, 100, 300, 200),
            isClickable = true,
            className = "android.widget.Button"
        )
        val screen = ScreenContext(
            foregroundPackage = "com.app",
            elements = listOf(container, childButton),
            screenWidth = 1080,
            screenHeight = 2400
        )

        // Coordinate (150, 150) falls inside both, but childButton is smaller (innermost)
        val match = ScreenGroundingEngine.groundCoordinate(screen, 150, 150)
        assertEquals(GroundingOutcome.FOUND_EXACT, match.matchOutcome)
        assertEquals("Click Me", match.target?.displayLabel)
    }

    @Test
    fun testCoordinateGrounding_outOfBounds_rejectsSafely() {
        val screen = ScreenContext(
            foregroundPackage = "com.app",
            elements = emptyList(),
            screenWidth = 1080,
            screenHeight = 2400
        )

        val match = ScreenGroundingEngine.groundCoordinate(screen, 1500, 3000)
        assertEquals(GroundingOutcome.COORDINATE_OUT_OF_BOUNDS, match.matchOutcome)
        assertNull(match.target)
        assertTrue(match.explanation.contains("outside screen boundaries"))
    }

    @Test
    fun testSecurityGrounding_secureWindowOrPassword_blocksAutonomousInteraction() {
        val passwordField = GroundedElement(
            id = "com.app:id/pwd",
            text = "••••••",
            bounds = Rect(100, 100, 500, 200),
            isPassword = true,
            isProtected = true
        )
        val secureScreen = ScreenContext(
            foregroundPackage = "com.android.keyguard",
            elements = listOf(passwordField),
            isSecureWindow = true,
            securityReason = "Lock screen credentials"
        )

        val match = ScreenGroundingEngine.resolveTarget(secureScreen, "password", ActionType.TYPE)
        assertEquals(GroundingOutcome.SECURE_CONTENT_BLOCKED, match.matchOutcome)
        assertNull(match.target)

        val coordMatch = ScreenGroundingEngine.groundCoordinate(secureScreen, 200, 150)
        assertEquals(GroundingOutcome.SECURE_CONTENT_BLOCKED, coordMatch.matchOutcome)
    }

    @Test
    fun testStaleScreenDetection_expiredContext_forcesReObservation() {
        val button = GroundedElement(
            id = "btn",
            text = "Refresh",
            bounds = Rect(50, 50, 200, 100),
            isClickable = true
        )
        val staleScreen = ScreenContext(
            foregroundPackage = "com.app",
            elements = listOf(button),
            capturedTimestamp = System.currentTimeMillis() - 5000L, // 5 seconds ago, ttl is 2.5s
            ttlMs = 2500L
        )

        assertFalse(staleScreen.isFresh)
        val match = ScreenGroundingEngine.resolveTarget(staleScreen, "Refresh", ActionType.CLICK)
        assertEquals(GroundingOutcome.STALE_OBSERVATION, match.matchOutcome)

        val preCheck = ScreenGroundingEngine.validatePrecondition(button, ActionType.CLICK, staleScreen)
        assertFalse(preCheck.isValid)
        assertTrue(preCheck.isInterruptedOrStale)
    }

    @Test
    fun testClosedLoopOutcomeVerification_detectsPackageTransitionAndMutation() {
        val before = ScreenContext(
            foregroundPackage = "com.app.launcher",
            elements = listOf(GroundedElement("app1", "Browser", bounds = Rect(10, 10, 100, 100)))
        )
        val afterAppSwitch = ScreenContext(
            foregroundPackage = "com.android.chrome",
            elements = listOf(GroundedElement("url_bar", "Search or enter address", bounds = Rect(10, 10, 500, 50)))
        )

        val outcome = ScreenGroundingEngine.verifyOutcome(ActionType.CLICK, before, afterAppSwitch)
        assertTrue(outcome.isVerified)
        assertTrue(outcome.stateMutated)
        assertTrue(outcome.message.contains("transitioned", ignoreCase = true))

        // Same package, identical elements -> No mutation
        val identicalOutcome = ScreenGroundingEngine.verifyOutcome(ActionType.CLICK, before, before)
        assertFalse(identicalOutcome.isVerified)
        assertFalse(identicalOutcome.stateMutated)
    }
}
