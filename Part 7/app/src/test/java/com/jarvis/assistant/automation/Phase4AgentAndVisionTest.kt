package com.jarvis.assistant.automation

import com.jarvis.assistant.automation.accessibility.FinancialScreenPolicy
import com.jarvis.assistant.automation.agent.SelfHealingAgentLoop
import com.jarvis.assistant.automation.grounding.ActionOutcomeResult
import com.jarvis.assistant.automation.grounding.ActionType
import com.jarvis.assistant.automation.grounding.GroundedElement
import com.jarvis.assistant.automation.grounding.GroundingOutcome
import com.jarvis.assistant.automation.grounding.ScreenBounds
import com.jarvis.assistant.automation.grounding.ScreenContext
import com.jarvis.assistant.automation.grounding.ScreenGroundingEngine
import com.jarvis.assistant.automation.mission.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4AgentAndVisionTest {

    @Test
    fun toolRegistryDiscoveryReturnsBoundedRelevantSubsetsAndRegistersFindTool() {
        assertNotNull(ToolRegistry.getTool("find_tool"))

        val whatsappMatches = ToolRegistry.findTools("whatsapp message", limit = 4)
        assertTrue(whatsappMatches.isNotEmpty())
        assertTrue(whatsappMatches.size <= 4)
        assertTrue(whatsappMatches.any { it.definition.name == "whatsapp_send" || it.definition.name == "whatsapp_prepare" })

        val subset = ToolRegistry.selectSubsetForGoal("scroll screen and click settings", maxTools = 6)
        assertTrue(subset.size <= 6)
        assertTrue(subset.any { it.name == "find_tool" })
        assertTrue(subset.any { it.name == "scroll_screen" || it.name == "click_ui_element" })
    }

    @Test
    fun selfHealingAgentLoopSuppressesConsequentialRetriesAndBlocksUnapprovedActions() = runTest {
        val highRiskStep = SelfHealingAgentLoop.TaskStep(
            stepId = "s1",
            title = "Send payment transfer",
            toolName = "whatsapp_send",
            arguments = mapOf("contact" to "Alex", "message" to "transfer 500"),
            isConsequential = true,
            maxRetries = 3
        )
        val validation = SelfHealingAgentLoop.validateStepPrecondition(highRiskStep, userApproved = false)
        assertFalse(validation.isPermitted)
        assertTrue(validation.requiresUserApproval)

        val mutatedStep = SelfHealingAgentLoop.TaskStep(
            stepId = "s2",
            title = "Tap button",
            toolName = "click_ui_element",
            isConsequential = true,
            maxRetries = 3
        )
        var attempts = 0
        val outcome = SelfHealingAgentLoop.executeStepWithSelfHealing(
            step = mutatedStep,
            mission = SelfHealingAgentLoop.MissionPlan("m1", "Tap once", listOf(mutatedStep)),
            isCancelled = { false },
            userApproved = true,
            executeAction = {
                attempts++
                ActionOutcomeResult(isVerified = false, message = "Transition timeout", stateMutated = true)
            },
            attemptRecovery = { true }
        )
        assertEquals(SelfHealingAgentLoop.StepOutcome.UNVERIFIED_TIMEOUT, outcome)
        assertEquals(1, attempts)
    }

    @Test
    fun screenGroundingAndFinancialPolicyEnforceFreshnessAndProtectedWindowBlocking() {
        val sendBtn = GroundedElement(
            id = "com.example:id/send_btn",
            text = "Send Message",
            screenBounds = ScreenBounds(800, 1800, 1020, 1950),
            isClickable = true,
            className = "android.widget.Button"
        )
        val freshScreen = ScreenContext(
            foregroundPackage = "com.whatsapp",
            elements = listOf(sendBtn),
            isSecureWindow = false
        )

        val exact = ScreenGroundingEngine.resolveTarget(freshScreen, "Send Message", ActionType.CLICK)
        assertEquals(GroundingOutcome.FOUND_EXACT, exact.matchOutcome)
        assertEquals("com.example:id/send_btn", exact.target?.id)

        val coordMatch = ScreenGroundingEngine.groundCoordinate(freshScreen, 900, 1850, ActionType.CLICK)
        assertEquals(GroundingOutcome.FOUND_EXACT, coordMatch.matchOutcome)

        val staleScreen = freshScreen.copy(isInvalidated = true)
        assertEquals(
            GroundingOutcome.STALE_OBSERVATION,
            ScreenGroundingEngine.resolveTarget(staleScreen, "Send Message", ActionType.CLICK).matchOutcome
        )

        val secureScreen = freshScreen.copy(isSecureWindow = true, securityReason = "FLAG_SECURE")
        assertEquals(
            GroundingOutcome.SECURE_CONTENT_BLOCKED,
            ScreenGroundingEngine.resolveTarget(secureScreen, "Send Message", ActionType.CLICK).matchOutcome
        )

        FinancialScreenPolicy.observeWindow("com.phonepe.app")
        assertTrue(FinancialScreenPolicy.projectionBlocked)
        FinancialScreenPolicy.observeWindow("com.android.systemui")
        assertTrue(FinancialScreenPolicy.projectionBlocked)
        FinancialScreenPolicy.observeWindow("com.whatsapp")
        assertFalse(FinancialScreenPolicy.projectionBlocked)
    }
}
