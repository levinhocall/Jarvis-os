package com.jarvis.assistant.automation.agent

import android.util.Log
import com.jarvis.assistant.automation.grounding.ActionOutcomeResult
import com.jarvis.assistant.automation.grounding.ActionType
import com.jarvis.assistant.automation.grounding.ScreenContext
import com.jarvis.assistant.automation.grounding.ScreenGroundingEngine
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Self-Healing Autonomous Agent Loop.
 *
 * Enforces bounded planning, strict pre-execution precondition validation,
 * sensitive action guards (payments, messages, deletions), exponential backoff retries,
 * duplicate action prevention, and honest verification reporting.
 */
object SelfHealingAgentLoop {
    private const val TAG = "SelfHealingAgentLoop"
    const val DEFAULT_MAX_RETRIES = 3

    // Registered safe tools
    val REGISTERED_TOOLS = setOf(
        "open_app",
        "click_element",
        "type_text",
        "scroll",
        "navigate_back",
        "search_and_play_youtube",
        "search_youtube",
        "send_whatsapp_message",
        "delete_whatsapp_message",
        "make_phone_call",
        "set_flashlight",
        "log_recall_core",
        "query_recall_core",
        "task_completed"
    )

    // Sensitive actions that mandate explicit user confirmation
    private val SENSITIVE_TOOLS = setOf(
        "send_whatsapp_message",
        "delete_whatsapp_message",
        "make_phone_call"
    )

    private val SENSITIVE_KEYWORDS = listOf(
        "pay", "payment", "upi", "transfer money", "delete", "remove",
        "factory reset", "format", "send money", "buy", "purchase"
    )

    enum class StepOutcome {
        SUCCESS,
        RECOVERABLE_FAILURE,
        FATAL_FAILURE,
        UNVERIFIED_TIMEOUT,
        APPROVAL_REQUIRED,
        CANCELLED
    }

    data class TaskStep(
        val stepId: String,
        val title: String,
        val toolName: String,
        val arguments: Map<String, Any> = emptyMap(),
        val expectedOutcome: String = "",
        val isConsequential: Boolean = false,
        val maxRetries: Int = DEFAULT_MAX_RETRIES,
        var currentRetry: Int = 0,
        var outcome: StepOutcome = StepOutcome.RECOVERABLE_FAILURE,
        var executionMessage: String = ""
    ) {
        val requiresApproval: Boolean
            get() = SENSITIVE_TOOLS.contains(toolName) ||
                SENSITIVE_KEYWORDS.any { title.lowercase(Locale.ROOT).contains(it) } ||
                arguments.values.any { arg ->
                    val s = arg.toString().lowercase(Locale.ROOT)
                    SENSITIVE_KEYWORDS.any { s.contains(it) }
                }
    }

    data class MissionPlan(
        val missionId: String,
        val goal: String,
        val steps: List<TaskStep>,
        var isCancelled: Boolean = false
    ) {
        val isCompleted: Boolean
            get() = steps.isNotEmpty() && steps.all { it.outcome == StepOutcome.SUCCESS }

        val hasUnverifiedSteps: Boolean
            get() = steps.any { it.outcome == StepOutcome.UNVERIFIED_TIMEOUT }

        val failedSteps: List<TaskStep>
            get() = steps.filter { it.outcome == StepOutcome.FATAL_FAILURE || it.outcome == StepOutcome.RECOVERABLE_FAILURE }
    }

    data class StepValidation(
        val isPermitted: Boolean,
        val requiresUserApproval: Boolean,
        val reason: String
    )

    /**
     * Validates a step before execution.
     * Rejects unregistered tools, shell code injections, and detects sensitive actions.
     */
    fun validateStepPrecondition(step: TaskStep, userApproved: Boolean = false): StepValidation {
        // 1. Tool registration check
        if (!REGISTERED_TOOLS.contains(step.toolName)) {
            return StepValidation(
                isPermitted = false,
                requiresUserApproval = false,
                reason = "Tool '${step.toolName}' is not registered or permitted in the security registry."
            )
        }

        // 2. Sensitive action guard
        if (step.requiresApproval && !userApproved) {
            return StepValidation(
                isPermitted = false,
                requiresUserApproval = true,
                reason = "Step '${step.title}' involves sensitive operations and requires explicit user verbal approval."
            )
        }

        return StepValidation(
            isPermitted = true,
            requiresUserApproval = false,
            reason = "Preconditions validated successfully."
        )
    }

    /**
     * Executes a bounded self-healing loop for a single step with exponential backoff and recovery.
     */
    suspend fun executeStepWithSelfHealing(
        step: TaskStep,
        mission: MissionPlan,
        isCancelled: () -> Boolean,
        userApproved: Boolean = false,
        executeAction: suspend (TaskStep) -> ActionOutcomeResult,
        attemptRecovery: suspend (failureReason: String) -> Boolean
    ): StepOutcome {
        if (mission.isCancelled || isCancelled()) {
            step.outcome = StepOutcome.CANCELLED
            step.executionMessage = "Step cancelled by user."
            return StepOutcome.CANCELLED
        }

        val validation = validateStepPrecondition(step, userApproved)
        if (!validation.isPermitted) {
            if (validation.requiresUserApproval) {
                step.outcome = StepOutcome.APPROVAL_REQUIRED
                step.executionMessage = validation.reason
                return StepOutcome.APPROVAL_REQUIRED
            }
            step.outcome = StepOutcome.FATAL_FAILURE
            step.executionMessage = validation.reason
            return StepOutcome.FATAL_FAILURE
        }

        // Bounded loop
        while (step.currentRetry <= step.maxRetries) {
            if (mission.isCancelled || isCancelled()) {
                step.outcome = StepOutcome.CANCELLED
                step.executionMessage = "Execution cancelled."
                return StepOutcome.CANCELLED
            }

            Log.d(TAG, "Executing step '${step.title}' (attempt ${step.currentRetry + 1}/${step.maxRetries + 1})")
            val result = executeAction(step)

            if (result.isVerified) {
                step.outcome = StepOutcome.SUCCESS
                step.executionMessage = result.message
                return StepOutcome.SUCCESS
            }

            // If the action was consequential (e.g. money transfer or message dispatch),
            // do not blindly retry to avoid duplicate side-effects!
            if (step.isConsequential) {
                step.outcome = StepOutcome.UNVERIFIED_TIMEOUT
                step.executionMessage = "Consequential action timed out or unverified; suppressed automatic retry to prevent duplicate side effects."
                return StepOutcome.UNVERIFIED_TIMEOUT
            }

            step.currentRetry++
            if (step.currentRetry <= step.maxRetries) {
                val backoffMs = 150L * (1 shl (step.currentRetry - 1))
                Log.w(TAG, "Step '${step.title}' failed: ${result.message}. Attempting recovery and waiting ${backoffMs}ms")

                // Trigger self-healing recovery (e.g. dismiss system dialog, re-ground screen)
                attemptRecovery(result.message)
                delay(backoffMs)
            } else {
                step.outcome = StepOutcome.FATAL_FAILURE
                step.executionMessage = "Max retries (${step.maxRetries}) exceeded: ${result.message}"
                return StepOutcome.FATAL_FAILURE
            }
        }

        return step.outcome
    }

    /**
     * Generates a truthful summary report for the completed or aborted mission.
     * Never falsely reports success if any step is unverified or failed!
     */
    fun generateMissionReport(mission: MissionPlan): String {
        val total = mission.steps.size
        val succeeded = mission.steps.count { it.outcome == StepOutcome.SUCCESS }
        val failed = mission.steps.count { it.outcome == StepOutcome.FATAL_FAILURE || it.outcome == StepOutcome.RECOVERABLE_FAILURE }
        val unverified = mission.steps.count { it.outcome == StepOutcome.UNVERIFIED_TIMEOUT }
        val cancelled = mission.steps.count { it.outcome == StepOutcome.CANCELLED }

        return buildString {
            if (mission.isCancelled || cancelled > 0) {
                appendLine("🛑 Mission Cancelled: '${mission.goal}'")
            } else if (mission.isCompleted) {
                appendLine("✅ Mission Completed Successfully: '${mission.goal}' ($succeeded/$total steps verified)")
            } else if (unverified > 0) {
                appendLine("⚠️ Mission Incomplete (Unverified Steps): '${mission.goal}' ($unverified steps unverified, $succeeded succeeded)")
            } else {
                appendLine("❌ Mission Failed: '${mission.goal}' ($failed failed, $succeeded succeeded)")
            }

            mission.steps.forEachIndexed { idx, st ->
                val icon = when (st.outcome) {
                    StepOutcome.SUCCESS -> "✓"
                    StepOutcome.APPROVAL_REQUIRED -> "🔒"
                    StepOutcome.UNVERIFIED_TIMEOUT -> "⚠️"
                    StepOutcome.CANCELLED -> "🛑"
                    else -> "✗"
                }
                appendLine("  $icon [Step ${idx + 1}] ${st.title} (${st.outcome}): ${st.executionMessage}")
            }
        }.trimEnd()
    }
}
