package com.jarvis.assistant.automation.council

import com.jarvis.assistant.automation.mission.EvidenceType
import com.jarvis.assistant.automation.mission.MissionVerificationEvidence
import com.jarvis.assistant.automation.mission.MissionEngine
import com.jarvis.assistant.automation.mission.ToolDefinition
import com.jarvis.assistant.automation.agent.LlmFunctionCallProvider
import com.jarvis.assistant.automation.agent.LlmPlanner
import com.jarvis.assistant.automation.agent.LlmPlannerStepRecord
import com.jarvis.assistant.automation.agent.LlmStepDecision
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
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
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.EXECUTOR, "unregistered_tool"))

        // Researcher is authorized for read-only, never mutating
        assertTrue(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "search_web"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "open_app"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "whatsapp_send"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "device_control"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "log_recall_core"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "task_completed"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "youtube_play"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.RESEARCHER, "termux_execute"))

        // Planner, Verifier, and Lead have 0 direct mutating tool authorization
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.PLANNER, "device_control"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.CRITIC, "device_control"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.VERIFIER, "whatsapp_send"))
        assertFalse(MultiAgentCouncil.isToolAuthorizedForRole(AgentRole.LEAD_SYNTHESIZER, "click_ui_element"))
    }

    @Test
    fun testCouncilExecution_boundedDelegationDepth() = runBlocking {
        val outcome = MultiAgentCouncil.executeCouncilTaskForTest(
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
        val outcome = MultiAgentCouncil.executeCouncilTaskForTest(
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

    @Test
    fun testCouncilExecution_withoutRealContextDoesNotFabricateSuccess() = runBlocking {
        val outcome = MultiAgentCouncil.executeCouncilTask(
            context = null,
            goal = "Search quantum computing"
        )

        assertFalse(outcome.isSuccess)
        assertFalse(outcome.isVerifiedIndependently)
        assertEquals(0, outcome.executionStepsCount)
        assertTrue(outcome.failureReason?.contains("Context unavailable", ignoreCase = true) == true)
    }

    @Test
    fun testCouncilExecution_clarificationRequiredDoesNotBecomeZeroStepSuccess() = runBlocking {
        val outcome = MultiAgentCouncil.executeCouncilTask(goal = "call")

        assertFalse(outcome.isSuccess)
        assertEquals(0, outcome.executionStepsCount)
        assertTrue(outcome.failureReason?.contains("Clarification required") == true)
        assertTrue(outcome.leadResponse.contains("contact", ignoreCase = true))
    }
    @Test
    fun testCouncilRunsIndependentSubAgentsInParallelWithinSharedBudget() = runBlocking {
        val previousPlanner = MissionEngine.llmPlanner
        val activeCalls = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val totalCalls = AtomicInteger(0)
        val provider = object : LlmFunctionCallProvider {
            override val providerId: String = "council-test-provider"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun predictStep(goal: String, history: List<LlmPlannerStepRecord>, availableTools: List<ToolDefinition>): LlmStepDecision =
                LlmStepDecision.Abort("Not used by advisory test")
            override suspend fun reason(prompt: String): String? {
                totalCalls.incrementAndGet()
                val active = activeCalls.incrementAndGet()
                maxConcurrent.updateAndGet { current -> maxOf(current, active) }
                try {
                    delay(120L)
                    return "REVIEW: independent findings recorded"
                } finally {
                    activeCalls.decrementAndGet()
                }
            }
        }
        MissionEngine.llmPlanner = LlmPlanner(provider)
        try {
            val outcome = MultiAgentCouncil.executeCouncilTaskForTest(
                goal = "Search recent Android 16 platform security updates, compare official release notes with current vulnerability guidance, and summarize the practical changes",
                simulatedExecutor = {
                    true to MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, "Test action verified")
                }
            )
            assertTrue(outcome.isSuccess)
            assertEquals(3, outcome.subAgentCallsUsed)
            assertEquals(3, totalCalls.get())
            assertEquals(3, outcome.agentReports.size)
            assertTrue("Sub-agents should overlap in time", maxConcurrent.get() > 1)
        } finally {
            MissionEngine.llmPlanner = previousPlanner
            assertTrue(MultiAgentCouncil.activeSubAgents.value.isEmpty())
        }
    }
    @Test
    fun testCriticBlockPreventsExecutorFromRunningMissionTools() = runBlocking {
        val previousPlanner = MissionEngine.llmPlanner
        val executions = AtomicInteger(0)
        val provider = object : LlmFunctionCallProvider {
            override val providerId: String = "council-block-test-provider"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun predictStep(goal: String, history: List<LlmPlannerStepRecord>, availableTools: List<ToolDefinition>): LlmStepDecision =
                LlmStepDecision.Abort("Not used by advisory test")
            override suspend fun reason(prompt: String): String? =
                if (prompt.contains("adversarial CRITIC")) "BLOCK: concrete destructive-action concern"
                else "REVIEW: checked"
        }
        MissionEngine.llmPlanner = LlmPlanner(provider)
        try {
            val outcome = MultiAgentCouncil.executeCouncilTaskForTest(
                goal = "Search the local records, identify duplicate entries, assess whether removal is safe, and prepare a verified cleanup plan for the duplicate records",
                simulatedExecutor = {
                    executions.incrementAndGet()
                    true to MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, "Should never execute")
                }
            )
            assertFalse(outcome.isSuccess)
            assertEquals("SUB_AGENT_CRITIC_BLOCK", outcome.failureReason)
            assertEquals(0, executions.get())
            assertEquals(3, outcome.subAgentCallsUsed)
        } finally {
            MissionEngine.llmPlanner = previousPlanner
        }
    }
    @Test
    fun testProductionPreflightRunsIndependentReviewers() = runBlocking {
        val previousPlanner = MissionEngine.llmPlanner
        val calls = AtomicInteger(0)
        val provider = object : LlmFunctionCallProvider {
            override val providerId: String = "production-preflight-test-provider"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun predictStep(goal: String, history: List<LlmPlannerStepRecord>, availableTools: List<ToolDefinition>): LlmStepDecision =
                LlmStepDecision.Abort("Not used by preflight test")
            override suspend fun reason(prompt: String): String? {
                calls.incrementAndGet()
                return "REVIEW: independent preflight review completed"
            }
        }
        MissionEngine.llmPlanner = LlmPlanner(provider)
        try {
            val result = MultiAgentCouncil.deliberate(
                context = null,
                userGoal = "Search recent Android platform security changes, compare official release notes with security bulletins, and identify the practical impact for this device"
            )
            assertEquals(3, calls.get())
            assertEquals(3, result.subAgentCallsUsed)
            assertEquals(3, result.agentReports.size)
            assertTrue(result.verifierCheck.contains("Independent verifier"))
        } finally {
            MissionEngine.llmPlanner = previousPlanner
            assertTrue(MultiAgentCouncil.activeSubAgents.value.isEmpty())
        }
    }
}
