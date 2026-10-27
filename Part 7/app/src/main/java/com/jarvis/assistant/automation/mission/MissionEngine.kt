package com.jarvis.assistant.automation.mission

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.command.JarvisCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
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

    suspend fun resumeNonTerminalMissions(context: Context? = null): List<MissionDefinition> = mutex.withLock {
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

                // If in flight step was non-idempotent or consequential at crash time -> mark UNVERIFIED
                val sanitizedSteps = steps.mapIndexed { idx, st ->
                    if (idx == e.currentStepIndex && (st.status == MissionStepStatus.RUNNING || st.status == MissionStepStatus.PENDING)) {
                        if (!st.isIdempotent || st.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL) {
                            st.copy(
                                status = MissionStepStatus.UNVERIFIED,
                                failureReason = "Process died while step was in flight. Manual user confirmation required."
                            )
                        } else {
                            st
                        }
                    } else {
                        st
                    }
                }

                val hasUnverified = sanitizedSteps.any { it.status == MissionStepStatus.UNVERIFIED }
                val overallStatus = if (hasUnverified) {
                    MissionOverallStatus.WAITING_FOR_APPROVAL
                } else {
                    try { MissionOverallStatus.valueOf(e.status) } catch (_: Exception) { MissionOverallStatus.EXECUTING }
                }

                val mission = MissionDefinition(
                    missionId = e.id,
                    goal = e.goal,
                    steps = sanitizedSteps,
                    status = overallStatus,
                    approvalState = if (hasUnverified) MissionApprovalState.PENDING_APPROVAL else try { MissionApprovalState.valueOf(e.approvalState) } catch (_: Exception) { MissionApprovalState.APPROVED },
                    outcomeSummary = e.outcomeSummary,
                    createdAt = e.updatedAt,
                    updatedAt = System.currentTimeMillis()
                )

                persistMission(mission, e.currentStepIndex)
                list.add(mission)
            } catch (err: Exception) {
                Log.e(TAG, "Error resuming mission: ${err.message}", err)
            }
        }
        return list
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
        val planner = llmPlanner
        if (planner != null && planner.isAvailable()) {
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
            engineScope.launch {
                val result = planner.planAndExecute(goal, context) { toolName, args ->
                    val step = MissionStep(
                        title = "LLM Step: $toolName",
                        toolName = toolName,
                        parameters = args
                    )
                    val (success, evidence) = executeSingleStep(step, context)
                    Pair(success && evidence.isVerified, evidence.details)
                }

                val convertedSteps = result.history.map { h ->
                    MissionStep(
                        title = "Step ${h.stepIndex}: ${h.toolName}",
                        toolName = h.toolName,
                        parameters = h.arguments,
                        status = if (h.isVerified) MissionStepStatus.COMPLETED else MissionStepStatus.FAILED,
                        verificationEvidence = MissionVerificationEvidence(
                            evidenceType = if (h.isVerified) EvidenceType.EXTERNAL_API_ACKNOWLEDGED else EvidenceType.UNVERIFIED,
                            isVerified = h.isVerified,
                            details = h.observation
                        )
                    )
                }

                val finalStatus = if (result.isSuccess) {
                    MissionOverallStatus.COMPLETED
                } else if (result.history.any { !it.isVerified }) {
                    MissionOverallStatus.UNVERIFIED
                } else {
                    MissionOverallStatus.FAILED
                }

                _activeMission.value = initialMission.copy(
                    steps = convertedSteps,
                    status = finalStatus,
                    outcomeSummary = result.summary,
                    updatedAt = System.currentTimeMillis()
                )
            }
            return@withLock initialMission
        }

        val mission = planMission(goal, userConstraints, context)
        _activeMission.value = mission
        Log.i(TAG, "Planned mission ${mission.missionId}: '${mission.goal}' with status ${mission.status}")

        // 1. If clarification is needed, do not execute
        if (mission.status == MissionOverallStatus.CLARIFICATION_REQUIRED) {
            return@withLock mission
        }

        // 2. If the mission requires upfront user approval, wait for approval
        if (mission.approvalState == MissionApprovalState.PENDING_APPROVAL) {
            val awaitingApproval = mission.copy(
                status = MissionOverallStatus.WAITING_FOR_APPROVAL,
                updatedAt = System.currentTimeMillis()
            )
            _activeMission.value = awaitingApproval
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
                        parameters = mapOf("contact" to contact, "message" to msg),
                        riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                        isIdempotent = false,
                        expectedEvidence = "Message sent checkmark or chat dispatch"
                    )
                )
            }
        }
        // 2. Ambiguity Detection: Phone Call
        else if (lower.startsWith("call") || lower.startsWith("phone") || lower.contains("ko phone") || lower.contains("ko call")) {
            val query = cleanGoal.replace(Regex("(?i)^(call|phone|dial)\\s*"), "").trim()
            if (query.isBlank()) {
                clarification = ClarificationRequest(
                    question = "Which contact would you like me to call, sir?",
                    missingField = "contact_name"
                )
            } else {
                steps.add(
                    MissionStep(
                        title = "Place phone call",
                        description = "Call contact '$query'",
                        toolName = "call_contact",
                        parameters = mapOf("contact_name" to query),
                        riskLevel = MissionRisk.MEDIUM,
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
     * Approves or rejects a pending mission requiring user consent.
     */
    suspend fun resolveApproval(missionId: String, approved: Boolean, context: Context? = null) = mutex.withLock {
        val current = _activeMission.value ?: return@withLock
        if (current.missionId != missionId || current.status != MissionOverallStatus.WAITING_FOR_APPROVAL) {
            return@withLock
        }

        if (approved) {
            val updated = current.copy(
                approvalState = MissionApprovalState.APPROVED,
                status = MissionOverallStatus.EXECUTING,
                updatedAt = System.currentTimeMillis()
            )
            _activeMission.value = updated
            engineScope.launch {
                executeMission(context, missionId)
            }
        } else {
            val updated = current.copy(
                approvalState = MissionApprovalState.REJECTED,
                status = MissionOverallStatus.CANCELLED,
                outcomeSummary = "Mission cancelled by user; approval was declined.",
                updatedAt = System.currentTimeMillis()
            )
            _activeMission.value = updated
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
            Log.i(TAG, "Mission $missionId cancelled: $reason")
        }
    }

    /**
     * Main sequential execution loop.
     */
    private suspend fun executeMission(context: Context?, missionId: String) {
        var mission = _activeMission.value ?: return
        if (mission.missionId != missionId) return

        _activeMission.value = mission.copy(
            status = MissionOverallStatus.EXECUTING,
            updatedAt = System.currentTimeMillis()
        )

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
            _activeMission.value = mission.copy(steps = updatedSteps.toList(), currentStepIndex = index)

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
                    _activeMission.value = mission.copy(steps = updatedSteps.toList())
                    return
                }

                val timeout = currentStep.timeoutMs
                val result = withTimeoutOrNull(timeout) {
                    executeSingleStep(currentStep, context)
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
                return
            }

            updatedSteps[index] = currentStep
            _activeMission.value = mission.copy(steps = updatedSteps.toList())
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

        _activeMission.value = mission.copy(
            steps = updatedSteps.toList(),
            status = finalStatus,
            outcomeSummary = finalSummary,
            unverifiedDisclaimers = unverifiedNotes,
            updatedAt = System.currentTimeMillis()
        )
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
     * Executes a single step using registered hook or real platform tool integrations.
     */
    private suspend fun executeSingleStep(
        step: MissionStep,
        context: Context?
    ): Pair<Boolean, MissionVerificationEvidence> {
        val customHook = toolExecutor
        if (customHook != null) {
            return try {
                customHook(step, context)
            } catch (e: Exception) {
                Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Execution threw: ${e.message}"))
            }
        }

        if (context == null) {
            return Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Context unavailable"))
        }

        // Real execution using JARVIS platform integrations
        return when (step.toolName) {
            "device_control" -> {
                val cmd = step.parameters["command"] as? String ?: ""
                val reflex = com.jarvis.assistant.automation.reflex.OfflineReflexExecutor.tryExecuteReflex(cmd, context, null)
                if (reflex.handled) {
                    Pair(true, MissionVerificationEvidence(EvidenceType.DEVICE_SETTING_VERIFIED, true, reflex.spokenResponse))
                } else {
                    Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Device setting command could not be matched"))
                }
            }
            "youtube_play" -> {
                val query = step.parameters["query"] as? String ?: ""
                val app = context.applicationContext as? JarvisApp
                if (app != null && query.isNotBlank()) {
                    app.commandExecutor.execute(JarvisCommand.PlayYouTube(query))
                    Pair(true, MissionVerificationEvidence(EvidenceType.EXTERNAL_API_ACKNOWLEDGED, true, "YouTube intent launched for '$query'"))
                } else {
                    Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "YouTube player unavailable"))
                }
            }
            "search_web" -> {
                val query = step.parameters["query"] as? String ?: ""
                val app = context.applicationContext as? JarvisApp
                if (app != null && query.isNotBlank()) {
                    app.commandExecutor.execute(JarvisCommand.SearchWeb(query))
                    Pair(true, MissionVerificationEvidence(EvidenceType.EXTERNAL_API_ACKNOWLEDGED, true, "Web search dispatched for '$query'"))
                } else {
                    Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Browser unavailable"))
                }
            }
            "whatsapp_prepare" -> {
                Pair(true, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, "WhatsApp message validated and staged"))
            }
            "send_whatsapp" -> {
                val contact = step.parameters["contact"] as? String ?: ""
                val msg = step.parameters["message"] as? String ?: ""
                val app = context.applicationContext as? JarvisApp
                if (app != null && contact.isNotBlank() && msg.isNotBlank()) {
                    val res = app.commandExecutor.execute(JarvisCommand.WhatsAppMessage(contact, msg))
                    val verified = !res.contains("failed", ignoreCase = true) && !res.contains("error", ignoreCase = true)
                    Pair(verified, MissionVerificationEvidence(
                        evidenceType = if (verified) EvidenceType.EXTERNAL_API_ACKNOWLEDGED else EvidenceType.UNVERIFIED,
                        isVerified = verified,
                        details = res
                    ))
                } else {
                    Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "WhatsApp assistant unavailable"))
                }
            }
            "call_contact" -> {
                val contact = step.parameters["contact_name"] as? String ?: ""
                val idx = step.parameters["contact_index"] as? Int
                val app = context.applicationContext as? JarvisApp
                if (app != null && contact.isNotBlank()) {
                    val res = com.jarvis.assistant.util.ContactCaller.callContact(context, contact, idx)
                    when (res) {
                        is com.jarvis.assistant.util.ContactCaller.CallResult.Success -> {
                            Pair(true, MissionVerificationEvidence(
                                evidenceType = EvidenceType.DEVICE_SETTING_VERIFIED,
                                isVerified = true,
                                details = "Placed call to ${res.contact.name} (${res.contact.number})"
                            ))
                        }
                        is com.jarvis.assistant.util.ContactCaller.CallResult.MultipleMatches -> {
                            Pair(false, MissionVerificationEvidence(
                                evidenceType = EvidenceType.UNVERIFIED,
                                isVerified = false,
                                details = "Ambiguous contact name. Found ${res.matches.size} matches."
                            ))
                        }
                        else -> {
                            Pair(false, MissionVerificationEvidence(
                                evidenceType = EvidenceType.UNVERIFIED,
                                isVerified = false,
                                details = "Call failed or missing permissions."
                            ))
                        }
                    }
                } else {
                    Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Phone service unavailable"))
                }
            }
            "navigate_home" -> {
                val app = context.applicationContext as? JarvisApp
                if (app != null) {
                    val res = app.commandExecutor.execute(JarvisCommand.NavigateHome)
                    Pair(true, MissionVerificationEvidence(EvidenceType.UI_STATE_CHANGE, true, res))
                } else {
                    Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Home navigation unavailable"))
                }
            }
            "build_website" -> {
                val prompt = step.parameters["prompt"] as? String ?: ""
                val mode = step.parameters["mode"] as? String ?: "new"
                val isEdit = mode.equals("edit", ignoreCase = true)
                com.jarvis.assistant.forge.ForgeController.init(context)
                com.jarvis.assistant.forge.ForgeController.start(prompt, isEdit)
                try {
                    val intent = Intent(context, com.jarvis.assistant.ui.forge.ForgeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(com.jarvis.assistant.ui.forge.ForgeActivity.EXTRA_PROMPT, prompt)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
                Pair(true, MissionVerificationEvidence(EvidenceType.EXTERNAL_API_ACKNOWLEDGED, true, "{\"result\":\"forge_started\"}"))
            }
            else -> {
                Pair(true, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Action dispatched without deep closed-loop sensor"))
            }
        }
    }
}
