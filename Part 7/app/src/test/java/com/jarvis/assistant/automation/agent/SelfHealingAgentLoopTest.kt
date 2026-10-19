package com.jarvis.assistant.automation.agent

import com.jarvis.assistant.automation.grounding.ActionOutcomeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SelfHealingAgentLoopTest {

    @Test
    fun testRejectsUnregisteredTool() {
        val step = SelfHealingAgentLoop.TaskStep(
            stepId = "step_1",
            title = "Execute shell command",
            toolName = "rm_rf_system",
            arguments = mapOf("cmd" to "rm -rf /")
        )

        val validation = SelfHealingAgentLoop.validateStepPrecondition(step, userApproved = false)
        assertFalse("Unregistered tool must be rejected", validation.isPermitted)
        assertTrue(validation.reason.contains("not registered"))
    }

    @Test
    fun testSensitiveActionRequiresUserApproval() {
        val step = SelfHealingAgentLoop.TaskStep(
            stepId = "step_2",
            title = "Send message to contact",
            toolName = "send_whatsapp_message",
            arguments = mapOf("contact" to "Mom", "text" to "I am running late")
        )

        assertTrue(step.requiresApproval)

        // Without user approval
        val unapproved = SelfHealingAgentLoop.validateStepPrecondition(step, userApproved = false)
        assertFalse(unapproved.isPermitted)
        assertTrue(unapproved.requiresUserApproval)

        // With user approval
        val approved = SelfHealingAgentLoop.validateStepPrecondition(step, userApproved = true)
        assertTrue("Approved sensitive action must be permitted", approved.isPermitted)
        assertFalse(approved.requiresUserApproval)
    }

    @Test
    fun testConsequentialActionSuppressesAutomaticRetryOnTimeout() = runBlocking {
        val step = SelfHealingAgentLoop.TaskStep(
            stepId = "step_consequential",
            title = "Confirm GPay Payment",
            toolName = "click_element",
            arguments = mapOf("action" to "Pay"),
            isConsequential = true,
            maxRetries = 3
        )
        val mission = SelfHealingAgentLoop.MissionPlan(
            missionId = "m_1",
            goal = "Pay electricity bill",
            steps = listOf(step)
        )

        val attempts = AtomicInteger(0)
        val outcome = SelfHealingAgentLoop.executeStepWithSelfHealing(
            step = step,
            mission = mission,
            isCancelled = { false },
            userApproved = true,
            executeAction = {
                attempts.incrementAndGet()
                // Action timed out or unverified
                ActionOutcomeResult(isVerified = false, stateMutated = false, message = "Timeout waiting for bank response")
            },
            attemptRecovery = { false }
        )

        assertEquals("Consequential action should halt as UNVERIFIED_TIMEOUT to avoid double-charging",
            SelfHealingAgentLoop.StepOutcome.UNVERIFIED_TIMEOUT, outcome)
        assertEquals("Consequential action must NOT retry automatically on timeout", 1, attempts.get())
        assertTrue(mission.hasUnverifiedSteps)
        assertFalse("Mission with unverified steps must not be considered complete", mission.isCompleted)
    }

    @Test
    fun testSelfHealingBoundedRecoverySucceedsOnSecondAttempt() = runBlocking {
        val step = SelfHealingAgentLoop.TaskStep(
            stepId = "step_normal",
            title = "Click Search",
            toolName = "click_element",
            isConsequential = false,
            maxRetries = 2
        )
        val mission = SelfHealingAgentLoop.MissionPlan(
            missionId = "m_2",
            goal = "Search YouTube",
            steps = listOf(step)
        )

        val attempts = AtomicInteger(0)
        val recoveryAttempts = AtomicInteger(0)

        val outcome = SelfHealingAgentLoop.executeStepWithSelfHealing(
            step = step,
            mission = mission,
            isCancelled = { false },
            userApproved = true,
            executeAction = {
                val current = attempts.incrementAndGet()
                if (current == 1) {
                    ActionOutcomeResult(isVerified = false, stateMutated = false, message = "Popup dialog obscuring target")
                } else {
                    ActionOutcomeResult(isVerified = true, stateMutated = true, message = "Clicked search successfully")
                }
            },
            attemptRecovery = {
                recoveryAttempts.incrementAndGet()
                true // Dismissed popup dialog
            }
        )

        assertEquals(SelfHealingAgentLoop.StepOutcome.SUCCESS, outcome)
        assertEquals(2, attempts.get())
        assertEquals(1, recoveryAttempts.get())
        assertTrue(mission.isCompleted)
    }

    @Test
    fun testTruthfulMissionReportingNeverClaimsFalseSuccess() {
        val step1 = SelfHealingAgentLoop.TaskStep("s1", "Open WhatsApp", "open_app").apply {
            outcome = SelfHealingAgentLoop.StepOutcome.SUCCESS
            executionMessage = "App opened"
        }
        val step2 = SelfHealingAgentLoop.TaskStep("s2", "Send Message", "send_whatsapp_message").apply {
            outcome = SelfHealingAgentLoop.StepOutcome.UNVERIFIED_TIMEOUT
            executionMessage = "Timed out before delivery check"
        }

        val mission = SelfHealingAgentLoop.MissionPlan(
            missionId = "m_3",
            goal = "Send WhatsApp message to Alex",
            steps = listOf(step1, step2)
        )

        assertFalse(mission.isCompleted)
        assertTrue(mission.hasUnverifiedSteps)

        val report = SelfHealingAgentLoop.generateMissionReport(mission)
        assertTrue("Report must indicate incomplete/unverified status", report.contains("Mission Incomplete (Unverified Steps)"))
        assertFalse("Report must not claim mission success", report.contains("Mission Completed Successfully"))
    }
}
