package com.jarvis.assistant.automation.mission

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.command.JarvisCommand
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.notification.JarvisNotificationListenerService
import com.jarvis.assistant.automation.dev.TermuxBridge
import com.jarvis.assistant.automation.dev.DevToolResult
import com.jarvis.assistant.automation.dev.DevProjectWorkspaceRegistry
import com.jarvis.assistant.security.sandbox.CapabilitySandbox
import com.jarvis.assistant.security.sandbox.RiskPolicy
import com.jarvis.assistant.security.sandbox.SandboxCheckResult
import com.jarvis.assistant.util.AppLauncher
import com.jarvis.assistant.vision.VisualTaskOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Central mission orchestration layer for JARVIS OS.
 * Standardizes the reasoning-to-action pipeline:
 * User Request -> Intent Understanding -> Clarification Check -> Plan -> Preconditions ->
 * Authorization -> Execution -> Evidence Verification -> Truthful Reporting.
 */
object MissionEngine {
    private const val TAG = "MissionEngine"
    private val mutex = Mutex()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeMission = MutableStateFlow<MissionDefinition?>(null)
    val activeMission: StateFlow<MissionDefinition?> = _activeMission.asStateFlow()

    // Deduplication tracker to prevent duplicate consequential actions within a 1-minute window
    private val recentConsequentialActions = ConcurrentHashMap<String, Long>()

    // Set of successfully completed non-idempotent step IDs
    private val completedNonIdempotentSteps = ConcurrentHashMap.newKeySet<String>()

    // Registered tool execution hook (for custom runners or unit tests)
    var toolExecutor: (suspend (step: MissionStep, context: Context?) -> Pair<Boolean, MissionVerificationEvidence>)? = null

    // Pluggable LLM planner instance (used when network + healthy provider are available)
    var llmPlanner: com.jarvis.assistant.automation.agent.LlmPlanner? = null

    // Persistent Room DAO for survivable missions across process death and reboots
    var missionDao: com.jarvis.assistant.data.mission.MissionDao? = null
    private val gson = com.google.gson.Gson()
    private val sandbox = CapabilitySandbox()

    private fun isSensitiveWorkspacePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        val fileName = normalized.substringAfterLast('/')
        return fileName in setOf(
            ".env", ".env.local", "local.properties", "gradle.properties",
            "keystore.properties", "credentials.json", "service-account.json"
        ) || normalized.contains("/secrets/") ||
            normalized.endsWith(".jks") || normalized.endsWith(".keystore")
    }

    private fun hasBlockingMission(): Boolean {
        val current = _activeMission.value ?: return false
        return current.status !in setOf(
            MissionOverallStatus.COMPLETED,
            MissionOverallStatus.FAILED,
            MissionOverallStatus.CANCELLED
        )
    }

    private fun blockedMission(goal: String): MissionDefinition = MissionDefinition(
        goal = goal,
        status = MissionOverallStatus.FAILED,
        outcomeSummary = "A previous mission is still active or requires manual review. This request was not started."
    )

    suspend fun persistMission(mission: MissionDefinition, currentStepIdx: Int = 0) {
        val dao = missionDao ?: return
        try {
            val entity = com.jarvis.assistant.data.mission.MissionEntity(
                id = mission.missionId,
                goal = mission.goal,
                status = mission.status.name,
                planJson = gson.toJson(mission.steps),
                currentStepIndex = currentStepIdx,
                approvalState = mission.approvalState.name,
                updatedAt = System.currentTimeMillis(),
                deduplicationKeysJson = gson.toJson(recentConsequentialActions.keys().toList()),
                outcomeSummary = mission.outcomeSummary
            )
            dao.insertOrUpdate(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist mission ${mission.missionId}: ${e.message}", e)
        }
    }

    suspend fun resumeNonTerminalMissions(
        context: Context? = null,
        preferredMissionId: String? = null
    ): List<MissionDefinition> = mutex.withLock {
        val dao = missionDao ?: return emptyList()
        val entities = dao.getNonTerminalMissions()
        val list = mutableListOf<MissionDefinition>()

        for (e in entities) {
            try {
                // Restore dedup keys
                if (e.deduplicationKeysJson.isNotBlank()) {
                    val keys = gson.fromJson(e.deduplicationKeysJson, Array<String>::class.java)
                    keys?.forEach { recentConsequentialActions[it] = e.updatedAt }
                }

                val type = object : com.google.gson.reflect.TypeToken<List<MissionStep>>() {}.type
                val steps: List<MissionStep> = gson.fromJson(e.planJson, type) ?: emptyList()

                val wasAwaitingApproval =
                    e.status == MissionOverallStatus.WAITING_FOR_APPROVAL.name &&
                        e.approvalState == MissionApprovalState.PENDING_APPROVAL.name

                // A pending approval is not an interrupted action. Preserve it exactly.
                // An actually in-flight consequential/non-idempotent action becomes UNVERIFIED
                // and is never replayed automatically after process death.
                val sanitizedSteps = steps.mapIndexed { idx, st ->
                    if (!wasAwaitingApproval && idx == e.currentStepIndex &&
                        (st.status == MissionStepStatus.RUNNING || st.status == MissionStepStatus.PENDING)
                    ) {
                        if (!st.isIdempotent || st.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL) {
                            st.copy(
                                status = MissionStepStatus.UNVERIFIED,
                                failureReason = "Process died while this action was in flight. JARVIS will not replay it automatically."
                            )
                        } else {
                            st
                        }
                    } else {
                        st
                    }
                }

                val hasUnverified = sanitizedSteps.any { it.status == MissionStepStatus.UNVERIFIED }
                val overallStatus = when {
                    hasUnverified -> MissionOverallStatus.UNVERIFIED
                    wasAwaitingApproval -> MissionOverallStatus.WAITING_FOR_APPROVAL
                    else -> try { MissionOverallStatus.valueOf(e.status) } catch (_: Exception) { MissionOverallStatus.EXECUTING }
                }
                val approvalState = if (wasAwaitingApproval) MissionApprovalState.PENDING_APPROVAL else
                    try { MissionApprovalState.valueOf(e.approvalState) } catch (_: Exception) { MissionApprovalState.NOT_REQUIRED }
                val pendingStep = sanitizedSteps.getOrNull(e.currentStepIndex)

                val mission = MissionDefinition(
                    missionId = e.id,
                    goal = e.goal,
                    steps = sanitizedSteps,
                    status = overallStatus,
                    approvalState = approvalState,
                    approvalPrompt = if (wasAwaitingApproval) {
                        "Approve the pending '${pendingStep?.toolName ?: "mission"}' action for this mission?"
                    } else null,
                    outcomeSummary = if (hasUnverified) {
                        "Mission interrupted during an action whose real-world outcome could not be verified. No automatic retry was performed."
                    } else e.outcomeSummary,
                    createdAt = e.updatedAt,
                    updatedAt = System.currentTimeMillis()
                )

                persistMission(mission, e.currentStepIndex)
                if (preferredMissionId == e.id || (_activeMission.value == null && preferredMissionId == null)) {
                    _activeMission.value = mission
                }
                if (context != null && mission.status == MissionOverallStatus.WAITING_FOR_APPROVAL) {
                    MissionApprovalNotifier.show(context, mission)
                }
                list.add(mission)
            } catch (err: Exception) {
                Log.e(TAG, "Error resuming mission: ${err.message}", err)
            }
        }
        return list
    }

    private fun buildPlannerGoal(goal: String, userConstraints: List<String>): String =
        if (userConstraints.isEmpty()) goal
        else "$goal\nUser constraints: ${userConstraints.joinToString("; ")}"

    private fun isLlmPlannedMission(mission: MissionDefinition): Boolean =
        mission.outcomeSummary?.startsWith("Approval required before JARVIS can execute", ignoreCase = true) == true

    private suspend fun persistLlmPlannerOutcome(
        baseMission: MissionDefinition,
        result: com.jarvis.assistant.automation.agent.LlmMissionExecutionResult,
        context: Context?
    ) {
        val awaitingApproval = result.abortReason == "APPROVAL_REQUIRED"
        val convertedSteps = result.history.map { record ->
            val isPendingApprovalStep = awaitingApproval &&
                record.toolName == result.pendingApprovalTool &&
                !record.isVerified
            val registeredRisk = ToolRegistry.getTool(record.toolName)?.riskLevel ?: MissionRisk.LOW
            MissionStep(
                title = "Step ${record.stepIndex}: ${record.toolName}",
                toolName = record.toolName,
                parameters = record.arguments,
                riskLevel = if (isPendingApprovalStep) MissionRisk.HIGH_CONSEQUENTIAL else registeredRisk,
                status = when {
                    record.isVerified -> MissionStepStatus.COMPLETED
                    isPendingApprovalStep -> MissionStepStatus.PENDING
                    else -> MissionStepStatus.UNVERIFIED
                },
                verificationEvidence = MissionVerificationEvidence(
                    evidenceType = if (record.isVerified) EvidenceType.EXTERNAL_API_ACKNOWLEDGED else EvidenceType.UNVERIFIED,
                    isVerified = record.isVerified,
                    details = record.observation
                )
            )
        }

        val allStepsVerified = convertedSteps.isNotEmpty() &&
            convertedSteps.all {
                it.status == MissionStepStatus.COMPLETED &&
                    it.verificationEvidence?.isVerified == true
            }
        val finalStatus = when {
            awaitingApproval -> MissionOverallStatus.WAITING_FOR_APPROVAL
            result.abortReason == "CLARIFICATION_REQUIRED" -> MissionOverallStatus.CLARIFICATION_REQUIRED
            result.abortReason == "CANCELLED" -> MissionOverallStatus.CANCELLED
            result.isSuccess && allStepsVerified -> MissionOverallStatus.COMPLETED
            result.history.any { !it.isVerified } || result.isSuccess -> MissionOverallStatus.UNVERIFIED
            else -> MissionOverallStatus.FAILED
        }
        val updated = baseMission.copy(
            steps = convertedSteps,
            status = finalStatus,
            approvalState = if (awaitingApproval) MissionApprovalState.PENDING_APPROVAL else baseMission.approvalState,
            approvalPrompt = if (awaitingApproval) result.summary else null,
            outcomeSummary = result.summary,
            updatedAt = System.currentTimeMillis()
        )
        _activeMission.value = updated
        val pendingIndex = convertedSteps.indexOfFirst { it.status == MissionStepStatus.PENDING }
        val nextIndex = if (pendingIndex >= 0) pendingIndex else
            convertedSteps.indexOfFirst { it.status != MissionStepStatus.COMPLETED }.coerceAtLeast(0)
        persistMission(updated, nextIndex)
        if (awaitingApproval && context != null) {
            MissionApprovalNotifier.show(context, updated)
        }
    }

    private suspend fun continueApprovedLlmMission(
        context: Context?,
        approvedMission: MissionDefinition
    ) {
        val planner = llmPlanner ?: run {
            val failed = approvedMission.copy(
                status = MissionOverallStatus.FAILED,
                outcomeSummary = "The LLM planner is unavailable; the approved action was not executed."
            )
            _activeMission.value = failed
            persistMission(failed)
            return
        }
        val pendingIndex = approvedMission.steps.indexOfFirst { it.status == MissionStepStatus.PENDING }
        if (pendingIndex < 0) {
            val failed = approvedMission.copy(
                status = MissionOverallStatus.FAILED,
                outcomeSummary = "No pending approved action was found; nothing was executed."
            )
            _activeMission.value = failed
            persistMission(failed)
            return
        }

        val pending = approvedMission.steps[pendingIndex]
        val canonicalToolName = ToolRegistry.resolveToolName(pending.toolName)
        val initialHistory = approvedMission.steps.take(pendingIndex).mapIndexed { index, step ->
            com.jarvis.assistant.automation.agent.LlmPlannerStepRecord(
                stepIndex = index + 1,
                toolName = ToolRegistry.resolveToolName(step.toolName),
                arguments = step.parameters,
                isVerified = step.status == MissionStepStatus.COMPLETED &&
                    step.verificationEvidence?.isVerified == true,
                observation = step.verificationEvidence?.details ?: "Previously verified mission step."
            )
        }
        var approvalConsumed = false
        val result = planner.planAndExecute(
            goal = buildPlannerGoal(approvedMission.goal, approvedMission.userConstraints),
            context = context,
            initialHistory = initialHistory,
            approvedToolCall = com.jarvis.assistant.automation.agent.ApprovedLlmToolCall(
                toolName = canonicalToolName,
                arguments = pending.parameters
            )
        ) { toolName, args ->
            val exactApproval = !approvalConsumed &&
                ToolRegistry.resolveToolName(toolName) == canonicalToolName &&
                args == pending.parameters
            if (exactApproval) approvalConsumed = true
            val step = MissionStep(
                title = "LLM Step: $toolName",
                toolName = toolName,
                parameters = args,
                riskLevel = ToolRegistry.getTool(toolName)?.riskLevel ?: MissionRisk.LOW
            )
            val (success, evidence) = executeSingleStep(
                step,
                context,
                isApprovedByUser = exactApproval
            )
            Pair(success && evidence.isVerified, evidence.details)
        }
        persistLlmPlannerOutcome(approvedMission, result, context)
    }

    /**
     * Initializes and launches a mission from a user goal.
     * Tries LLM planner when available; falls back to deterministic regex planner for offline fast-path.
     */
    suspend fun startMission(
        context: Context? = null,
        goal: String,
        userConstraints: List<String> = emptyList()
    ): MissionDefinition = mutex.withLock {
        if (hasBlockingMission()) return@withLock blockedMission(goal)
        val planner = llmPlanner
        val hasExactContactOrProfileConstraint = userConstraints.any {
            it.startsWith("contact_index=") || it.startsWith("app_number=")
        }
        if (!hasExactContactOrProfileConstraint &&
            planner != null &&
            planner.isAvailable() &&
            planner.isProviderReady()
        ) {
            val missionId = java.util.UUID.randomUUID().toString()
            val initialMission = MissionDefinition(
                missionId = missionId,
                goal = goal,
                userConstraints = userConstraints,
                steps = emptyList(),
                status = MissionOverallStatus.EXECUTING,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            _activeMission.value = initialMission
            persistMission(initialMission)
            engineScope.launch {
                val result = planner.planAndExecute(buildPlannerGoal(goal, userConstraints), context) { toolName, args ->
                    val step = MissionStep(
                        title = "LLM Step: $toolName",
                        toolName = toolName,
                        parameters = args,
                        riskLevel = ToolRegistry.getTool(toolName)?.riskLevel ?: MissionRisk.LOW
                    )
                    val (success, evidence) = executeSingleStep(step, context)
                    Pair(success && evidence.isVerified, evidence.details)
                }
                persistLlmPlannerOutcome(initialMission, result, context)
            }
            // Return the running mission immediately. The background planner updates
            // activeMission and persistence when it reaches a verified terminal state.
            return@withLock initialMission
        }

        val mission = planMission(goal, userConstraints, context)
        _activeMission.value = mission
        persistMission(mission)
        Log.i(TAG, "Planned mission ${mission.missionId}: '${mission.goal}' with status ${mission.status}")

        // 1. If clarification is needed, do not execute
        if (mission.status == MissionOverallStatus.CLARIFICATION_REQUIRED) {
            persistMission(mission)
            return@withLock mission
        }

        // 2. If the mission requires upfront user approval, wait for approval
        if (mission.approvalState == MissionApprovalState.PENDING_APPROVAL) {
            val awaitingApproval = mission.copy(
                status = MissionOverallStatus.WAITING_FOR_APPROVAL,
                updatedAt = System.currentTimeMillis()
            )
            _activeMission.value = awaitingApproval
            persistMission(awaitingApproval)
            if (context != null) MissionApprovalNotifier.show(context, awaitingApproval)
            return@withLock awaitingApproval
        }

        // 3. Otherwise start execution in background
        engineScope.launch {
            executeMission(context, mission.missionId)
        }
        return@withLock mission
    }

    /**
     * Plans a structured mission from natural language intent using authorized tools only.
     * Detects ambiguities and generates explicit clarification requests when inputs are missing.
     */
    fun planMission(
        goal: String,
        userConstraints: List<String> = emptyList(),
        context: Context? = null
    ): MissionDefinition {
        val cleanGoal = goal.trim()
        val lower = cleanGoal.lowercase(Locale.ROOT)
        val steps = mutableListOf<MissionStep>()
        var requiresApproval = false
        var approvalPrompt: String? = null
        var clarification: ClarificationRequest? = null

        // 1. Ambiguity Detection: WhatsApp Messaging
        if (lower.startsWith("send whatsapp") || lower.contains("whatsapp message") || lower.contains("whatsapp pe message")) {
            val contactMatch = Regex("(?i)(?:to|ko)\\s+([a-zA-Z0-9_ ]+?)(?:\\s+(?:that|ki|saying|bolna)\\s+(.+))?$").find(cleanGoal)
            val contact = contactMatch?.groupValues?.getOrNull(1)?.trim()
            val msg = contactMatch?.groupValues?.getOrNull(2)?.trim()

            if (contact.isNullOrBlank()) {
                clarification = ClarificationRequest(
                    question = "Who would you like to send this WhatsApp message to, sir?",
                    missingField = "contact"
                )
            } else if (msg.isNullOrBlank()) {
                clarification = ClarificationRequest(
                    question = "What message would you like to send to $contact?",
                    missingField = "message"
                )
            } else {
                requiresApproval = true
                approvalPrompt = "Send WhatsApp message to '$contact' saying: '$msg'?"
                steps.add(
                    MissionStep(
                        title = "Prepare WhatsApp message",
                        description = "Validate recipient and draft message content",
                        toolName = "whatsapp_prepare",
                        parameters = mapOf("contact" to contact, "message" to msg),
                        riskLevel = MissionRisk.MEDIUM,
                        isIdempotent = true
                    )
                )
                steps.add(
                    MissionStep(
                        title = "Send WhatsApp message",
                        description = "Dispatch approved message through WhatsApp automation",
                        toolName = "whatsapp_send",
                        parameters = buildMap {
                            put("contact", contact)
                            put("message", msg)
                            userConstraints.firstNotNullOfOrNull {
                                it.removePrefix("app_number=").toIntOrNull()
                            }?.let { put("app_number", it) }
                        },
                        riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                        isIdempotent = false,
                        expectedEvidence = "Message sent checkmark or chat dispatch"
                    )
                )
            }
        }
        // 2. WhatsApp voice/video calls are consequential and always require explicit approval.
        else if (lower.contains("whatsapp") && (lower.contains("call") || lower.contains("phone"))) {
            val query = cleanGoal
                .replace(Regex("(?i)^(?:please\\s+)?(?:make\\s+)?(?:a\\s+)?whatsapp\\s+(?:voice\\s+|video\\s+)?call\\s*(?:to\\s+)?"), "")
                .replace(Regex("(?i)^(?:please\\s+)?(?:make\\s+)?(?:a\\s+)?(?:voice\\s+|video\\s+)?call\\s*(?:to\\s+)?"), "")
                .replace(Regex("(?i)\\s+on\\s+whatsapp$"), "")
                .trim()
            if (query.isBlank()) {
                clarification = ClarificationRequest(
                    question = "Who should I call on WhatsApp, Sir?",
                    missingField = "contact_name"
                )
            } else {
                val callType = if (lower.contains("video")) "video" else "voice"
                val appNumber = userConstraints.firstNotNullOfOrNull {
                    it.removePrefix("app_number=").toIntOrNull()
                }
                requiresApproval = true
                approvalPrompt = "Start a WhatsApp $callType call with '$query'?"
                steps.add(
                    MissionStep(
                        title = "Start WhatsApp $callType call",
                        description = "Open WhatsApp call action for '$query' only after explicit user approval",
                        toolName = "whatsapp_call",
                        parameters = buildMap {
                            put("contact_name", query)
                            put("call_type", callType)
                            if (appNumber != null) put("app_number", appNumber)
                        },
                        riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                        isIdempotent = false,
                        expectedEvidence = "WhatsApp call interface opened; connection state remains unverified"
                    )
                )
            }
        }
        // 3. Ambiguity Detection: Phone Call
        else if (lower.startsWith("call") || lower.startsWith("phone") || lower.contains("ko phone") || lower.contains("ko call")) {
            val query = cleanGoal.replace(Regex("(?i)^(call|phone|dial)\\s*"), "").trim()
            if (query.isBlank()) {
                clarification = ClarificationRequest(
                    question = "Which contact would you like me to call, sir?",
                    missingField = "contact_name"
                )
            } else {
                requiresApproval = true
                approvalPrompt = "Place a phone call to '$query'?"
                val contactIndex = userConstraints.firstNotNullOfOrNull {
                    it.removePrefix("contact_index=").toIntOrNull()
                }
                steps.add(
                    MissionStep(
                        title = "Place phone call",
                        description = "Call contact '$query' after explicit user approval",
                        toolName = "call_contact",
                        parameters = buildMap {
                            put("contact_name", query)
                            if (contactIndex != null) put("contact_index", contactIndex)
                        },
                        riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                        isIdempotent = false,
                        expectedEvidence = "Outgoing phone call screen launched"
                    )
                )
            }
        }
        // 3. Web Search & Research Intent
        else if (lower.startsWith("search") || lower.contains("find out") || lower.contains("google")) {
            val query = cleanGoal.replace(Regex("(?i)^(search(\\s+(information|details|results))?|find out|look up|google)\\s*(for|about)?\\s*"), "").trim()
            if (query.isBlank()) {
                clarification = ClarificationRequest(
                    question = "What topic or query would you like me to search for?",
                    missingField = "query"
                )
            } else {
                steps.add(
                    MissionStep(
                        title = "Search information",
                        description = "Query authorized search tool for: $query",
                        toolName = "search_web",
                        parameters = mapOf("query" to query),
                        riskLevel = MissionRisk.LOW,
                        isIdempotent = true,
                        expectedEvidence = "Search results or web page loaded"
                    )
                )
            }
        }
        // 4. YouTube Media Play/Search
        else if (lower.contains("play") || lower.contains("youtube")) {
            val query = cleanGoal.replace(Regex("(?i)^(play|search youtube for|youtube)\\s*"), "").trim()
            if (query.isBlank()) {
                clarification = ClarificationRequest(
                    question = "What song or video would you like to play on YouTube?",
                    missingField = "query"
                )
            } else {
                steps.add(
                    MissionStep(
                        title = "Play media on YouTube",
                        description = "Search and stream track: $query",
                        toolName = "youtube_play",
                        parameters = mapOf("query" to query),
                        riskLevel = MissionRisk.LOW,
                        isIdempotent = true,
                        expectedEvidence = "Playback initiated"
                    )
                )
            }
        }
        // 5. Device Control Intent
        else if (lower.contains("torch") || lower.contains("volume") || lower.contains("battery") || lower.contains("brightness")) {
            steps.add(
                MissionStep(
                    title = "Adjust device hardware",
                    description = cleanGoal,
                    toolName = "device_control",
                    parameters = mapOf("command" to cleanGoal),
                    riskLevel = MissionRisk.LOW,
                    isIdempotent = true,
                    expectedEvidence = "Hardware state updated"
                )
            )
        }
        // 6. Default General Task
        else {
            steps.add(
                MissionStep(
                    title = "Analyze and process request",
                    description = cleanGoal,
                    toolName = "device_control",
                    parameters = mapOf("command" to cleanGoal),
                    riskLevel = MissionRisk.LOW,
                    isIdempotent = true
                )
            )
        }

        val overallStatus = when {
            clarification != null -> MissionOverallStatus.CLARIFICATION_REQUIRED
            requiresApproval -> MissionOverallStatus.WAITING_FOR_APPROVAL
            else -> MissionOverallStatus.PLANNING
        }

        return MissionDefinition(
            goal = cleanGoal,
            userConstraints = userConstraints,
            steps = steps,
            status = overallStatus,
            approvalState = if (requiresApproval) MissionApprovalState.PENDING_APPROVAL else MissionApprovalState.NOT_REQUIRED,
            approvalPrompt = approvalPrompt,
            clarificationRequest = clarification
        )
    }

    /**
     * Starts a skill-provided, already validated plan through the same durable mission lifecycle
     * used by natural-language missions. Consequential tool definitions always require approval.
     */
    suspend fun startPreparedMission(
        context: Context? = null,
        goal: String,
        userConstraints: List<String> = emptyList(),
        steps: List<MissionStep>,
        requiresApproval: Boolean = false,
        approvalPrompt: String? = null
    ): MissionDefinition = mutex.withLock {
        if (hasBlockingMission()) return@withLock blockedMission(goal)
        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank() || steps.isEmpty()) {
            val rejected = MissionDefinition(
                goal = cleanGoal.ifBlank { "Unnamed skill mission" },
                userConstraints = userConstraints,
                steps = steps,
                status = MissionOverallStatus.FAILED,
                outcomeSummary = "Mission was not started because its goal or step list was empty."
            )
            _activeMission.value = rejected
            persistMission(rejected)
            return@withLock rejected
        }

        // Never allow a skill's declared risk to downgrade the registered tool's risk.
        val riskAdjustedSteps = steps.map { step ->
            val registeredRisk = ToolRegistry.getTool(step.toolName)?.riskLevel ?: step.riskLevel
            val policyRequiresApproval = RiskPolicy.evaluateRisk(step.toolName, step.parameters, cleanGoal)
                .requiresExplicitConfirmation
            val effectiveRisk = if (policyRequiresApproval) MissionRisk.HIGH_CONSEQUENTIAL else registeredRisk
            if (effectiveRisk.ordinal > step.riskLevel.ordinal) step.copy(riskLevel = effectiveRisk) else step
        }
        val needsApproval = requiresApproval || riskAdjustedSteps.any {
            it.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL
        }
        val mission = MissionDefinition(
            goal = cleanGoal,
            userConstraints = userConstraints,
            steps = riskAdjustedSteps,
            status = if (needsApproval) MissionOverallStatus.WAITING_FOR_APPROVAL else MissionOverallStatus.EXECUTING,
            approvalState = if (needsApproval) MissionApprovalState.PENDING_APPROVAL else MissionApprovalState.NOT_REQUIRED,
            approvalPrompt = if (needsApproval) {
                approvalPrompt ?: "Skill '$cleanGoal' contains consequential actions. Do you want to proceed?"
            } else null
        )
        _activeMission.value = mission
        persistMission(mission)

        if (needsApproval && context != null) {
            MissionApprovalNotifier.show(context, mission)
        }
        if (!needsApproval) {
            engineScope.launch { executeMission(context, mission.missionId) }
        }
        mission
    }

    suspend fun awaitMissionOutcome(
        missionId: String,
        timeoutMs: Long = 120_000L
    ): MissionDefinition? = withTimeoutOrNull(timeoutMs) {
        activeMission.first { mission ->
            mission?.let {
                it.missionId == missionId && it.status in setOf(
                    MissionOverallStatus.WAITING_FOR_APPROVAL,
                    MissionOverallStatus.CLARIFICATION_REQUIRED,
                    MissionOverallStatus.COMPLETED,
                    MissionOverallStatus.PARTIALLY_COMPLETED,
                    MissionOverallStatus.FAILED,
                    MissionOverallStatus.CANCELLED,
                    MissionOverallStatus.UNVERIFIED
                )
            } == true
        }
    }

    /**
     * Approves or rejects a pending mission requiring user consent.
     */
    suspend fun resolveApproval(missionId: String, approved: Boolean, context: Context? = null) = mutex.withLock {
        val current = _activeMission.value ?: return@withLock
        if (current.missionId != missionId || current.status != MissionOverallStatus.WAITING_FOR_APPROVAL) {
            return@withLock
        }

        if (current.steps.any { it.status == MissionStepStatus.UNVERIFIED }) {
            Log.w(TAG, "Mission $missionId contains an unverified in-flight action; approval cannot safely replay it.")
            return@withLock
        }
        MissionApprovalNotifier.cancel(context, missionId)

        if (approved) {
            val updated = current.copy(
                approvalState = MissionApprovalState.APPROVED,
                status = MissionOverallStatus.EXECUTING,
                updatedAt = System.currentTimeMillis()
            )
            _activeMission.value = updated
            persistMission(updated)
            engineScope.launch {
                if (isLlmPlannedMission(updated)) {
                    continueApprovedLlmMission(context, updated)
                } else {
                    executeMission(context, missionId)
                }
            }
        } else {
            val updated = current.copy(
                approvalState = MissionApprovalState.REJECTED,
                status = MissionOverallStatus.CANCELLED,
                outcomeSummary = "Mission cancelled by user; approval was declined.",
                updatedAt = System.currentTimeMillis()
            )
            _activeMission.value = updated
            persistMission(updated)
            Log.i(TAG, "Mission $missionId rejected by user.")
        }
    }

    /**
     * Cancels an active mission cleanly.
     */
    suspend fun cancelMission(missionId: String, reason: String = "User requested cancellation") = mutex.withLock {
        val current = _activeMission.value ?: return@withLock
        if (current.missionId == missionId && !current.isFinished) {
            val updated = current.copy(
                status = MissionOverallStatus.CANCELLED,
                outcomeSummary = "Mission cancelled: $reason",
                updatedAt = System.currentTimeMillis()
            )
            _activeMission.value = updated
            persistMission(updated)
            Log.i(TAG, "Mission $missionId cancelled: $reason")
        }
    }

    /**
     * Main sequential execution loop.
     */
    private suspend fun executeMission(context: Context?, missionId: String) {
        var mission = _activeMission.value ?: return
        if (mission.missionId != missionId) return

        mission = mission.copy(
            status = MissionOverallStatus.EXECUTING,
            updatedAt = System.currentTimeMillis()
        )
        _activeMission.value = mission
        persistMission(mission)

        val unverifiedNotes = mutableListOf<String>()
        val updatedSteps = mission.steps.toMutableList()
        var completedCount = 0

        for (index in updatedSteps.indices) {
            mission = _activeMission.value ?: break
            if (mission.status == MissionOverallStatus.CANCELLED) {
                Log.i(TAG, "Mission execution aborted: mission is cancelled.")
                break
            }

            var currentStep = updatedSteps[index]

            // Resume only from work that has not already been verified. This prevents
            // approval/recovery from repeating completed actions, including idempotent ones.
            if (currentStep.status == MissionStepStatus.COMPLETED &&
                currentStep.verificationEvidence?.isVerified == true
            ) {
                completedCount++
                continue
            }

            // 1. Idempotency Check: Skip already completed non-idempotent steps
            if (!currentStep.isIdempotent && completedNonIdempotentSteps.contains(currentStep.stepId)) {
                Log.i(TAG, "Skipping already completed non-idempotent step: ${currentStep.title}")
                continue
            }

            // 2. Precondition & Tool Validation Check
            val validation = ToolRegistry.validate(currentStep.toolName, currentStep.parameters, context)
            when (validation) {
                is ToolValidationResult.MissingParameters -> {
                    currentStep = currentStep.copy(
                        status = MissionStepStatus.BLOCKED,
                        failureReason = "Missing required parameters: ${validation.missingParams.joinToString()}"
                    )
                    updatedSteps[index] = currentStep
                    finalizeFailure(mission, updatedSteps, index, currentStep.failureReason!!, completedCount, unverifiedNotes)
                    return
                }
                is ToolValidationResult.MissingPermissions -> {
                    currentStep = currentStep.copy(
                        status = MissionStepStatus.BLOCKED,
                        failureReason = "Missing permissions: ${validation.missingPermissions.joinToString()}"
                    )
                    updatedSteps[index] = currentStep
                    finalizeFailure(mission, updatedSteps, index, currentStep.failureReason!!, completedCount, unverifiedNotes)
                    return
                }
                is ToolValidationResult.AccessibilityUnavailable -> {
                    currentStep = currentStep.copy(
                        status = MissionStepStatus.BLOCKED,
                        failureReason = validation.guidance
                    )
                    updatedSteps[index] = currentStep
                    finalizeFailure(mission, updatedSteps, index, currentStep.failureReason!!, completedCount, unverifiedNotes)
                    return
                }
                is ToolValidationResult.UnknownTool -> {
                    currentStep = currentStep.copy(
                        status = MissionStepStatus.BLOCKED,
                        failureReason = "Unknown tool '${validation.toolName}'"
                    )
                    updatedSteps[index] = currentStep
                    finalizeFailure(mission, updatedSteps, index, currentStep.failureReason!!, completedCount, unverifiedNotes)
                    return
                }
                ToolValidationResult.Valid -> Unit
            }

            // 3. Deduplication Check for High Consequential Actions (60s Safety Window)
            if (currentStep.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL) {
                val actionKey = "${currentStep.toolName}:${currentStep.parameters}"
                val lastRun = recentConsequentialActions[actionKey] ?: 0L
                val now = System.currentTimeMillis()
                if (now - lastRun < 60_000L) {
                    val skippedEvidence = MissionVerificationEvidence(
                        evidenceType = EvidenceType.UNVERIFIED,
                        isVerified = false,
                        details = "Skipped duplicate consequential action within safety cooldown window."
                    )
                    currentStep = currentStep.copy(
                        status = MissionStepStatus.BLOCKED,
                        failureReason = "Duplicate consequential action detected within 60s cooldown.",
                        verificationEvidence = skippedEvidence
                    )
                    updatedSteps[index] = currentStep
                    unverifiedNotes.add("Step '${currentStep.title}' was blocked to prevent duplicate consequential operations.")
                    continue
                }
                recentConsequentialActions[actionKey] = now
            }

            // 4. Mark step as running
            currentStep = currentStep.copy(
                status = MissionStepStatus.RUNNING,
                startedAt = System.currentTimeMillis()
            )
            updatedSteps[index] = currentStep
            mission = mission.copy(steps = updatedSteps.toList(), currentStepIndex = index)
            _activeMission.value = mission
            persistMission(mission, index)

            // 5. Execute Step with Bounded Retry and Timeout
            var stepSuccess = false
            var evidence = MissionVerificationEvidence(
                evidenceType = EvidenceType.UNVERIFIED,
                isVerified = false,
                details = "No verifier attached."
            )

            for (attempt in 0..currentStep.maxRetries) {
                if (_activeMission.value?.status == MissionOverallStatus.CANCELLED) {
                    currentStep = currentStep.copy(status = MissionStepStatus.CANCELLED)
                    updatedSteps[index] = currentStep
                    val cancelledMission = mission.copy(steps = updatedSteps.toList(), currentStepIndex = index)
                    _activeMission.value = cancelledMission
                    persistMission(cancelledMission, index)
                    return
                }

                val timeout = currentStep.timeoutMs
                val result = withTimeoutOrNull(timeout) {
                    executeSingleStep(currentStep, context, mission.approvalState == MissionApprovalState.APPROVED)
                }

                if (result != null) {
                    stepSuccess = result.first
                    evidence = result.second
                } else {
                    stepSuccess = false
                    evidence = MissionVerificationEvidence(
                        evidenceType = EvidenceType.UNVERIFIED,
                        isVerified = false,
                        details = "Step timed out after ${timeout}ms"
                    )
                }

                if (stepSuccess) {
                    break
                }
                if (attempt < currentStep.maxRetries) {
                    delay(200L * (attempt + 1))
                }
            }

            // 6. Verify Outcome
            if (stepSuccess) {
                val stepStatus = if (evidence.isVerified) MissionStepStatus.COMPLETED else MissionStepStatus.UNVERIFIED
                currentStep = currentStep.copy(
                    status = stepStatus,
                    verificationEvidence = evidence,
                    completedAt = System.currentTimeMillis()
                )
                if (!currentStep.isIdempotent) {
                    completedNonIdempotentSteps.add(currentStep.stepId)
                }
                completedCount++
                if (!evidence.isVerified) {
                    unverifiedNotes.add("Step '${currentStep.title}' executed, but physical verification was incomplete: ${evidence.details}")
                }
            } else {
                currentStep = currentStep.copy(
                    status = MissionStepStatus.FAILED,
                    failureReason = evidence.details,
                    verificationEvidence = evidence,
                    completedAt = System.currentTimeMillis()
                )
                updatedSteps[index] = currentStep
                unverifiedNotes.add("Step '${currentStep.title}' failed: ${evidence.details}")

                finalizeFailure(mission, updatedSteps, index, evidence.details, completedCount, unverifiedNotes)
                persistMission(_activeMission.value ?: mission, index)
                return
            }

            updatedSteps[index] = currentStep
            mission = mission.copy(steps = updatedSteps.toList(), currentStepIndex = index)
            _activeMission.value = mission
            persistMission(mission, index)
        }

        // 7. Finalize Overall Mission Outcome Truthfully
        val finalStatus = when {
            unverifiedNotes.isNotEmpty() && completedCount == updatedSteps.size -> MissionOverallStatus.UNVERIFIED
            completedCount < updatedSteps.size && completedCount > 0 -> MissionOverallStatus.PARTIALLY_COMPLETED
            completedCount == updatedSteps.size -> MissionOverallStatus.COMPLETED
            else -> MissionOverallStatus.FAILED
        }

        val finalSummary = when (finalStatus) {
            MissionOverallStatus.COMPLETED ->
                "Mission '${mission.goal}' completed and fully verified with evidence."
            MissionOverallStatus.UNVERIFIED ->
                "Mission executed with unverified observations: ${unverifiedNotes.joinToString("; ")}"
            MissionOverallStatus.PARTIALLY_COMPLETED ->
                "Mission partially completed ($completedCount/${updatedSteps.size} steps). Observations: ${unverifiedNotes.joinToString("; ")}"
            else ->
                "Mission could not be completed."
        }

        val finalizedMission = mission.copy(
            steps = updatedSteps.toList(),
            status = finalStatus,
            outcomeSummary = finalSummary,
            unverifiedDisclaimers = unverifiedNotes,
            updatedAt = System.currentTimeMillis()
        )
        _activeMission.value = finalizedMission
        persistMission(finalizedMission, updatedSteps.size)
        Log.i(TAG, "Mission finalized: $finalStatus — $finalSummary")
    }

    private fun finalizeFailure(
        mission: MissionDefinition,
        updatedSteps: List<MissionStep>,
        failedIndex: Int,
        reason: String,
        completedCount: Int,
        unverifiedNotes: List<String>
    ) {
        val finalStatus = if (completedCount > 0) MissionOverallStatus.PARTIALLY_COMPLETED else MissionOverallStatus.FAILED
        val failedStepTitle = updatedSteps[failedIndex].title
        _activeMission.value = mission.copy(
            steps = updatedSteps,
            status = finalStatus,
            outcomeSummary = "Mission stopped at step '$failedStepTitle': $reason",
            unverifiedDisclaimers = unverifiedNotes,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Executes one already-planned council step through the same validation and platform
     * execution path used by regular missions. Missing context, tools, permissions, or
     * integrations are returned as failures; this method never fabricates success.
     */
    suspend fun executeStepForCouncil(
        step: MissionStep,
        context: Context?
    ): Pair<Boolean, MissionVerificationEvidence> {
        if (context == null) {
            return Pair(false, MissionVerificationEvidence(
                EvidenceType.UNVERIFIED, false, "Context unavailable; execution was not attempted."
            ))
        }

        return when (val validation = ToolRegistry.validate(step.toolName, step.parameters, context)) {
            ToolValidationResult.Valid -> executeSingleStep(step, context)
            is ToolValidationResult.UnknownTool -> Pair(false, MissionVerificationEvidence(
                EvidenceType.UNVERIFIED, false, "Unknown tool '${validation.toolName}'; execution was not attempted."
            ))
            is ToolValidationResult.MissingParameters -> Pair(false, MissionVerificationEvidence(
                EvidenceType.UNVERIFIED, false, "Missing required parameters: ${validation.missingParams.joinToString()}."
            ))
            is ToolValidationResult.MissingPermissions -> Pair(false, MissionVerificationEvidence(
                EvidenceType.UNVERIFIED, false, "Missing Android permissions: ${validation.missingPermissions.joinToString()}."
            ))
            is ToolValidationResult.AccessibilityUnavailable -> Pair(false, MissionVerificationEvidence(
                EvidenceType.UNVERIFIED, false, validation.guidance
            ))
        }
    }

    /**
     * Executes a single step using registered hook or real platform tool integrations.
     */
    private suspend fun executeSingleStep(
        step: MissionStep,
        context: Context?,
        isApprovedByUser: Boolean = false
    ): Pair<Boolean, MissionVerificationEvidence> {
        val customHook = toolExecutor
        if (customHook != null) {
            return try {
                customHook(step, context)
            } catch (e: Exception) {
                Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Execution threw: ${e.javaClass.simpleName}"))
            }
        }

        if (context == null) {
            return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Android context unavailable; action was not executed."))
        }

        val appContext = context.applicationContext
        val app = appContext as? JarvisApp
        val visual = VisualTaskOrchestrator.getInstance()

        return when (step.toolName) {
            "device_control" -> {
                val command = step.parameters["command"] as? String ?: ""
                val reflex = com.jarvis.assistant.automation.reflex.OfflineReflexExecutor.tryExecuteReflex(command, context, null)
                if (reflex.handled && reflex.succeeded) {
                    Pair(true, MissionVerificationEvidence(EvidenceType.DEVICE_SETTING_VERIFIED, true, reflex.spokenResponse))
                } else {
                    Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Device setting command could not be matched or verified."))
                }
            }

            "open_app" -> {
                val packageName = step.parameters["package_name"] as? String
                val appName = packageName?.takeIf { it.isNotBlank() }
                    ?: (step.parameters["app_name"] as? String).orEmpty()
                when (val result = AppLauncher.openAppResult(context, appName)) {
                    is AppLauncher.OpenAppResult.Success -> {
                        delay(500)
                        val activePackage = JarvisAccessibilityService.instance?.getActiveRootNode()?.packageName?.toString()
                        val verified = activePackage == result.app.packageName
                        Pair(verified, MissionVerificationEvidence(
                            EvidenceType.UI_STATE_CHANGE, verified,
                            if (verified) "Foreground package verified: ${result.app.packageName}."
                            else "Launch was requested for ${result.app.packageName}, but foreground state could not be verified."
                        ))
                    }
                    is AppLauncher.OpenAppResult.MultipleFound -> Pair(false, MissionVerificationEvidence(
                        EvidenceType.UNVERIFIED, false,
                        "Multiple apps matched '${result.appName}': ${result.matches.take(4).joinToString { it.label }}. Clarification is required; no app was launched."
                    ))
                    is AppLauncher.OpenAppResult.NotFound -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Installed app '${result.appName}' was not found."))
                    AppLauncher.OpenAppResult.Failure -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Android did not launch the requested app."))
                }
            }

            "click_ui_element" -> {
                val target = step.parameters["target_text"] as? String ?: ""
                if (target.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Target text is missing."))
                val result = visual.handleClick(target, taskGoal = _activeMission.value?.goal.orEmpty())
                Pair(result.success && result.verified == true, MissionVerificationEvidence(EvidenceType.UI_STATE_CHANGE, result.verified == true, result.message))
            }

            "type_text" -> {
                val textToType = step.parameters["text"] as? String ?: ""
                val target = step.parameters["target_text"] as? String
                if (textToType.isEmpty()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Text input is empty."))
                val result = visual.handleType(text = textToType, targetInputName = target, submit = false)
                Pair(result.success && result.verified == true, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, result.verified == true, result.message))
            }

            "scroll_screen" -> {
                val direction = (step.parameters["direction"] as? String).orEmpty().lowercase(Locale.ROOT)
                if (direction !in setOf("up", "down")) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Scroll direction must be up or down."))
                val distanceValue = (step.parameters["distance"] as? Number)?.toInt() ?: 2
                val distance = when {
                    distanceValue <= 1 -> "small"
                    distanceValue <= 3 -> "medium"
                    else -> "large"
                }
                val result = visual.handleScroll(direction, distance)
                Pair(result.success && result.verified, MissionVerificationEvidence(EvidenceType.UI_STATE_CHANGE, result.verified, result.message))
            }

            "navigate_back" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Accessibility service is not running."))
                val before = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(service.getActiveRootNode())
                val action = visual.handleKey("back")
                delay(300)
                val after = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(service.getActiveRootNode())
                val verified = action.success && !after.isEquivalentTo(before)
                Pair(verified, MissionVerificationEvidence(EvidenceType.UI_STATE_CHANGE, verified, if (verified) "Back navigation changed the active screen." else "Back was dispatched but the screen transition was not verified."))
            }

            "navigate_home" -> {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val expectedHomePackage = context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
                val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
                delay(600)
                val activePackage = JarvisAccessibilityService.instance?.getActiveRootNode()?.packageName?.toString()
                val verified = launched && expectedHomePackage != null && activePackage == expectedHomePackage
                Pair(verified, MissionVerificationEvidence(
                    EvidenceType.UI_STATE_CHANGE, verified,
                    if (verified) "Android home launcher foreground state verified."
                    else "Home intent was dispatched, but launcher foreground state was not verified."
                ))
            }

            "youtube_play" -> {
                val query = step.parameters["query"] as? String ?: ""
                if (app == null || query.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "YouTube player unavailable or query missing."))
                val output = app.commandExecutor.execute(JarvisCommand.PlayYouTube(query))
                val failed = output.contains("failed", true) || output.contains("could not", true) || output.contains("unable", true)
                if (failed) Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, output))
                else Pair(true, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "YouTube request dispatched. Playback state was not independently verified; $output"))
            }

            "search_web" -> {
                val query = step.parameters["query"] as? String ?: ""
                if (app == null || query.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Browser unavailable or query missing."))
                val output = app.commandExecutor.execute(JarvisCommand.SearchWeb(query))
                val launched = output.startsWith("Opening ", ignoreCase = true)
                if (!launched) Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, output))
                else {
                    delay(900)
                    val screen = visual.inspectScreen(waitForContent = true)
                    val verified = screen.contains(query, ignoreCase = true) && (screen.contains("result", true) || screen.contains("search", true))
                    Pair(launched, MissionVerificationEvidence(
                        EvidenceType.TEXT_MATCH, verified,
                        if (verified) "Search query and result surface were observed on screen."
                        else "Browser opened, but search results could not be independently read."
                    ))
                }
            }

            "whatsapp_prepare" -> {
                val contact = step.parameters["contact"] as? String ?: ""
                val message = step.parameters["message"] as? String ?: ""
                if (app != null && contact.isNotBlank() && message.isNotBlank()) {
                    val draftResult = app.whatsAppAssistant.createDraft(contact, message)
                    val verified = app.whatsAppAssistant.hasPendingDraft()
                    Pair(verified, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, verified, if (verified) "WhatsApp draft staged for '$contact'. No message has been sent." else draftResult))
                } else Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "WhatsApp draft service unavailable or inputs missing."))
            }

            "send_whatsapp", "whatsapp_send" -> {
                if (!isApprovedByUser) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Explicit user approval is required before sending a WhatsApp message."))
                val contact = step.parameters["contact"] as? String ?: ""
                val message = step.parameters["message"] as? String ?: ""
                if (app == null || contact.isBlank() || message.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "WhatsApp assistant unavailable or message inputs missing."))
                val appNumber = (step.parameters["app_number"] as? Number)?.toInt()
                when (val sendResult = com.jarvis.assistant.util.WhatsAppMessenger.sendMessage(
                    context = context,
                    recipientName = contact,
                    message = message,
                    appNumber = appNumber,
                    confirmed = true
                )) {
                    is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Success -> Pair(
                        true,
                        MissionVerificationEvidence(
                            EvidenceType.UNVERIFIED,
                            false,
                            "WhatsApp opened and the send action was queued for ${sendResult.contactName}; delivery is not independently verified."
                        )
                    )
                    is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleAppsFound -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Multiple WhatsApp profiles are available; specify the app number."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.ContactNotFound -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "WhatsApp contact '${sendResult.name}' was not found."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleContactsFound -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Multiple contacts matched '${sendResult.name}'; clarification is required."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MissingPermission -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Contacts permission is not granted."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.RequiresConfirmation -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "WhatsApp message confirmation is still required."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Error -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, sendResult.reason))
                }
            }

            "whatsapp_call" -> {
                if (!isApprovedByUser) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Explicit user approval is required before placing a WhatsApp call."))
                val contact = step.parameters["contact_name"] as? String ?: ""
                val callType = (step.parameters["call_type"] as? String).orEmpty().ifBlank { "voice" }
                val appNumber = (step.parameters["app_number"] as? Number)?.toInt()
                if (contact.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "WhatsApp contact name is missing."))
                when (val result = com.jarvis.assistant.util.WhatsAppMessenger.placeCall(
                    context = context ?: return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Android context is unavailable.")),
                    recipientName = contact,
                    callType = callType,
                    appNumber = appNumber,
                    confirmed = true
                )) {
                    is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Success -> Pair(
                        true,
                        MissionVerificationEvidence(
                            EvidenceType.UNVERIFIED,
                            false,
                            "WhatsApp $callType call interface was opened for ${result.contactName}; call connection is not verified."
                        )
                    )
                    is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleAppsFound -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Multiple WhatsApp apps are available; select a profile before retrying."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.ContactNotFound -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "WhatsApp contact '${result.name}' was not found."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleContactsFound -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Multiple contacts matched '${result.name}'; clarification is required."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MissingPermission -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Contacts permission is not granted."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.RequiresConfirmation -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "WhatsApp call confirmation is still required."))
                    is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Error -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, result.reason))
                }
            }

            "call_contact" -> {
                if (!isApprovedByUser) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Explicit user approval is required before placing a phone call."))
                val contact = step.parameters["contact_name"] as? String ?: ""
                val idx = (step.parameters["contact_index"] as? Number)?.toInt()
                if (contact.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Contact name is missing."))
                when (val result = com.jarvis.assistant.util.ContactCaller.callContact(context, contact, idx)) {
                    is com.jarvis.assistant.util.ContactCaller.CallResult.Success -> Pair(true, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Call intent launched for ${result.contact.name}; connection state was not verified."))
                    is com.jarvis.assistant.util.ContactCaller.CallResult.MultipleMatches -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Ambiguous contact name; ${result.matches.size} matches found. No call was placed."))
                    else -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Call failed or required permissions are missing."))
                }
            }

            "delete_whatsapp_message" -> {
                if (!isApprovedByUser) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Explicit user approval is required before deleting a WhatsApp message."))
                val contact = step.parameters["contact"] as? String ?: ""
                val targetText = step.parameters["target_text"] as? String
                if (targetText?.isNotBlank() == true) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Targeted message deletion is not supported; no message was deleted."))
                if (app == null || contact.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "WhatsApp contact is missing or service unavailable."))
                val output = app.commandExecutor.execute(JarvisCommand.WhatsAppDeleteMessage(contact, forEveryone = false))
                Pair(true, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "$output Deletion is asynchronous and was not independently verified."))
            }

            "read_unread_notifications" -> {
                if (JarvisNotificationListenerService.instance == null) Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Notification listener is not connected; unread notification state is unavailable."))
                else Pair(true, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, JarvisNotificationListenerService.getUnreadWhatsAppSummary()))
            }

            "log_recall_core" -> {
                val key = step.parameters["key"] as? String ?: ""
                val value = step.parameters["value"] as? String ?: ""
                if (app == null || key.isBlank() || value.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Memory key/value missing or memory store unavailable."))
                val id = app.memoryRepository.remember(
                    content = value,
                    type = com.jarvis.assistant.data.memory.MemoryEntity.TYPE_PERSONAL_FACT,
                    key = key,
                    importance = 0.7f,
                    confidence = 0.9f
                )
                val verified = id > 0L && app.memoryRepository.searchMemories(key).any { it.id == id }
                Pair(verified, MissionVerificationEvidence(EvidenceType.DATABASE_RECORD, verified, if (verified) "Memory '$key' was persisted and read back from the encrypted database." else "Memory write could not be verified."))
            }

            "query_recall_core" -> {
                val query = step.parameters["query"] as? String ?: ""
                if (app == null || query.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Memory query missing or memory store unavailable."))
                val matches = app.memoryRepository.searchMemories(query).take(8)
                val summary = if (matches.isEmpty()) "Memory query completed; no saved memories matched '$query'."
                    else matches.joinToString("; ") { "${it.key}: ${it.value.take(240)}" }
                Pair(true, MissionVerificationEvidence(EvidenceType.DATABASE_RECORD, true, summary))
            }

            "termux_execute" -> {
                if (!isApprovedByUser) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Explicit user approval is required before executing shell commands."))
                val command = step.parameters["command"] as? String ?: ""
                if (command.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Shell command is empty."))
                if (sandbox.validateInputText(command) is SandboxCheckResult.Blocked) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Shell command was blocked by the capability sandbox."))
                when (val result = TermuxBridge.executeCommand(context, command, workDir = null, timeoutSeconds = 60L)) {
                    is DevToolResult.Success -> Pair(true, MissionVerificationEvidence(EvidenceType.EXTERNAL_API_ACKNOWLEDGED, true, TermuxBridge.maskSecrets(result.output).take(2_000)))
                    is DevToolResult.Failure -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Termux command failed (exit ${result.exitCode}): ${TermuxBridge.maskSecrets(result.error).take(1_000)}"))
                    is DevToolResult.EnvironmentUnsupported -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, result.reason))
                    is DevToolResult.AwaitingApproval -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, result.reason))
                }
            }

            "inspect_code" -> {
                val project = DevProjectWorkspaceRegistry.activeProject ?: return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "No development workspace is selected in Dev Studio."))
                val relativePath = step.parameters["path"] as? String ?: ""
                if (relativePath.isBlank()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "File path is missing."))
                if (isSensitiveWorkspacePath(relativePath)) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Sensitive configuration and credential files are blocked from mission inspection."))
                if (sandbox.validateFilePath(project.workspaceRoot, relativePath) !is SandboxCheckResult.Allowed) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "File path was blocked by the workspace sandbox."))
                val root = File(project.workspaceRoot).canonicalFile
                val target = File(root, relativePath).canonicalFile
                if (!target.toPath().startsWith(root.toPath()) || !target.isFile) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Requested file does not exist inside the selected workspace."))
                val text = target.readText().take(40_000)
                Pair(true, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, "Read $relativePath (${text.length} characters):
${text.take(3_000)}"))
            }

            "edit_code" -> {
                if (!isApprovedByUser) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Explicit user approval is required before modifying source files."))
                val project = DevProjectWorkspaceRegistry.activeProject ?: return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "No development workspace is selected in Dev Studio."))
                val relativePath = step.parameters["path"] as? String ?: ""
                val newContent = step.parameters["content"] as? String ?: ""
                if (relativePath.isBlank() || newContent.isEmpty()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "File path or replacement content is missing."))
                if (isSensitiveWorkspacePath(relativePath)) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Sensitive configuration and credential files are blocked from mission editing."))
                if (sandbox.validateFilePath(project.workspaceRoot, relativePath) !is SandboxCheckResult.Allowed) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "File path was blocked by the workspace sandbox."))
                val root = File(project.workspaceRoot).canonicalFile
                val target = File(root, relativePath).canonicalFile
                if (!target.toPath().startsWith(root.toPath())) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Resolved path escapes the selected workspace."))
                val parent = target.parentFile ?: return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Target parent directory is unavailable."))
                if (!parent.exists() && !parent.mkdirs()) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Could not create target directory."))
                val temporary = File(parent, ".${target.name}.${step.stepId}.tmp")
                try {
                    FileOutputStream(temporary).use { stream ->
                        stream.write(newContent.toByteArray(Charsets.UTF_8))
                        stream.fd.sync()
                    }
                    try {
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: Exception) {
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    val verified = target.isFile && target.readText() == newContent
                    Pair(verified, MissionVerificationEvidence(EvidenceType.FILE_CREATED, verified, if (verified) "File content was written and read back." else "File write completed but read-back verification failed."))
                } catch (error: Exception) {
                    temporary.delete()
                    Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "File edit failed (${error.javaClass.simpleName})."))
                }
            }

            "run_git" -> {
                if (!isApprovedByUser) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Explicit user approval is required before running Git operations."))
                val project = DevProjectWorkspaceRegistry.activeProject ?: return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "No development workspace is selected in Dev Studio."))
                val safeSubcommand = (step.parameters["subcommand"] as? String).orEmpty().trim()
                val verb = safeSubcommand.substringBefore(' ')
                val allowedVerbs = setOf("status", "diff", "log", "branch", "show", "rev-parse", "fetch", "pull", "push", "add", "commit", "checkout", "switch", "reset", "clean", "merge", "rebase")
                val safeArgumentSyntax = safeSubcommand.matches(Regex("^[A-Za-z0-9._/=@:+, -]+$"))
                if (safeSubcommand.isBlank() || !safeArgumentSyntax || verb !in allowedVerbs) {
                    return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Git subcommand was rejected by the command allowlist."))
                }
                val root = File(project.workspaceRoot).canonicalPath
                if (!(root.startsWith("/sdcard/") || root.startsWith("/storage/emulated/0/"))) return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Termux cannot access this app-private workspace. Move the selected project to shared storage before Git operations."))
                val quotedRoot = "'" + root.replace("'", "'\\''") + "'"
                val command = "git -C $quotedRoot $safeSubcommand"
                when (val result = TermuxBridge.executeCommand(context, command, workDir = root, timeoutSeconds = 90L)) {
                    is DevToolResult.Success -> Pair(true, MissionVerificationEvidence(EvidenceType.EXTERNAL_API_ACKNOWLEDGED, true, TermuxBridge.maskSecrets(result.output).take(2_000)))
                    is DevToolResult.Failure -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Git command failed (exit ${result.exitCode}): ${TermuxBridge.maskSecrets(result.error).take(1_000)}"))
                    is DevToolResult.EnvironmentUnsupported -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, result.reason))
                    is DevToolResult.AwaitingApproval -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, result.reason))
                }
            }

            "task_completed" -> Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Completion signals are accepted only by LlmPlanner after all tool steps have verified."))

            else -> Pair(false, MissionVerificationEvidence(
                EvidenceType.UNVERIFIED, false,
                "No real executor is registered for tool '${step.toolName}'; action was not executed."
            ))
        }
    }
}
}
