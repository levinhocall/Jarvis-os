package com.jarvis.assistant.automation.council

import com.jarvis.assistant.automation.mission.EvidenceType
import com.jarvis.assistant.automation.mission.MissionVerificationEvidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MultiAgentCouncilTest {

    @Test
    fun testRoleToolAuthorization_onlyExecutorCanMutateState() {
        // Executor has authority over all tools
        assertTrue(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.EXECUTOR, "whatsapp_send"))
        assertTrue(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.EXECUTOR, "device_control"))
        assertTrue(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.EXECUTOR, "search_web"))

        // Researcher is authorized for read-only, never mutating
        assertTrue(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "search_web"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "whatsapp_send"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "device_control"))

        // Planner, Verifier, and Lead have 0 direct mutating tool authorization
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.PLANNER, "device_control"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.VERIFIER, "whatsapp_send"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.LEAD_SYNTHESIZER, "click_ui_element"))
    }

    @Test
    fun testCouncilExecution_boundedDelegationDepth() = runBlocking {
        val outcome = MultiAgentCouncil.executeCouncilTask(
            goal = "Search quantum computing",
            simulatedExecutor = {
                Pair(true, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, "Search completed"))
            }
        )

        assertTrue(outcome.isSuccess)
        assertTrue(outcome.handoffHistory.isNotEmpty())

        // Ensure every single handoff respects the strict max delegation depth
        for (handoff in outcome.handoffHistory) {
            assertTrue(
                "Delegation depth (${handoff.delegationDepth}) must not exceed MAX_DELEGATION_DEPTH (3)",
                handoff.delegationDepth <= MultiAgentCouncil.MAX_DELEGATION_DEPTH
            )
        }

        // Verify independent verification succeeded
        assertTrue(outcome.isVerifiedIndependently)
        assertTrue(outcome.leadResponse.contains("All systems nominal, Sir", ignoreCase = true))
    }

    @Test
    fun testCouncilExecution_consequentialActionsRequireUserApproval() = runBlocking {
        val outcome = MultiAgentCouncil.executeCouncilTask(
            goal = "Send WhatsApp message to Rahul saying I will reach late"
        )

        // Consequential action must NOT proceed without user approval
        assertFalse(outcome.isSuccess)
        assertEquals(0, outcome.executionStepsCount)
        assertTrue(outcome.leadResponse.contains("consequential", ignoreCase = true))
        assertEquals("Awaiting user approval", outcome.failureReason)
    }

    @Test
    fun testIndependentVerification_detectsUnverifiedOutcomesTruthfully() = runBlocking {
        val outcome = MultiAgentCouncil.executeCouncilTask(
            goal = "Search latest Android release notes",
            simulatedExecutor = {
                // Action returned unverified evidence
                Pair(true, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Physical confirmation missing"))
            }
        )

        assertTrue(outcome.isSuccess)
        // Verifier must NOT claim independent verification when physical evidence is false
        assertFalse(outcome.isVerifiedIndependently)
        assertTrue(outcome.leadResponse.contains("unconfirmed", ignoreCase = true))
    }
}
