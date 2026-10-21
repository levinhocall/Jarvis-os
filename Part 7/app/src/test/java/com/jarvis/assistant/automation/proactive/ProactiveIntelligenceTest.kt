package com.jarvis.assistant.automation.proactive

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProactiveIntelligenceTest {

    @Before
    fun setUp() {
        InterruptionGovernor.resetState()
    }

    @Test
    fun testQuietHours_suppressesOvertSuggestionsDuringNight() {
        val candidate = ProactiveCandidate(
            title = "Routine Check",
            description = "Good morning briefing ready",
            category = ProactiveCategory.WORKFLOW_SUGGESTION,
            confidence = 0.85f,
            deliveryType = SuggestionDeliveryType.OVERT_NOTIFICATION
        )

        // 1. At 23:00 (11 PM, inside 22:00 - 07:00) -> Suppressed
        val decisionNight = InterruptionGovernor.evaluate(candidate, currentHourOverride = 23)
        assertFalse(decisionNight.isAllowed)
        assertTrue(decisionNight.isQuietHours)

        // 2. At 04:00 (4 AM, inside 22:00 - 07:00) -> Suppressed
        val decisionEarlyMorning = InterruptionGovernor.evaluate(candidate, currentHourOverride = 4)
        assertFalse(decisionEarlyMorning.isAllowed)
        assertTrue(decisionEarlyMorning.isQuietHours)

        // 3. At 14:00 (2 PM, outside quiet hours) -> Allowed
        val decisionDaytime = InterruptionGovernor.evaluate(candidate, currentHourOverride = 14)
        assertTrue(decisionDaytime.isAllowed)
        assertFalse(decisionDaytime.isQuietHours)
    }

    @Test
    fun testConfidenceScoring_filtersLowConfidenceCandidates() {
        // High confidence (0.85f >= 0.70f) -> Allowed
        val goodCandidate = ProactiveCandidate(
            title = "Follow-up",
            description = "Respond to Alice",
            category = ProactiveCategory.COMMUNICATION_FOLLOWUP,
            confidence = 0.85f
        )
        val decisionGood = InterruptionGovernor.evaluate(goodCandidate, currentHourOverride = 14)
        assertTrue(decisionGood.isAllowed)

        // Low confidence (0.50f < 0.70f) -> Suppressed
        val weakCandidate = ProactiveCandidate(
            title = "Uncertain Guess",
            description = "You might want pizza",
            category = ProactiveCategory.WORKFLOW_SUGGESTION,
            confidence = 0.50f
        )
        val decisionWeak = InterruptionGovernor.evaluate(weakCandidate, currentHourOverride = 14)
        assertFalse(decisionWeak.isAllowed)
        assertTrue(decisionWeak.isLowConfidence)
    }

    @Test
    fun testSilentPreparation_bypassesQuietHoursAndThrottling() {
        val silentPrep = ProactiveCandidate(
            title = "Preload Weather Cache",
            description = "Warm up cache silently",
            category = ProactiveCategory.SYSTEM_TELEMETRY,
            confidence = 0.60f, // Low confidence
            deliveryType = SuggestionDeliveryType.SILENT_PREPARATION // Silent
        )

        // Silent prep allowed even at 3 AM
        val decision = InterruptionGovernor.evaluate(silentPrep, currentHourOverride = 3)
        assertTrue("Silent background preparation must not be blocked by quiet hours", decision.isAllowed)
    }

    @Test
    fun testFrequencyCapAndCooldown_throttlesOvertInterruptions() {
        val candidate = ProactiveCandidate(
            title = "Alert 1",
            description = "First notification",
            category = ProactiveCategory.SYSTEM_TELEMETRY,
            confidence = 0.90f
        )

        // First delivery allowed
        val decision1 = InterruptionGovernor.evaluate(candidate, currentHourOverride = 14)
        assertTrue(decision1.isAllowed)
        InterruptionGovernor.recordOvertDelivered(System.currentTimeMillis())

        // Immediate subsequent overt delivery (within 10-minute cooldown) -> Throttled
        val candidate2 = candidate.copy(title = "Alert 2")
        val decision2 = InterruptionGovernor.evaluate(candidate2, currentHourOverride = 14)
        assertFalse(decision2.isAllowed)
        assertTrue(decision2.isThrottled)
    }

    @Test
    fun testAdaptiveFeedback_dismissalsSuppressAnnoyingCategories() {
        val routineCandidate = ProactiveCandidate(
            title = "Evening Routine",
            description = "Dim lights",
            category = ProactiveCategory.WORKFLOW_SUGGESTION,
            confidence = 0.80f
        )

        // Simulate user dismissing this category 3 consecutive times
        InterruptionGovernor.recordFeedback(ProactiveCategory.WORKFLOW_SUGGESTION, accepted = false)
        InterruptionGovernor.recordFeedback(ProactiveCategory.WORKFLOW_SUGGESTION, accepted = false)
        InterruptionGovernor.recordFeedback(ProactiveCategory.WORKFLOW_SUGGESTION, accepted = false)

        // 4th time: Should be suppressed due to repeated dismissals
        val decision = InterruptionGovernor.evaluate(routineCandidate, currentHourOverride = 14)
        assertFalse(decision.isAllowed)
        assertTrue(decision.isUserSuppressed)

        // If user explicitly accepts later, suppression is reset
        InterruptionGovernor.recordFeedback(ProactiveCategory.WORKFLOW_SUGGESTION, accepted = true)
        val decisionAfterAccept = InterruptionGovernor.evaluate(routineCandidate, currentHourOverride = 14)
        assertTrue(decisionAfterAccept.isAllowed)
    }

    @Test
    fun testLowBatteryEventTrigger_deliversHighConfidenceAlert() {
        // Battery at 12% and not charging during daytime
        val decision = ProactiveAssistantCoordinator.onLowBatteryDetected(
            batteryPct = 12,
            isCharging = false,
            currentHourOverride = 14
        )
        assertNotNull(decision)
        assertTrue(decision?.isAllowed == true)

        // Battery at 80% -> No alert triggered (returns null)
        val normalDecision = ProactiveAssistantCoordinator.onLowBatteryDetected(
            batteryPct = 80,
            isCharging = false,
            currentHourOverride = 14
        )
        assertNull(normalDecision)
    }
}
