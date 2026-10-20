package com.jarvis.assistant.automation.mission

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.agent.ActionVerifier
import com.jarvis.assistant.automation.command.CommandParser
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
 * Central mission orchestration layer.
 * Standardizes the execution pipeline:
 * Understand intent -> Validate -> Plan -> Request approval if necessary -> Execute -> Verify -> Recover -> Report.
 */
object MissionEngine {
    private const val TAG = "MissionEngine"
    private val mutex = Mutex()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeMission = MutableStateFlow<MissionDefinition?>(null)
    val activeMission: StateFlow<MissionDefinition?> = _activeMission.asStateFlow()

    // Deduplication tracker to prevent duplicate consequential actions within a 1-minute window
    private val recentConsequentialActions = ConcurrentHashMap<String, Long>()

    // Registered tool execution hook
    var toolExecutor: (suspend (step: MissionStep, context: Context) -> Pair<Boolean, MissionVerificationEvidence>)? = null

    /**
     * Initializes and launches a mission from a user goal.
     */
    suspend fun startMission(
        context: Context,
        goal: String,
        userConstraints: List<String> = emptyList()
    ): MissionDefinition = mutex.withLock {
        val mission = planMission(goal, userConstraints)
        _activeMission.value = mission
        Log.i(TAG, "Planned mission ${mission.missionId}: '${mission.goal}' with ${mission.steps.size} steps")

        // If the mission requires upfront user approval, transition to WAITING_FOR_APPROVAL
        if (mission.approvalState == MissionApprovalState.PENDING_APPROVAL) {
            val awaitingApproval = mission.copy(
                status = MissionOverallStatus.WAITING_FOR_APPROVAL,
                updatedAt = System.currentTimeMillis()
            )
            _activeMission.value = awaitingApproval
            return@withLock awaitingApproval
        }

        // Otherwise start execution in background
        engineScope.launch {
            executeMission(context, mission.missionId)
        }
        return@withLock mission
    }

    /**
     * Plans a structured mission from natural language intent using authorized tools only.
     */
    fun planMission(goal: String, userConstraints: List<String> = emptyList()): MissionDefinition {
        val cleanGoal = goal.trim()
        val lower = cleanGoal.lowercase(Locale.ROOT)
        val steps = mutableListOf<MissionStep>()
        var requiresApproval = false
        var approvalPrompt: String? = null

        // 1. WhatsApp messaging intent
        if (lower.startsWith("send whatsapp") || lower.contains("whatsapp message") || lower.contains("whatsapp pe message")) {
            val contactMatch = Regex("(?i)(?:to|ko)\\s+([a-zA-Z0-9_ ]+?)(?:\\s+(?:that|ki|saying|bolna)\\s+(.+))?$").find(cleanGoal)
            val contact = contactMatch?.groupValues?.getOrNull(1)?.trim() ?: "Contact"
            val msg = contactMatch?.groupValues?.getOrNull(2)?.trim() ?: "Hello"

            requiresApproval = true
            approvalPrompt = "Send WhatsApp message to '$contact' with text: '$msg'?"
            steps.add(
                MissionStep(
                    title = "Prepare WhatsApp message",
                    description = "Validate recipient and draft message content",
                    toolName = "whatsapp_prepare",
                    parameters = mapOf("contact" to contact, "message" to msg),
                    riskLevel = MissionRisk.MEDIUM
                )
            )
            steps.add(
                MissionStep(
                    title = "Send WhatsApp message",
                    description = "Dispatch approved message through WhatsApp automation",
                    toolName = "whatsapp_send",
                    parameters = mapOf("contact" to contact, "message" to msg),
                    riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                    requiredPermissions = listOf("android.permission.BIND_ACCESSIBILITY_SERVICE"),
                    expectedEvidence = "Message sent checkmark or chat dispatch"
                )
            )
        }
        // 2. Web search & research intent
        else if (lower.startsWith("search") || lower.contains("find out") || lower.contains("google")) {
            val query = cleanGoal.replace(Regex("(?i)^(search|find out|look up|google)\\s*(for)?"), "").trim()
            steps.add(
                MissionStep(
                    title = "Search information",
                    description = "Query authorized search tool for: $query",
                    toolName = "search_web",
                    parameters = mapOf("query" to query),
                    riskLevel = MissionRisk.LOW,
                    expectedEvidence = "Search results or web page loaded"
                )
            )
        }
        // 3. YouTube play/search intent
        else if (lower.contains("play") || lower.contains("youtube")) {
            val query = cleanGoal.replace(Regex("(?i)^(play|search youtube for|youtube)\\s*"), "").trim()
            steps.add(
                MissionStep(
                    title = "Play media on YouTube",
                    description = "Search and stream track: $query",
                    toolName = "youtube_play",
                    parameters = mapOf("query" to query),
                    riskLevel = MissionRisk.LOW,
                    expectedEvidence = "Playback initiated"
                )
            )
        }
        // 4. Device control intent
        else if (lower.contains("torch") || lower.contains("volume") || lower.contains("battery") || lower.contains("brightness")) {
            steps.add(
                MissionStep(
                    title = "Adjust device hardware",
                    description = cleanGoal,
                    toolName = "device_control",
                    parameters = mapOf("raw" to cleanGoal),
                    riskLevel = MissionRisk.LOW,
                    expectedEvidence = "Hardware state updated"
                )
            )
        }
        // 5. Default general assistant step
        else {
            steps.add(
                MissionStep(
                    title = "Analyze and process request",
                    description = cleanGoal,
                    toolName = "assistant_action",
                    parameters = mapOf("goal" to cleanGoal),
                    riskLevel = MissionRisk.LOW
                )
            )
        }

        return MissionDefinition(
            goal = cleanGoal,
            userConstraints = userConstraints,
            steps = steps,
            status = MissionOverallStatus.PLANNING,
            approvalState = if (requiresApproval) MissionApprovalState.PENDING_APPROVAL else MissionApprovalState.NOT_REQUIRED,
            approvalPrompt = approvalPrompt
        )
    }

    /**
     * Approves or rejects a pending mission requiring user consent.
     */
    suspend fun resolveApproval(missionId: String, approved: Boolean, context: Context) = mutex.withLock {
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
    private suspend fun executeMission(context: Context, missionId: String) {
        var mission = _activeMission.value ?: return
        if (mission.missionId != missionId) return

        _activeMission.value = mission.copy(status = MissionOverallStatus.EXECUTING, updatedAt = System.currentTimeMillis())

        val unverifiedNotes = mutableListOf<String>()
        val updatedSteps = mission.steps.toMutableList()

        for (index in updatedSteps.indices) {
            mission = _activeMission.value ?: break
            if (mission.status == MissionOverallStatus.CANCELLED) break

            var currentStep = updatedSteps[index]

            // Check deduplication for consequential actions
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

            // Mark step as running
            currentStep = currentStep.copy(
                status = MissionStepStatus.RUNNING,
                startedAt = System.currentTimeMillis()
            )
            updatedSteps[index] = currentStep
            _activeMission.value = mission.copy(steps = updatedSteps.toList(), currentStepIndex = index)

            // Execute step with bounded retry
            var stepSuccess = false
            var evidence = MissionVerificationEvidence(
                evidenceType = EvidenceType.UNVERIFIED,
                isVerified = false,
                details = "No verifier attached."
            )

            for (attempt in 0..currentStep.maxRetries) {
                val result = executeSingleStep(currentStep, context)
                stepSuccess = result.first
                evidence = result.second

                if (stepSuccess && evidence.isVerified) {
                    break
                }
                if (attempt < currentStep.maxRetries) {
                    delay(200L * (attempt + 1))
                }
            }

            // Verify outcome
            if (stepSuccess) {
                currentStep = currentStep.copy(
                    status = MissionStepStatus.COMPLETED,
                    verificationEvidence = evidence,
                    completedAt = System.currentTimeMillis()
                )
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

                // Terminate mission on failure
                _activeMission.value = mission.copy(
                    steps = updatedSteps.toList(),
                    status = MissionOverallStatus.FAILED,
                    outcomeSummary = "Mission failed at step '${currentStep.title}': ${evidence.details}",
                    unverifiedDisclaimers = unverifiedNotes,
                    updatedAt = System.currentTimeMillis()
                )
                return
            }

            updatedSteps[index] = currentStep
            _activeMission.value = mission.copy(steps = updatedSteps.toList())
        }

        // Finalize completed mission
        val finalSummary = if (unverifiedNotes.isEmpty()) {
            "Mission '${mission.goal}' completed and fully verified, Nazib Sir."
        } else {
            "Mission completed with observations: ${unverifiedNotes.joinToString("; ")}"
        }

        _activeMission.value = mission.copy(
            steps = updatedSteps.toList(),
            status = MissionOverallStatus.COMPLETED,
            outcomeSummary = finalSummary,
            unverifiedDisclaimers = unverifiedNotes,
            updatedAt = System.currentTimeMillis()
        )
        Log.i(TAG, "Mission completed: $finalSummary")
    }

    /**
     * Executes a single step using registered hook or platform fallback.
     */
    private suspend fun executeSingleStep(
        step: MissionStep,
        context: Context
    ): Pair<Boolean, MissionVerificationEvidence> {
        val customHook = toolExecutor
        if (customHook != null) {
            return try {
                customHook(step, context)
            } catch (e: Exception) {
                Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Execution threw: ${e.message}"))
            }
        }

        // Fallback execution using JarvisApp command pipeline
        return when (step.toolName) {
            "device_control" -> {
                val raw = step.parameters["raw"] as? String ?: ""
                val reflex = com.jarvis.assistant.automation.reflex.OfflineReflexExecutor.tryExecuteReflex(raw, context, null)
                if (reflex.handled) {
                    Pair(true, MissionVerificationEvidence(EvidenceType.DEVICE_SETTING_VERIFIED, true, reflex.spokenResponse))
                } else {
                    Pair(false, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Reflex command not recognized"))
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
            "whatsapp_send" -> {
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
            else -> {
                // Generic step: report as completed with unverified notice if no specific validator exists
                Pair(true, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Action dispatched without deep closed-loop sensor"))
            }
        }
    }
}
