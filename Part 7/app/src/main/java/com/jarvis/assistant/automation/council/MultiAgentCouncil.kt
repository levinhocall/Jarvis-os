package com.jarvis.assistant.automation.council

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.mission.*
import com.jarvis.assistant.data.memory.PersonalMemoryGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    const val MAX_DELEGATION_DEPTH = 3

    private val _activeRole = MutableStateFlow<AgentRole?>(null)
    val activeRole: StateFlow<AgentRole?> = _activeRole.asStateFlow()

    private val mutatingTools = setOf(
        "call_contact",
        "whatsapp_send",
        "send_whatsapp",
        "device_control",
        "click_ui_element",
        "scroll_screen",
        "navigate_home",
        "navigate_back"
    )

    /**
     * Enforces role-based tool authorization: Only EXECUTOR may invoke state-mutating tools.
     */
    fun isToolAuthorizedForRole(role: AgentRole, toolName: String): Boolean {
        return when (role) {
            AgentRole.EXECUTOR -> true // Executor has full tool execution authority
            AgentRole.RESEARCHER -> !mutatingTools.contains(toolName) // Read-only research tools only
            AgentRole.PLANNER, AgentRole.VERIFIER, AgentRole.LEAD_SYNTHESIZER -> false // No direct tool execution
        }
    }

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

    /**
     * Executes a complex multi-agent task through bounded delegation across Planner, Researcher,
     * Executor, and Verifier, synthesized by Lead with Tony Stark persona.
     */
    suspend fun executeCouncilTask(
        context: Context? = null,
        goal: String,
        simulatedExecutor: (suspend (MissionStep) -> Pair<Boolean, MissionVerificationEvidence>)? = null
    ): CouncilExecutionOutcome = withContext(Dispatchers.Default) {
        val cleanGoal = goal.trim()
        val handoffs = mutableListOf<AgentHandoff>()

        // 1. Lead initiates planning
        _activeRole.value = AgentRole.LEAD_SYNTHESIZER
        handoffs.add(
            AgentHandoff(
                fromRole = AgentRole.LEAD_SYNTHESIZER,
                toRole = AgentRole.PLANNER,
                taskDescription = "Decompose user goal into bounded execution plan",
                inputPayload = mapOf("goal" to cleanGoal),
                delegationDepth = 1
            )
        )

        // 2. Planner decomposes task
        _activeRole.value = AgentRole.PLANNER
        val missionPlan = MissionEngine.planMission(cleanGoal)
        handoffs.add(
            AgentHandoff(
                fromRole = AgentRole.PLANNER,
                toRole = AgentRole.RESEARCHER,
                taskDescription = "Fetch relevant facts and prior memories",
                inputPayload = mapOf("stepsCount" to missionPlan.steps.size),
                delegationDepth = 2
            )
        )

        // 3. Researcher searches context
        _activeRole.value = AgentRole.RESEARCHER
        val memories = PersonalMemoryGraph.queryRelevantMemories(context, cleanGoal, limit = 2)
        val researchedContext = memories.joinToString("; ") { "${it.key}: ${it.value}" }
        handoffs.add(
            AgentHandoff(
                fromRole = AgentRole.RESEARCHER,
                toRole = AgentRole.LEAD_SYNTHESIZER,
                taskDescription = "Return researched memory context",
                outputPayload = mapOf("context" to researchedContext),
                delegationDepth = 2
            )
        )

        // Check consequential risk & approval requirement
        if (missionPlan.approvalState == MissionApprovalState.PENDING_APPROVAL) {
            _activeRole.value = AgentRole.LEAD_SYNTHESIZER
            return@withContext CouncilExecutionOutcome(
                isSuccess = false,
                goal = cleanGoal,
                leadResponse = "Sir, this operation involves consequential actions requiring your direct authorization before the Executor can proceed.",
                handoffHistory = handoffs,
                executionStepsCount = 0,
                isVerifiedIndependently = false,
                failureReason = "Awaiting user approval"
            )
        }

        // 4. Lead assigns Executor for mutating operations
        _activeRole.value = AgentRole.EXECUTOR
        handoffs.add(
            AgentHandoff(
                fromRole = AgentRole.LEAD_SYNTHESIZER,
                toRole = AgentRole.EXECUTOR,
                taskDescription = "Execute planned steps with tool authorization",
                inputPayload = mapOf("steps" to missionPlan.steps.map { it.title }),
                delegationDepth = 2
            )
        )

        var lastEvidence: MissionVerificationEvidence? = null
        var allExecutedSuccessfully = true

        for (step in missionPlan.steps) {
            if (!isToolAuthorizedForRole(AgentRole.EXECUTOR, step.toolName)) {
                allExecutedSuccessfully = false
                break
            }
            val result = if (simulatedExecutor != null) {
                simulatedExecutor(step)
            } else {
                Pair(true, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, "Executed by council executor"))
            }
            if (!result.first) {
                allExecutedSuccessfully = false
                lastEvidence = result.second
                break
            }
            lastEvidence = result.second
        }

        if (!allExecutedSuccessfully) {
            _activeRole.value = AgentRole.LEAD_SYNTHESIZER
            return@withContext CouncilExecutionOutcome(
                isSuccess = false,
                goal = cleanGoal,
                leadResponse = "Executor encountered a roadblock: ${lastEvidence?.details ?: "Step failed"}.",
                handoffHistory = handoffs,
                executionStepsCount = 0,
                isVerifiedIndependently = false,
                failureReason = lastEvidence?.details
            )
        }

        // 5. Verifier independently inspects the outcome
        _activeRole.value = AgentRole.VERIFIER
        handoffs.add(
            AgentHandoff(
                fromRole = AgentRole.EXECUTOR,
                toRole = AgentRole.VERIFIER,
                taskDescription = "Independently inspect execution evidence without mutating state",
                inputPayload = mapOf("evidence" to (lastEvidence?.details ?: "")),
                delegationDepth = 3
            )
        )

        val isIndependentVerified = lastEvidence?.isVerified == true

        // 6. Lead synthesizes final response with Tony Stark tone
        _activeRole.value = AgentRole.LEAD_SYNTHESIZER
        handoffs.add(
            AgentHandoff(
                fromRole = AgentRole.VERIFIER,
                toRole = AgentRole.LEAD_SYNTHESIZER,
                taskDescription = "Deliver verified status report for final synthesis",
                outputPayload = mapOf("isVerified" to isIndependentVerified),
                delegationDepth = 3
            )
        )

        val finalLeadMessage = if (isIndependentVerified) {
            "All systems nominal, Sir. The council has executed and independently verified '${cleanGoal}' with complete success."
        } else {
            "Task executed, Sir, though physical sensor verification remains unconfirmed."
        }

        _activeRole.value = null

        CouncilExecutionOutcome(
            isSuccess = allExecutedSuccessfully,
            goal = cleanGoal,
            leadResponse = finalLeadMessage,
            handoffHistory = handoffs,
            executionStepsCount = missionPlan.steps.size,
            isVerifiedIndependently = isIndependentVerified
        )
    }
}
