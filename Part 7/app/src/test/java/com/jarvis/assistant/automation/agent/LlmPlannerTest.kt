package com.jarvis.assistant.automation.agent

import com.jarvis.assistant.automation.tooling.ToolDefinition
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
    fun `cloud history excludes private memory arguments and observations`() {
        for (tool in listOf("query_recall_core", "log_recall_core")) {
            val record = LlmPlannerStepRecord(1, tool, mapOf("key" to "private-key"), true, "private-value")
            val line = cloudHistoryLine(record)
            assertFalse(line.contains("private-key"))
            assertFalse(line.contains("private-value"))
            assertTrue(line.contains("Verified=true"))
            assertTrue(record.observation.contains("private-value")) // Local evidence is preserved.
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

        assertFalse("A later successful step cannot erase an earlier unverified action", result.isSuccess)
        assertEquals("UNVERIFIED_FINISH", result.abortReason)
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

        assertFalse("Consequential action must wait for explicit approval", result.isSuccess)
        assertEquals("APPROVAL_REQUIRED", result.abortReason)
        assertEquals("Consequential action must not execute before approval", 0, executionAttempts.get())
        assertEquals("whatsapp_send", result.pendingApprovalTool)
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
        val result = planner.planAndExecute("Send message to Bob") { toolName, _ ->
            executedTool = toolName
            Pair(true, "Sent")
        }

        assertEquals("send_whatsapp_message alias should resolve to canonical name", "whatsapp_send", result.pendingApprovalTool)
        assertEquals("High-risk alias must not execute before approval", "", executedTool)
    }

    @Test
    fun testFinishWithoutVerifiedExecutionEvidenceDoesNotSucceed() = runBlocking {
        val provider = FakeScriptedProvider { _, _ ->
            LlmStepDecision.Finish("Everything is complete.")
        }
        val result = LlmPlanner(provider).planAndExecute("Do something") { _, _ ->
            Pair(true, "This executor should not be called.")
        }

        assertFalse(result.isSuccess)
        assertEquals("UNVERIFIED_FINISH", result.abortReason)
        assertTrue(result.history.isEmpty())
    }

    @Test
    fun testApprovedConsequentialCallExecutesOnceThenPlannerContinuesWithVerifiedHistory() = runBlocking {
        val priorHistory = listOf(
            LlmPlannerStepRecord(
                stepIndex = 1,
                toolName = "open_app",
                arguments = mapOf("app_name" to "WhatsApp"),
                isVerified = true,
                observation = "WhatsApp foreground package verified."
            )
        )
        val provider = FakeScriptedProvider { _, history ->
            assertEquals(2, history.size)
            assertTrue(history.all { it.isVerified })
            LlmStepDecision.Finish("Approved action and mission verified.")
        }
        val attempts = AtomicInteger(0)
        val approved = ApprovedLlmToolCall(
            toolName = "termux_execute",
            arguments = mapOf("command" to "git status --short")
        )

        val result = LlmPlanner(provider).planAndExecute(
            goal = "Send a WhatsApp message",
            initialHistory = priorHistory,
            approvedToolCall = approved
        ) { toolName, args ->
            assertEquals("termux_execute", toolName)
            assertEquals(approved.arguments, args)
            attempts.incrementAndGet()
            true to "Termux command output verified by test executor."
        }

        assertTrue(result.isSuccess)
        assertEquals(1, attempts.get())
        assertEquals(2, result.history.size)
        assertTrue(result.history.all { it.isVerified })
    }

    @Test
    fun testPauseStopsAtSafeBoundaryAndPreservesVerifiedHistory() = runBlocking {
        val provider = FakeScriptedProvider { _, _ ->
            LlmStepDecision.InvokeTool("search_web", mapOf("query" to "safe pause test"))
        }
        val planner = LlmPlanner(provider, maxSteps = 1)
        val result = planner.planAndExecute("Pause safely") { _, _ ->
            planner.requestPause()
            Pair(true, "Search result verified")
        }

        assertFalse(result.isSuccess)
        assertEquals("PAUSED", result.abortReason)
        assertEquals(1, result.stepsExecuted)
        assertTrue(result.history.single().isVerified)
    }

    @Test
    fun testCancellationDuringPlanningPreventsNextToolInvocation() = runBlocking {
        lateinit var planner: LlmPlanner
        var executionCount = 0
        val provider = object : LlmFunctionCallProvider {
            override val providerId: String = "cancel-during-plan-test"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun predictStep(
                goal: String,
                history: List<LlmPlannerStepRecord>,
                availableTools: List<ToolDefinition>
            ): LlmStepDecision {
                planner.cancel()
                return LlmStepDecision.InvokeTool("search_web", mapOf("query" to "must not run"))
            }
        }
        planner = LlmPlanner(provider)
        val result = planner.planAndExecute("Cancel before execution") { _, _ ->
            executionCount++
            Pair(true, "Unexpected execution")
        }

        assertEquals("CANCELLED", result.abortReason)
        assertEquals(0, executionCount)
    }

    @Test
    fun testFailedPreExecutionCheckpointPreventsToolInvocation() = runBlocking {
        val provider = FakeScriptedProvider { _, _ ->
            LlmStepDecision.InvokeTool("search_web", mapOf("query" to "checkpoint test"))
        }
        val planner = LlmPlanner(provider, maxSteps = 2)
        var executions = 0
        val result = planner.planAndExecute(
            goal = "Search with durable checkpoint",
            onStepStarted = { _, _ -> throw IllegalStateException("database unavailable") }
        ) { _, _ ->
            executions++
            true to "Unexpected execution"
        }

        assertEquals("CHECKPOINT_FAILED_BEFORE_EXECUTION", result.abortReason)
        assertEquals(0, executions)
    }

    @Test
    fun testFailedPostExecutionCheckpointReportsUnverifiedResult() = runBlocking {
        val provider = FakeScriptedProvider { _, _ ->
            LlmStepDecision.InvokeTool("search_web", mapOf("query" to "checkpoint test"))
        }
        val planner = LlmPlanner(provider, maxSteps = 2)
        var executions = 0
        val result = planner.planAndExecute(
            goal = "Search with durable checkpoint",
            onStepRecorded = { throw IllegalStateException("database unavailable") }
        ) { _, _ ->
            executions++
            true to "Search action returned"
        }

        assertEquals("CHECKPOINT_FAILED_AFTER_EXECUTION", result.abortReason)
        assertEquals(1, executions)
        assertEquals(1, result.history.size)
        assertTrue(result.history.single().isVerified)
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
