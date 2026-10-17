package com.jarvis.assistant.automation.agent

/**
 * Honest end-of-mission classification. A mission is PASS only with verified evidence of the goal.
 * Anything short of that is reported as PARTIAL, BLOCKED or UNSUPPORTED, never as success.
 */
enum class MissionOutcome { PASS, PARTIAL, BLOCKED, UNSUPPORTED }

object MissionOutcomes {
    /** Plain inputs so the rule is testable without Android; callers map engine state onto these. */
    data class Facts(
        val status: String,
        val evidence: String,
        val completedSteps: Int,
        val totalSteps: Int,
        val processedItems: Int,
        val unsupportedRequested: Boolean = false
    )

    private val blockingEvidence = setOf("NEEDS_LOGIN", "AMBIGUOUS", "SAFETY_LIMIT", "LOADING", "TARGET_NOT_FOUND")

    fun classify(facts: Facts): MissionOutcome {
        if (facts.unsupportedRequested) return MissionOutcome.UNSUPPORTED
        val verified = facts.evidence == "VERIFIED_GOAL" || facts.evidence == "END_REACHED"
        if (facts.status == "COMPLETED" && verified) return MissionOutcome.PASS

        val madeProgress = facts.processedItems > 0 || facts.completedSteps > 1
        val blocked = facts.status == "BLOCKED" || facts.evidence in blockingEvidence
        return when {
            blocked && !madeProgress -> MissionOutcome.BLOCKED
            blocked || (madeProgress && facts.status in setOf("FAILED", "CANCELLED", "PAUSED")) -> MissionOutcome.PARTIAL
            facts.status == "COMPLETED" -> MissionOutcome.PARTIAL // finished without verified evidence
            else -> MissionOutcome.BLOCKED
        }
    }
}
