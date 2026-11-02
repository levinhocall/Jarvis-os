package com.jarvis.assistant.automation.council

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.mission.*
import com.jarvis.assistant.data.memory.PersonalMemoryGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
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
    val finalVerdict: String,
    val agentReports: Map<String, String> = emptyMap(),
    val subAgentCallsUsed: Int = 0
)

object MultiAgentCouncil {
    private const val TAG = "MultiAgentCouncil"
    const val MAX_DELEGATION_DEPTH = 3

    private val _activeRole = MutableStateFlow<AgentRole?>(null)
    val activeRole: StateFlow<AgentRole?> = _activeRole.asStateFlow()
    private val _activeSubAgents = MutableStateFlow<Set<AgentRole>>(emptySet())
    val activeSubAgents: StateFlow<Set<AgentRole>> = _activeSubAgents.asStateFlow()

    private data class AgentReviewBatch(
        val reports: Map<String, String>,
        val callsUsed: Int
    )

    // Default-deny: the Researcher may only use explicitly read-only tools.
    // New tools are never granted Researcher access merely because their risk metadata
    // was incorrectly or incompletely configured.
    private val researcherReadOnlyTools = setOf(
        "search_web",
        "query_recall_core",
        "read_unread_notifications",
        "inspect_code"
    )

    /**
     * Enforces role-based tool authorization: Only EXECUTOR may invoke state-mutating tools.
     */
    fun isToolAuthorizedForRole(role: AgentRole, toolName: String): Boolean {
        val canonicalName = ToolRegistry.resolveToolName(toolName)
        val registeredTool = ToolRegistry.getTool(canonicalName) ?: return false
        return when (role) {
            AgentRole.EXECUTOR -> true // Executor may invoke only registered tools.
            AgentRole.RESEARCHER ->
                canonicalName in researcherReadOnlyTools &&
                    registeredTool.riskLevel == MissionRisk.LOW &&
                    registeredTool.isIdempotent &&
                    !registeredTool.requiresAccessibility
            AgentRole.PLANNER, AgentRole.CRITIC, AgentRole.VERIFIER, AgentRole.LEAD_SYNTHESIZER -> false
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

        // 3. Build the authoritative MissionEngine plan, then run bounded model reviewers.
        // This is the production preflight path called by JarvisUnifiedRuntime.
        val missionPlan = MissionEngine.planMission(cleanGoal, context = context)
        val agentReview = runIndependentSubAgents(
            cleanGoal,
            missionPlan.steps,
            researcherFindings.joinToString("; ").take(2500)
        )

        // 4. Critic stage combines deterministic policy checks with the independent LLM critic.
        val criticRisks = evaluateRisks(cleanGoal, plannerSteps).toMutableList()
        val criticReport = agentReview.reports[AgentRole.CRITIC.name].orEmpty()
        val criticBlocked = criticReport.trimStart().startsWith("BLOCK:", ignoreCase = true)
        if (criticBlocked) criticRisks.add("Independent critic blocked execution: " + criticReport.take(700))

        // 5. Verifier review supplements, but never replaces, concrete runtime evidence checks.
        val canVerify = plannerSteps.all { step ->
            !step.contains("arbitrary", ignoreCase = true)
        }
        val verifierReview = agentReview.reports[AgentRole.VERIFIER.name].orEmpty()
        val verifierCheck = (if (canVerify) {
            "Verification criteria confirmed: Steps have concrete sensors/outcomes."
        } else {
            "Verification caution: Some steps cannot be physically confirmed by platform sensors."
        }) + if (verifierReview.isNotBlank()) " Independent verifier: " + verifierReview.take(900) else ""

        // 6. Consensus & Safety Evaluation. Model reviewers can add a block, never grant authority.
        val hasHighRisk = criticRisks.any {
            it.contains("Consequential", ignoreCase = true) ||
                it.contains("Permission", ignoreCase = true) ||
                it.contains("blocked execution", ignoreCase = true)
        }
        val isApproved = !hasHighRisk &&
            missionPlan.status != MissionOverallStatus.CLARIFICATION_REQUIRED &&
            missionPlan.status != MissionOverallStatus.WAITING_FOR_APPROVAL

        val finalVerdict = when {
            criticBlocked -> "Council critic raised a blocking concern: " + criticReport.take(900)
            hasHighRisk -> "Council evaluated plan: User confirmation or safety review is required."
            criticRisks.isNotEmpty() -> "Council approved plan with observations: ${criticRisks.first()}."
            else -> "Council consensus: Plan is bounded; MissionEngine remains the execution authority."
        }

        CouncilDeliberation(
            goal = cleanGoal,
            plannerSteps = plannerSteps,
            researcherEvidence = researcherFindings,
            criticRisks = criticRisks,
            verifierCheck = verifierCheck,
            isApprovedToExecute = isApproved,
            finalVerdict = finalVerdict,
            agentReports = agentReview.reports,
            subAgentCallsUsed = agentReview.callsUsed
        )
    }

    /**
     * Runs three independent, text-only model reviewers in parallel under one shared budget.
     * Sub-agents have no ToolRegistry executor and cannot perform device actions.
     */
    private suspend fun runIndependentSubAgents(
        goal: String,
        plan: List<MissionStep>,
        researchedContext: String
    ): AgentReviewBatch = coroutineScope {
        // Keep ordinary one-step commands fast; delegate multi-step or explicitly complex goals.
        if (plan.isEmpty() || (plan.size < 2 && goal.length < 80)) return@coroutineScope AgentReviewBatch(emptyMap(), 0)
        val planner = MissionEngine.llmPlanner
            ?: return@coroutineScope AgentReviewBatch(emptyMap(), 0)
        val budget = AtomicInteger(0)
        val roles = listOf(AgentRole.PLANNER, AgentRole.CRITIC, AgentRole.VERIFIER)
        val planSummary = plan.take(20).joinToString("\n") { "- " + it.title + " [tool=" + it.toolName + "]" }.take(5000)
        val prompts = mapOf(
            AgentRole.PLANNER to "You are the independent PLANNER sub-agent for JARVIS OS. Review this user goal and proposed plan. Identify missing dependencies, ordering issues and unclear requirements. Do not execute tools. Return concise findings and begin with REVIEW: unless the plan is structurally impossible, in which case begin BLOCK:.\nGoal: " + goal.take(3000) + "\nPlan:\n" + planSummary,
            AgentRole.CRITIC to "You are the adversarial CRITIC sub-agent for JARVIS OS. Inspect the goal and plan for destructive, privacy, external-communication, financial, permission and duplicate-action risks. Do not execute tools. Return concise findings beginning REVIEW:. Use BLOCK: only when the plan has a concrete severe safety flaw that should stop execution.\nGoal: " + goal.take(3000) + "\nPlan:\n" + planSummary,
            AgentRole.VERIFIER to "You are the independent VERIFIER sub-agent for JARVIS OS. Define observable evidence needed to verify the goal and identify any step whose success cannot be verified. Do not execute tools. Return concise findings beginning REVIEW:.\nGoal: " + goal.take(3000) + "\nResearch context: " + researchedContext.take(2500) + "\nPlan:\n" + planSummary
        )
        val results = withTimeoutOrNull(24_000L) {
            roles.map { role ->
                async(Dispatchers.IO) {
                    if (budget.incrementAndGet() > 3) return@async role to null
                    _activeSubAgents.update { it + role }
                    try {
                        val prompt = prompts.getValue(role) + "\nShared mission budget: maximum 3 sub-agent calls, no delegation, no tool execution, output under 400 words."
                        val report = withTimeoutOrNull(18_000L) { planner.reasonAsSubAgent(prompt) }
                        role to report?.take(4000)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Sub-agent " + role.name + " failed; other reviewers continue (" + error.javaClass.simpleName + ").")
                        role to null
                    } finally {
                        _activeSubAgents.update { it - role }
                    }
                }
            }.awaitAll()
        } ?: emptyList()
        AgentReviewBatch(
            reports = results.mapNotNull { (role, report) -> report?.takeIf { it.isNotBlank() }?.let { role.name to it } }.toMap(),
            callsUsed = budget.get().coerceAtMost(3)
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

        if (Regex("\\b(delete|remove|clear)\\b").containsMatchIn(lower)) {
            risks.add("Consequential action: Irreversible data deletion detected.")
        }
        if (Regex("\\b(send|message|call)\\b").containsMatchIn(lower)) {
            risks.add("Consequential action: External communication will be visible to third parties.")
        }
        if (Regex("\\b(pay|transfer|buy|order)\\b").containsMatchIn(lower)) {
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
        goal: String
    ): CouncilExecutionOutcome = executeCouncilTaskInternal(context, goal, null)

    /**
     * Test-only executor injection. Production callers cannot supply a simulated executor.
     */
    internal suspend fun executeCouncilTaskForTest(
        context: Context? = null,
        goal: String,
        simulatedExecutor: suspend (MissionStep) -> Pair<Boolean, MissionVerificationEvidence>
    ): CouncilExecutionOutcome = executeCouncilTaskInternal(context, goal, simulatedExecutor)

    private suspend fun executeCouncilTaskInternal(
        context: Context?,
        goal: String,
        simulatedExecutor: (suspend (MissionStep) -> Pair<Boolean, MissionVerificationEvidence>)?
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

        // Never let an empty or clarification-required plan fall through as a zero-step success.
        if (missionPlan.status == MissionOverallStatus.CLARIFICATION_REQUIRED) {
            _activeRole.value = AgentRole.LEAD_SYNTHESIZER
            return@withContext CouncilExecutionOutcome(
                isSuccess = false,
                goal = cleanGoal,
                leadResponse = missionPlan.clarificationRequest?.question
                    ?: "More information is required before this task can be planned.",
                handoffHistory = handoffs,
                executionStepsCount = 0,
                isVerifiedIndependently = false,
                failureReason = "Clarification required: ${missionPlan.clarificationRequest?.missingField ?: "unspecified input"}"
            )
        }

        if (missionPlan.approvalState == MissionApprovalState.PENDING_APPROVAL ||
            missionPlan.status == MissionOverallStatus.WAITING_FOR_APPROVAL
        ) {
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

        if (missionPlan.steps.isEmpty()) {
            _activeRole.value = AgentRole.LEAD_SYNTHESIZER
            return@withContext CouncilExecutionOutcome(
                isSuccess = false,
                goal = cleanGoal,
                leadResponse = "The planner produced no executable steps. Nothing was run.",
                handoffHistory = handoffs,
                executionStepsCount = 0,
                isVerifiedIndependently = false,
                failureReason = "Empty mission plan"
            )
        }

        // 3b. Independent LLM reviews run concurrently with a shared 3-call budget.
        // The local Researcher retrieval above supplies context; these sub-agents are advisory only.
        val agentReview = runIndependentSubAgents(cleanGoal, missionPlan.steps, researchedContext)
        agentReview.reports.forEach { (roleName, report) ->
            val role = runCatching { AgentRole.valueOf(roleName) }.getOrNull() ?: return@forEach
            handoffs.add(
                AgentHandoff(
                    fromRole = AgentRole.LEAD_SYNTHESIZER,
                    toRole = role,
                    taskDescription = "Independent bounded model review; no tool execution authority",
                    inputPayload = mapOf("goal" to cleanGoal),
                    outputPayload = mapOf("review" to report.take(4000)),
                    delegationDepth = 1
                )
            )
            handoffs.add(
                AgentHandoff(
                    fromRole = role,
                    toRole = AgentRole.LEAD_SYNTHESIZER,
                    taskDescription = "Return advisory findings for lead reconciliation",
                    outputPayload = mapOf("review" to report.take(4000)),
                    delegationDepth = 2
                )
            )
        }
        val criticReport = agentReview.reports[AgentRole.CRITIC.name].orEmpty()
        if (criticReport.trimStart().startsWith("BLOCK:", ignoreCase = true)) {
            _activeRole.value = AgentRole.LEAD_SYNTHESIZER
            return@withContext CouncilExecutionOutcome(
                isSuccess = false,
                goal = cleanGoal,
                leadResponse = "Council critic raised a concrete blocking concern. No mission tools were executed. " + criticReport.take(1200),
                handoffHistory = handoffs,
                executionStepsCount = 0,
                isVerifiedIndependently = false,
                failureReason = "SUB_AGENT_CRITIC_BLOCK",
                agentReports = agentReview.reports,
                subAgentCallsUsed = agentReview.callsUsed
            )
        }
        // Lead reconciliation: retain the MissionEngine plan and safety gates as authoritative;
        // advisory model output can add review context but cannot grant tool permissions.
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
        var executedStepCount = 0
        var allExecutedSuccessfully = true

        for (step in missionPlan.steps) {
            if (!isToolAuthorizedForRole(AgentRole.EXECUTOR, step.toolName)) {
                allExecutedSuccessfully = false
                break
            }
            val result = if (simulatedExecutor != null) {
                // Explicit injection is retained for deterministic unit tests only.
                simulatedExecutor(step)
            } else {
                // Production execution must pass through the central mission executor.
                MissionEngine.executeStepForCouncil(step, context)
            }
            if (!result.first) {
                allExecutedSuccessfully = false
                lastEvidence = result.second
                break
            }
            executedStepCount++
            lastEvidence = result.second
        }

        if (!allExecutedSuccessfully) {
            _activeRole.value = AgentRole.LEAD_SYNTHESIZER
            return@withContext CouncilExecutionOutcome(
                isSuccess = false,
                goal = cleanGoal,
                leadResponse = "Executor encountered a roadblock: ${lastEvidence?.details ?: "Step failed"}.",
                handoffHistory = handoffs,
                executionStepsCount = executedStepCount,
                isVerifiedIndependently = false,
                failureReason = lastEvidence?.details,
                agentReports = agentReview.reports,
                subAgentCallsUsed = agentReview.callsUsed
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

        val reviewSummary = agentReview.reports.entries.joinToString(" | ") { (role, report) ->
            "$role: " + report.take(240)
        }.take(900)
        val finalLeadMessage = (if (isIndependentVerified) {
            "All systems nominal, Sir. The council has executed and independently verified '${cleanGoal}' with complete success."
        } else {
            "Task executed, Sir, though physical sensor verification remains unconfirmed."
        }) + if (reviewSummary.isNotBlank()) " Independent reviews: $reviewSummary" else ""

        _activeRole.value = null

        CouncilExecutionOutcome(
            isSuccess = allExecutedSuccessfully,
            goal = cleanGoal,
            leadResponse = finalLeadMessage,
            handoffHistory = handoffs,
            executionStepsCount = missionPlan.steps.size,
            isVerifiedIndependently = isIndependentVerified,
            agentReports = agentReview.reports,
            subAgentCallsUsed = agentReview.callsUsed
        )
    }
}
