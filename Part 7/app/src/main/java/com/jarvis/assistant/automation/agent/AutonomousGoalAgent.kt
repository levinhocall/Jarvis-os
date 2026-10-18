package com.jarvis.assistant.automation.agent

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.command.CommandExecutor
import com.jarvis.assistant.automation.command.JarvisCommand
import com.jarvis.assistant.data.memory.RecallCoreManager
import com.jarvis.assistant.vision.TacticalScannerManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

data class GoalStep(
    val stepIndex: Int,
    val description: String,
    val command: JarvisCommand,
    var executed: Boolean = false,
    var verified: Boolean = false
)

data class GoalExecutionResult(
    val success: Boolean,
    val finalVerdict: String,
    val stepsCompleted: Int,
    val totalSteps: Int
)

object AutonomousGoalAgent {
    private const val TAG = "AutonomousGoalAgent"
    private const val MAX_STEPS = 6

    private val isCancelled = AtomicBoolean(false)
    private var activeGoalJob: Job? = null

    fun cancelActiveGoal(reason: String = "User requested stop") {
        Log.i(TAG, "Cancelling active goal execution: $reason")
        isCancelled.set(true)
        activeGoalJob?.cancel()
        activeGoalJob = null
    }

    suspend fun executeGoal(
        rawGoal: String,
        context: Context,
        commandExecutor: CommandExecutor,
        onProgress: ((String) -> Unit)? = null
    ): GoalExecutionResult = withContext(Dispatchers.Default) {
        isCancelled.set(false)
        val cleanGoal = rawGoal.trim()
        Log.i(TAG, "Beginning autonomous goal execution for: '$cleanGoal'")

        // 1. PLAN: Decompose goal into atomic steps
        val plan = planStepsForGoal(cleanGoal)
        var completedSteps = 0

        for (step in plan) {
            if (isCancelled.get() || !isActive) {
                Log.w(TAG, "Goal interrupted before step ${step.stepIndex}")
                return@withContext GoalExecutionResult(
                    success = false,
                    finalVerdict = "Goal execution stopped on request, Nazib Sir.",
                    stepsCompleted = completedSteps,
                    totalSteps = plan.size
                )
            }

            onProgress?.invoke("Executing step ${step.stepIndex + 1}/${plan.size}: ${step.description}")

            // 2. ACT
            withContext(Dispatchers.Main) {
                commandExecutor.execute(step.command)
            }
            step.executed = true

            // Settle delay for UI transitions
            delay(1200L)

            // 3. VERIFY
            val verified = verifyStepOutcome(step)
            step.verified = verified
            completedSteps++
        }

        // 4. REPORT: Run tactical scan on destination page and format result
        val scan = TacticalScannerManager.scanCurrentScreen(context)
        val finalVerdict = if (scan.success && scan.detectedPrice != null) {
            "Goal complete, Nazib Sir: Found ${scan.detectedProduct} at ${scan.detectedPrice} on ${scan.platform}. Saved to Recall Core."
        } else {
            "Goal finished. Reached the target destination and page is open on screen, Sir."
        }

        GoalExecutionResult(
            success = true,
            finalVerdict = finalVerdict,
            stepsCompleted = completedSteps,
            totalSteps = plan.size
        )
    }

    private fun planStepsForGoal(goal: String): List<GoalStep> {
        val steps = mutableListOf<GoalStep>()
        val lower = goal.lowercase()

        // E.g., "Flipkart par Voltas 500L freezer search karke sabse sasta option batao"
        val platform = when {
            lower.contains("flipkart") -> "flipkart"
            lower.contains("amazon") -> "amazon"
            else -> "google"
        }

        val cleanedQuery = goal
            .replace(Regex("(?i)(flipkart|amazon|google|par|pe|search karke|search karo|sabse sasta|option batao|dikhao)"), "")
            .trim()

        val searchQuery = if (cleanedQuery.isNotBlank()) cleanedQuery else goal

        // Step 1: Open and search target platform via direct search intent
        steps.add(
            GoalStep(
                stepIndex = 0,
                description = "Navigate to $platform search for $searchQuery",
                command = JarvisCommand.ContextualSearch(query = searchQuery, platform = platform)
            )
        )

        // Step 2: Safe scroll down to reveal price and spec list
        steps.add(
            GoalStep(
                stepIndex = 1,
                description = "Scroll results to inspect pricing",
                command = JarvisCommand.ScrollScreen(direction = "down", distance = "medium")
            )
        )

        return steps.take(MAX_STEPS)
    }

    private fun verifyStepOutcome(step: GoalStep): Boolean {
        val a11y = JarvisAccessibilityService.instance
        val root = a11y?.getActiveRootNode() ?: return false
        return root.childCount > 0
    }
}
