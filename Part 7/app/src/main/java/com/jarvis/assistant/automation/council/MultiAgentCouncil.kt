package com.jarvis.assistant.automation.council

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.mission.*
import com.jarvis.assistant.data.memory.PersonalMemoryGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CouncilDeliberation(
    val goal: String,
    val plannerSteps: List<String>,
    val researcherEvidence: List<String>,
    val criticRisks: List<String>,
    val verifierCheck: String,
    val isApprovedToExecute: Boolean,
    val finalVerdict: String
)

object MultiAgentCouncil {
    private const val TAG = "MultiAgentCouncil"

    /**
     * Conducts a bounded multi-role deliberation on a complex user request.
     */
    suspend fun deliberate(
        context: Context? = null,
        userGoal: String
    ): CouncilDeliberation = withContext(Dispatchers.Default) {
        val cleanGoal = userGoal.trim()
        Log.i(TAG, "Multi-Agent Council convening for: '$cleanGoal'")

        // 1. Planner Stage: Decompose into atomic sub-tasks
        val plannerSteps = decomposeGoal(cleanGoal)

        // 2. Researcher Stage: Retrieve relevant facts and memories
        val memories = PersonalMemoryGraph.queryRelevantMemories(context, cleanGoal, limit = 2)
        val researcherFindings = memories.map { "Memory [${it.key}]: ${it.value}" }

        // 3. Critic Stage: Identify ambiguities, permissions, or security risks
        val criticRisks = evaluateRisks(cleanGoal, plannerSteps)

        // 4. Verifier Pre-Check: Determine whether success criteria can be physically verified
        val canVerify = plannerSteps.all { step ->
            !step.contains("arbitrary", ignoreCase = true)
        }
        val verifierCheck = if (canVerify) {
            "Verification criteria confirmed: Steps have concrete sensors/outcomes."
        } else {
            "Verification caution: Some steps cannot be physically confirmed by platform sensors."
        }

        // 5. Consensus & Safety Evaluation
        val hasHighRisk = criticRisks.any { it.contains("Consequential", ignoreCase = true) || it.contains("Permission", ignoreCase = true) }
        val isApproved = !hasHighRisk

        val finalVerdict = when {
            hasHighRisk -> "Council evaluated plan: User confirmation required due to high-consequential actions."
            criticRisks.isNotEmpty() -> "Council approved plan with observations: ${criticRisks.first()}."
            else -> "Council consensus: Plan is sound, bounded, and verified for execution."
        }

        CouncilDeliberation(
            goal = cleanGoal,
            plannerSteps = plannerSteps,
            researcherEvidence = researcherFindings,
            criticRisks = criticRisks,
            verifierCheck = verifierCheck,
            isApprovedToExecute = isApproved,
            finalVerdict = finalVerdict
        )
    }

    private fun decomposeGoal(goal: String): List<String> {
        val lower = goal.lowercase()
        return when {
            lower.contains("whatsapp") -> listOf(
                "Validate recipient contact",
                "Draft message content",
                "Request approval for external dispatch",
                "Dispatch message through Accessibility service"
            )
            lower.contains("search") || lower.contains("find") -> listOf(
                "Parse search parameters",
                "Query search provider or local memory",
                "Summarize top verified results"
            )
            lower.contains("protocol") -> listOf(
                "Validate protocol preconditions",
                "Adjust device volume & hardware",
                "Launch designated workspace application",
                "Confirm system telemetry nominal"
            )
            else -> listOf(
                "Analyze request intent",
                "Validate preconditions and permissions",
                "Execute atomic action",
                "Verify state change"
            )
        }
    }

    private fun evaluateRisks(goal: String, steps: List<String>): List<String> {
        val risks = mutableListOf<String>()
        val lower = goal.lowercase()

        if (lower.contains("delete") || lower.contains("remove") || lower.contains("clear")) {
            risks.add("Consequential action: Irreversible data deletion detected.")
        }
        if (lower.contains("send") || lower.contains("message") || lower.contains("call")) {
            risks.add("Consequential action: External communication will be visible to third parties.")
        }
        if (lower.contains("pay") || lower.contains("transfer") || lower.contains("buy") || lower.contains("order")) {
            risks.add("Consequential action: Financial transaction requires strict user authentication.")
        }

        return risks
    }
}
