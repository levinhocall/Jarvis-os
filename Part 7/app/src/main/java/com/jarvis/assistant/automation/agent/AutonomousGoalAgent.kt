package com.jarvis.assistant.automation.agent

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.command.CommandExecutor
import com.jarvis.assistant.automation.command.JarvisCommand
import com.jarvis.assistant.vision.TacticalScannerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/**
 * Bounded, serial autonomous goal runner. Cancellation is tied to the actual
 * caller Job; a new goal cannot silently reset another goal's cancellation flag.
 */
object AutonomousGoalAgent {
    private const val TAG = "AutonomousGoalAgent"
    private const val MAX_STEPS = 6
    private val goalMutex = Mutex()

    @Volatile
    private var activeGoalJob: Job? = null

    fun cancelActiveGoal(reason: String = "User requested stop") {
        Log.i(TAG, "Cancelling active goal execution")
        activeGoalJob?.cancel(CancellationException(reason))
    }

    suspend fun executeGoal(
        rawGoal: String,
        context: Context,
        commandExecutor: CommandExecutor,
        onProgress: ((String) -> Unit)? = null
    ): GoalExecutionResult = goalMutex.withLock {
        val callerJob = currentCoroutineContext()[Job]
        activeGoalJob = callerJob
        try {
            val cleanGoal = rawGoal.trim()
            if (cleanGoal.isBlank()) {
                return@withLock GoalExecutionResult(false, "Please provide a goal to execute.", 0, 0)
            }

            // Avoid logging user-provided goals; they may contain private searches.
            Log.i(TAG, "Beginning bounded autonomous goal execution")
            val plan = planStepsForGoal(cleanGoal)
            if (plan.isEmpty()) {
                return@withLock GoalExecutionResult(false, "No safe executable steps could be planned.", 0, 0)
            }

            var completedSteps = 0
            for (step in plan) {
                currentCoroutineContext().ensureActive()
                onProgress?.invoke("Executing step ${step.stepIndex + 1}/${plan.size}: ${step.description}")

                val commandResult = try {
                    withContext(Dispatchers.Main) { commandExecutor.execute(step.command) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Goal step execution failed", error)
                    return@withLock GoalExecutionResult(
                        false,
                        "Step ${step.stepIndex + 1} failed. Execution stopped without claiming the goal was completed.",
                        completedSteps,
                        plan.size
                    )
                }
                step.executed = true

                // UI transition settling is cancellable and does not block the main thread.
                kotlinx.coroutines.delay(900L)
                currentCoroutineContext().ensureActive()

                val commandRejected = commandResult.isBlank() ||
                    listOf("unable", "cannot", "could not", "failed", "error", "denied")
                        .any { commandResult.contains(it, ignoreCase = true) }
                val verified = !commandRejected && verifyStepOutcome()
                step.verified = verified
                if (!verified) {
                    return@withLock GoalExecutionResult(
                        false,
                        "Step ${step.stepIndex + 1} could not be verified on the active screen. Remaining steps were not executed.",
                        completedSteps,
                        plan.size
                    )
                }
                completedSteps++
            }

            currentCoroutineContext().ensureActive()
            val scan = try {
                TacticalScannerManager.scanCurrentScreen(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Final screen scan failed", error)
                null
            }

            val verdict = when {
                scan?.success == true && scan.detectedPrice != null ->
                    "All $completedSteps planned steps were verified. Found ${scan.detectedProduct} at ${scan.detectedPrice} on ${scan.platform}; saved to Recall Core."
                scan?.success == true ->
                    "All $completedSteps planned steps were verified. Screen content was read, but no price was detected."
                else ->
                    "All $completedSteps planned steps were verified. Final screen scan was unavailable; inspect the destination to confirm the requested result."
            }
            GoalExecutionResult(true, verdict, completedSteps, plan.size)
        } catch (cancelled: CancellationException) {
            Log.i(TAG, "Goal execution cancelled")
            throw cancelled
        } finally {
            if (activeGoalJob === callerJob) activeGoalJob = null
        }
    }

    private fun planStepsForGoal(goal: String): List<GoalStep> {
        val lower = goal.lowercase()
        val platform = when {
            lower.contains("flipkart") -> "flipkart"
            lower.contains("amazon") -> "amazon"
            else -> "google"
        }
        val cleanedQuery = goal.replace(
            Regex("(?i)(flipkart|amazon|google|par|pe|search karke|search karo|sabse sasta|option batao|dikhao)"),
            ""
        ).trim()
        val searchQuery = cleanedQuery.ifBlank { goal }

        return listOf(
            GoalStep(
                stepIndex = 0,
                description = "Open $platform search for $searchQuery",
                command = JarvisCommand.ContextualSearch(query = searchQuery, platform = platform)
            ),
            GoalStep(
                stepIndex = 1,
                description = "Scroll results to inspect additional content",
                command = JarvisCommand.ScrollScreen(direction = "down", distance = "medium")
            )
        ).take(MAX_STEPS)
    }

    /** Reads a fresh accessibility snapshot and always releases the owned root node. */
    private fun verifyStepOutcome(): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        val root = service.getActiveRootNode() ?: return false
        return try {
            root.childCount > 0 ||
                !root.text.isNullOrBlank() ||
                !root.contentDescription.isNullOrBlank()
        } finally {
            root.recycle()
        }
    }
}
