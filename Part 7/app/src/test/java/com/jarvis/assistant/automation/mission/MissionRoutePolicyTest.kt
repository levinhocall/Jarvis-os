package com.jarvis.assistant.automation.mission

import com.jarvis.assistant.automation.agent.*
import com.jarvis.assistant.security.sandbox.RiskPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.jarvis.assistant.automation.tooling.ToolDefinition

class MissionRoutePolicyTest {
    @Test fun selectedRouteSurvivesConstraintRoundTripAndDoesNotDuplicate() {
        val constraints = MissionRoutePolicy.constraints(listOf("contact_index=2", "execution_route=UI_AGENT"), MissionExecutionRoute.CORE_LLM)
        assertEquals(2, constraints.size)
        assertEquals(MissionExecutionRoute.CORE_LLM, MissionRoutePolicy.route(constraints))
        assertTrue("contact_index=2" in constraints)
    }
    @Test fun routesHaveDistinctEnforcedToolSurfaces() {
        val ui = MissionRoutePolicy.allowedTools(listOf("execution_route=UI_AGENT"))!!
        val core = MissionRoutePolicy.allowedTools(listOf("execution_route=CORE_LLM"))!!
        assertTrue("inspect_screen" in ui && "click_ui_element" in ui)
        assertFalse("click_ui_element" in core || "inspect_screen" in core || "open_app" in core)
        assertTrue("search_web" in core)
        assertNull(MissionRoutePolicy.allowedTools(emptyList()))
    }
    @Test fun corruptedRouteCannotSilentlyExpandToolAuthority() {
        assertThrows(IllegalArgumentException::class.java) { MissionRoutePolicy.route(listOf("execution_route=UNRESTRICTED")) }
    }
    @Test fun corePlannerCannotExecuteAnOutOfRouteUiTool() = runBlocking {
        var executed = false
        val provider = object : LlmFunctionCallProvider {
            override val providerId = "route-boundary-test"
            override suspend fun isAvailable() = true
            override suspend fun predictStep(goal: String, history: List<LlmPlannerStepRecord>, availableTools: List<ToolDefinition>): LlmStepDecision {
                assertFalse(availableTools.any { it.name == "click_ui_element" })
                return LlmStepDecision.InvokeTool("click_ui_element", mapOf("target_text" to "Button"))
            }
        }
        val outcome = LlmPlanner(provider).planAndExecute("A test mission", allowedToolNames = setOf("search_web")) { _, _ ->
            executed = true; true to "Must not execute"
        }
        assertEquals("ROUTE_VIOLATION", outcome.abortReason); assertFalse(executed)
    }
    @Test fun bookingCommitButtonsRequireSpecificApproval() {
        assertTrue(RiskPolicy.evaluateRisk("click_ui_element", mapOf("target_text" to "Confirm booking")).requiresExplicitConfirmation)
        assertTrue(RiskPolicy.evaluateRisk("click_ui_element", mapOf("target_text" to "Request ride")).requiresExplicitConfirmation)
        assertFalse(RiskPolicy.evaluateRisk("search_web", mapOf("query" to "book recommendations")).requiresExplicitConfirmation)
    }
}
