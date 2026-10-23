package com.jarvis.assistant.automation.agent

import com.jarvis.assistant.automation.mission.ToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LlmPlannerTest {

    /**
     * Fake scripted provider for deterministic unit testing.
     */
    class FakeScriptedProvider(
        private val script: (stepNumber: Int, history: List<LlmPlannerStepRecord>) -> LlmStepDecision
    ) : LlmFunctionCallProvider {
        override val providerId: String = "fake_scripted"
        private val invocationCount = AtomicInteger(0)

        override suspend fun isAvailable(): Boolean = true

        override suspend fun predictStep(
            goal: String,
            history: List<LlmPlannerStepRecord>,
            availableTools: List<ToolDefinition>
        ): LlmStepDecision {
            val stepNum = invocationCount.incrementAndGet()
            return script(stepNum, history)
        }
    }

    @Test
    fun testToolExecutionInCorrectOrder() = runBlocking {
        val executedTools = mutableListOf<String>()

        val provider = FakeScriptedProvider { stepNum, _ ->
            when (stepNum) {
                1 -> LlmStepDecision.InvokeTool("open_app", mapOf("app_name" to "YouTube"))
                2 -> LlmStepDecision.InvokeTool("youtube_play", mapOf("query" to "Hans Zimmer"))
                else -> LlmStepDecision.Finish("Music playback started successfully.")
            }
        }

        val planner = LlmPlanner(provider, maxSteps = 5)
        val result = planner.planAndExecute("Play Hans Zimmer on YouTube") { toolName, args ->
            executedTools.add(toolName)
            Pair(true, "Action executed for $toolName with $args")
        }

        assertTrue("Mission should succeed", result.isSuccess)
        assertEquals(2, result.stepsExecuted)
        assertEquals(listOf("open_app", "youtube_play"), executedTools)
        assertTrue(result.summary.contains("Music playback started"))
    }

    @Test
    fun testLoopDetectionAbort() = runBlocking {
        // Return identical tool and arguments continuously
        val provider = FakeScriptedProvider { _, _ ->
            LlmStepDecision.InvokeTool("scroll_screen", mapOf("direction" to "down"))
        }

        val planner = LlmPlanner(provider, maxSteps = 10)
        val result = planner.planAndExecute("Find post on screen") { _, _ ->
            Pair(true, "Scrolled down")
        }

        assertFalse("Mission should not succeed on loop", result.isSuccess)
        assertEquals("LOOP_DETECTED", result.abortReason)
        assertTrue(result.summary.contains("Loop detected"))
    }

    @Test
    fun testStepLimitAbort() = runBlocking {
        var counter = 0
        val provider = FakeScriptedProvider { _, _ ->
            counter++
            LlmStepDecision.InvokeTool("search_web", mapOf("query" to "query_$counter"))
        }

        val planner = LlmPlanner(provider, maxSteps = 3)
        val result = planner.planAndExecute("Deep research") { _, _ ->
            Pair(true, "Search results returned")
        }

        assertFalse("Mission should abort on step limit", result.isSuccess)
        assertEquals("STEP_LIMIT_EXCEEDED", result.abortReason)
        assertEquals(3, result.stepsExecuted)
    }

    @Test
    fun testReplanAfterFailedVerification() = runBlocking {
        val historyObservations = mutableListOf<String>()

        val provider = FakeScriptedProvider { stepNum, history ->
            when (stepNum) {
                1 -> LlmStepDecision.InvokeTool("click_ui_element", mapOf("target_text" to "Submit"))
                2 -> {
                    // Check that the provider can observe the failure of step 1
                    val lastObs = history.lastOrNull()?.observation ?: ""
                    historyObservations.add(lastObs)
                    // Replanning: try scrolling first
                    LlmStepDecision.InvokeTool("scroll_screen", mapOf("direction" to "down"))
                }
                else -> LlmStepDecision.Finish("Found Submit after scrolling and completed.")
            }
        }

        val planner = LlmPlanner(provider, maxSteps = 5)
        var clickAttempted = false

        val result = planner.planAndExecute("Submit form") { toolName, _ ->
            if (toolName == "click_ui_element" && !clickAttempted) {
                clickAttempted = true
                Pair(false, "Button 'Submit' not visible on current screen")
            } else {
                Pair(true, "Action succeeded")
            }
        }

        assertTrue(result.isSuccess)
        assertEquals(2, result.stepsExecuted)
        assertTrue("History should record failed observation", historyObservations.first().contains("not visible"))
    }

    @Test
    fun testConsequentialStepNeverAutoRetried() = runBlocking {
        val executionAttempts = AtomicInteger(0)

        val provider = FakeScriptedProvider { _, _ ->
            LlmStepDecision.InvokeTool(
                "whatsapp_send",
                mapOf("contact" to "Alice", "message" to "Hello Alice")
            )
        }

        val planner = LlmPlanner(provider, maxSteps = 5)
        val result = planner.planAndExecute("Message Alice") { _, _ ->
            executionAttempts.incrementAndGet()
            Pair(false, "Network connection dropped during message dispatch")
        }

        assertFalse("Mission should fail on unverified consequential action", result.isSuccess)
        assertEquals("CONSEQUENTIAL_UNVERIFIED", result.abortReason)
        assertEquals("Consequential action must NOT be retried automatically", 1, executionAttempts.get())
    }

    @Test
    fun testToolRegistryAliasesResolveToCanonicalName() = runBlocking {
        var executedTool = ""

        val provider = FakeScriptedProvider { _, _ ->
            LlmStepDecision.InvokeTool(
                "send_whatsapp_message", // legacy alias
                mapOf("contact" to "Bob", "message" to "Meeting at 5")
            )
        }

        val planner = LlmPlanner(provider, maxSteps = 2)
        planner.planAndExecute("Send message to Bob") { toolName, _ ->
            executedTool = toolName
            Pair(true, "Sent")
        }

        assertEquals("send_whatsapp_message alias should resolve to whatsapp_send", "whatsapp_send", executedTool)
    }

    @Test
    fun testCancellationViaKillSwitch() = runBlocking {
        val provider = FakeScriptedProvider { _, _ ->
            LlmStepDecision.InvokeTool("search_web", mapOf("query" to "test"))
        }

        val planner = LlmPlanner(provider, maxSteps = 5)
        planner.cancel() // Kill switch activated upfront

        val result = planner.planAndExecute("Search test") { _, _ ->
            Pair(true, "ok")
        }

        assertFalse(result.isSuccess)
        assertEquals("CANCELLED", result.abortReason)
    }
}
