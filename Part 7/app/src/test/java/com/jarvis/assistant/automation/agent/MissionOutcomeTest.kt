package com.jarvis.assistant.automation.agent

import com.jarvis.assistant.automation.agent.MissionOutcomes.Facts
import org.junit.Assert.assertEquals
import org.junit.Test

class MissionOutcomeTest {
    private fun facts(
        status: String,
        evidence: String = "UNKNOWN",
        done: Int = 1,
        total: Int = 4,
        items: Int = 0,
        unsupported: Boolean = false
    ) = Facts(status, evidence, done, total, items, unsupported)

    @Test
    fun passRequiresCompletedStatusAndVerifiedEvidence() {
        assertEquals(MissionOutcome.PASS, MissionOutcomes.classify(facts("COMPLETED", "VERIFIED_GOAL", 4)))
        assertEquals(MissionOutcome.PASS, MissionOutcomes.classify(facts("COMPLETED", "END_REACHED", 5, 5, 12)))
    }

    @Test
    fun completedWithoutEvidenceIsNeverPass() {
        assertEquals(MissionOutcome.PARTIAL, MissionOutcomes.classify(facts("COMPLETED", "UNKNOWN", 4)))
    }

    @Test
    fun blockedBeforeAnyProgressIsBlocked() {
        assertEquals(MissionOutcome.BLOCKED, MissionOutcomes.classify(facts("BLOCKED", "NEEDS_LOGIN", 1)))
        assertEquals(MissionOutcome.BLOCKED, MissionOutcomes.classify(facts("RECOVERING", "UNKNOWN", 1)))
    }

    @Test
    fun progressThenStopIsPartial() {
        assertEquals(MissionOutcome.PARTIAL, MissionOutcomes.classify(facts("FAILED", "NO_PROGRESS", 3, 5, 7)))
        assertEquals(MissionOutcome.PARTIAL, MissionOutcomes.classify(facts("BLOCKED", "SAFETY_LIMIT", 2, 5, 20)))
        assertEquals(MissionOutcome.PARTIAL, MissionOutcomes.classify(facts("CANCELLED", "CANCELLED", 3)))
    }

    @Test
    fun unsupportedWinsOverEverything() {
        assertEquals(
            MissionOutcome.UNSUPPORTED,
            MissionOutcomes.classify(facts("COMPLETED", "VERIFIED_GOAL", 4, unsupported = true))
        )
    }
}
