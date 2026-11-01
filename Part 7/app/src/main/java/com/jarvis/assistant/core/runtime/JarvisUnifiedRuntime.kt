package com.jarvis.assistant.core.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.dev.SoftwareEngineeringAgent
import com.jarvis.assistant.automation.mission.LongHorizonMissionCoordinator
import com.jarvis.assistant.automation.mission.MissionEngine
import com.jarvis.assistant.automation.mission.MissionOverallStatus
import com.jarvis.assistant.automation.offline.OfflineIntelligenceCoordinator
import com.jarvis.assistant.automation.provenance.KnowledgeProvenanceCoordinator
import com.jarvis.assistant.automation.provenance.SourceAuthority
import com.jarvis.assistant.automation.reflex.OfflineReflexExecutor
import com.jarvis.assistant.automation.routing.CognitiveRouter
import com.jarvis.assistant.automation.routing.ModelTier
import com.jarvis.assistant.automation.routing.TaskTaxonomy
import com.jarvis.assistant.core.ambient.AmbientExperienceCoordinator
import com.jarvis.assistant.core.eval.BenchmarkEvaluator
import com.jarvis.assistant.core.reliability.ReliabilityCoordinator
import com.jarvis.assistant.core.world.WorldModelCoordinator
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.security.sandbox.CapabilitySandbox
import com.jarvis.assistant.security.sandbox.SandboxCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import androidx.core.content.ContextCompat
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class JarvisUnifiedRuntime(
    val sandbox: CapabilitySandbox = CapabilitySandbox(),
    val router: CognitiveRouter = CognitiveRouter(),
    val offlineCoordinator: OfflineIntelligenceCoordinator = OfflineIntelligenceCoordinator(),
    val provenanceCoordinator: KnowledgeProvenanceCoordinator = KnowledgeProvenanceCoordinator(),
    val missionCoordinator: LongHorizonMissionCoordinator = LongHorizonMissionCoordinator(),
    val devAgent: SoftwareEngineeringAgent = SoftwareEngineeringAgent(),
    val evaluator: BenchmarkEvaluator = BenchmarkEvaluator()
) {
    val reliabilityCoordinator = ReliabilityCoordinator
    val ambientCoordinator = AmbientExperienceCoordinator
    val worldModelCoordinator = WorldModelCoordinator

    private val isInitialized = AtomicBoolean(false)
    private var lastBootReport: SystemBootReport? = null

    private inline fun measureElapsedMs(block: () -> Unit): Long {
        val start = try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
        block()
        val end = try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
        return (end - start).coerceAtLeast(1L)
    }

    /**
     * Executes the comprehensive boot diagnostic self-test across all subsystems.
     * Uses measured elapsed times, actual cryptographic ledger checks, and genuine system states.
     */
    fun bootSelfTest(
        context: Context? = null,
        isAccessibilityServiceConnected: Boolean? = null,
        isAudioRecordPermissionGranted: Boolean? = null,
        isNetworkOnline: Boolean? = null
    ): SystemBootReport {
        val appContext = context?.applicationContext
        val accessibilityReady = isAccessibilityServiceConnected ?: (
            appContext != null &&
                JarvisAccessibilityService.isRunning() &&
                AccessibilityHealthModel.isOperational()
            )
        val voiceReady = isAudioRecordPermissionGranted ?: (
            appContext != null &&
                ContextCompat.checkSelfPermission(
                    appContext,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED &&
                VoskModelManager.isModelReady.value
            )
        val networkReady = isNetworkOnline ?: appContext?.let(::hasValidatedInternet) ?: false

        val diagnostics = mutableListOf<SubsystemDiagnostic>()

        // 1. Accessibility is healthy only when the actual service is connected and operational.
        val a11yLatency = measureElapsedMs { AccessibilityHealthModel.isOperational() }
        diagnostics.add(
            SubsystemDiagnostic(
                subsystemName = "Accessibility Perception",
                status = if (accessibilityReady) SubsystemStatus.HEALTHY else SubsystemStatus.DEGRADED,
                latencyMs = a11yLatency,
                diagnosticMessage = if (accessibilityReady) {
                    "Accessibility service is connected and operational."
                } else {
                    "Accessibility service is not confirmed connected and operational."
                },
                requiredActionForUser = if (accessibilityReady) null else
                    "Enable JARVIS in Android Accessibility Settings and return to recheck."
            )
        )

        // 2. Voice readiness requires both microphone permission and a loaded local Vosk model.
        val voiceLatency = measureElapsedMs { VoskModelManager.isModelReady.value }
        diagnostics.add(
            SubsystemDiagnostic(
                subsystemName = "Voice Pipeline & Wake Word",
                status = if (voiceReady) SubsystemStatus.HEALTHY else SubsystemStatus.DISABLED,
                latencyMs = voiceLatency,
                diagnosticMessage = if (voiceReady) {
                    "Microphone permission and local Vosk recognition model are ready."
                } else {
                    "Microphone permission or local Vosk recognition model is unavailable."
                },
                requiredActionForUser = if (voiceReady) null else
                    "Grant microphone permission and allow the Vosk model to finish loading."
            )
        )

        // 3. Verify the sandbox ledger and a benign input check.
        var ledgerVerified = false
        val sandboxLatency = measureElapsedMs {
            val sandboxCheck = sandbox.validateInputText("System check ping")
            ledgerVerified = sandbox.verifyLedgerIntegrity() && sandboxCheck is SandboxCheckResult.Allowed
        }
        diagnostics.add(
            SubsystemDiagnostic(
                subsystemName = "Security Execution Sandbox",
                status = if (ledgerVerified) SubsystemStatus.HEALTHY else SubsystemStatus.FAILED,
                latencyMs = sandboxLatency,
                diagnosticMessage = if (ledgerVerified) {
                    "Sandbox input check and tamper-evident ledger integrity verified."
                } else {
                    "Sandbox verification or ledger integrity check failed."
                }
            )
        )

        // 4. A configured key/circuit breaker is not enough: the mission planner must also be wired.
        val providerLatency = measureElapsedMs {
            router.isProviderHealthy("gemini_flash")
        }
        val providerConfigured = appContext?.let { app ->
            val hasKey = runCatching { AppPreferences(app).apiKey.isNotBlank() }.getOrDefault(false)
            hasKey && MissionEngine.llmPlanner?.isAvailable() == true &&
                router.isProviderHealthy("gemini_flash")
        } ?: false
        val providerStatus = when {
            !networkReady -> SubsystemStatus.OFFLINE
            providerConfigured -> SubsystemStatus.HEALTHY
            else -> SubsystemStatus.DEGRADED
        }
        diagnostics.add(
            SubsystemDiagnostic(
                subsystemName = "Cognitive Router & AI Providers",
                status = providerStatus,
                latencyMs = providerLatency,
                diagnosticMessage = when {
                    !networkReady -> "No validated internet connection is available."
                    providerConfigured -> "A configured provider and mission-planner integration are available; no live model request was sent during boot."
                    else -> "The production mission-planner provider is not fully configured or connected."
                },
                requiredActionForUser = if (providerConfigured || !networkReady) null else
                    "Configure a supported API key and connect the provider to MissionEngine."
            )
        )

        // 5. The current offline queue is process-local and has no automatic executor.
        val offlineLatency = measureElapsedMs { offlineCoordinator.getHonestCapabilityMatrix() }
        diagnostics.add(
            SubsystemDiagnostic(
                subsystemName = "Offline Intelligence Store",
                status = SubsystemStatus.DEGRADED,
                latencyMs = offlineLatency,
                diagnosticMessage = "Local heuristics are available, but deferred actions are not yet durably persisted or automatically synchronized."
            )
        )

        val hasFailed = diagnostics.any { it.status == SubsystemStatus.FAILED }
        val hasDegraded = diagnostics.any {
            it.status == SubsystemStatus.DEGRADED ||
                it.status == SubsystemStatus.DISABLED ||
                it.status == SubsystemStatus.OFFLINE
        }
        val overallStatus = when {
            hasFailed -> SubsystemStatus.FAILED
            hasDegraded -> SubsystemStatus.DEGRADED
            else -> SubsystemStatus.HEALTHY
        }
        val summary = when (overallStatus) {
            SubsystemStatus.HEALTHY -> "All verified subsystems are online and operational, Sir."
            SubsystemStatus.DEGRADED -> "JARVIS is running in degraded mode; one or more subsystems need configuration or integration."
            else -> "System alert: critical failure detected during boot self-test."
        }

        val report = SystemBootReport(
            overallStatus = overallStatus,
            diagnostics = diagnostics,
            isAccessibilityReady = accessibilityReady,
            isVoiceReady = voiceReady,
            isNetworkReady = networkReady,
            summaryMessage = summary
        )
        lastBootReport = report
        isInitialized.set(true)
        return report
    }

    private fun hasValidatedInternet(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Central execution pipeline unifying security, routing, offline execution, and provenance.
     * Truly delegates to offline and online mission execution with no canned strings.
     */
    suspend fun processUserRequest(
        input: String,
        context: Context? = null,
        isNetworkAvailable: Boolean = true
    ): UnifiedExecutionResult = withContext(Dispatchers.Default) {
        return@withContext try {
            // 1. Security Sandbox Check: Prompt Injection / Threat Check
            val securityCheck = sandbox.validateInputText(input)
            if (securityCheck is SandboxCheckResult.Blocked) {
                sandbox.audit("processUserRequest", "user", securityCheck, input)
                return@withContext UnifiedExecutionResult.BlockedBySecurity(securityCheck.reason)
            }

            // 2. Destructive Git command guard
            val gitAudit = devAgent.auditGitOperation(input)
            if (gitAudit.isDestructive) {
                return@withContext UnifiedExecutionResult.BlockedBySecurity("Destructive command requires explicit authorization: ${gitAudit.warningMessage}")
            }

            // 3. Fast-path reflex check (hardware flashlight, volume, brightness) - zero latency
            if (context != null) {
                val reflexResult = OfflineReflexExecutor.tryExecuteReflex(input, context, null)
                if (reflexResult.handled) {
                    val record = provenanceCoordinator.recordEvidence(
                        claim = reflexResult.spokenResponse,
                        subject = "reflex_hardware",
                        source = SourceAuthority.OFFICIAL_SYSTEM,
                        uri = "sys://local_reflex",
                        excerpt = input
                    )
                    return@withContext UnifiedExecutionResult.Success(
                        outputMessage = reflexResult.spokenResponse,
                        specialistHandled = "OfflineReflexExecutor",
                        provenanceRecordId = record.id
                    )
                }
            }

            // 4. Cognitive Routing
            val route = router.route(input, isNetworkAvailable = isNetworkAvailable)

            if (route.taskType == com.jarvis.assistant.automation.routing.TaskTaxonomy.CODING_DEV) {
                try {
                    com.jarvis.assistant.ui.home.MainActivity.activeInstance?.runOnUiThread {
                        com.jarvis.assistant.ui.home.MainActivity.activeInstance?.selectTab(com.jarvis.assistant.ui.home.MainActivity.TAB_CODING)
                    }
                } catch (_: Throwable) {}
            }

            // 5. Offline Mode Handling
            if (!isNetworkAvailable || (route.selectedTier == ModelTier.LOCAL_REFLEX && route.providerId == "local_reflex")) {
                val offlineResult = offlineCoordinator.handleCommandOffline(input, context)
                return@withContext when (offlineResult) {
                    is com.jarvis.assistant.automation.offline.OfflineExecutionResult.ExecutedLocally -> {
                        val record = provenanceCoordinator.recordEvidence(
                            claim = offlineResult.userFeedback,
                            subject = "offline_action",
                            source = SourceAuthority.OFFICIAL_SYSTEM,
                            uri = "sys://offline_local",
                            excerpt = input
                        )
                        UnifiedExecutionResult.Success(
                            outputMessage = offlineResult.userFeedback,
                            specialistHandled = "OfflineIntelligenceCoordinator",
                            provenanceRecordId = record.id
                        )
                    }
                    is com.jarvis.assistant.automation.offline.OfflineExecutionResult.QueuedForOnline -> {
                        UnifiedExecutionResult.Degraded(
                            reason = "ACTION_QUEUED_OFFLINE",
                            fallbackMessage = "The action was queued for a future online retry; it has not been executed yet. ${offlineResult.userFeedback}"
                        )
                    }
                    is com.jarvis.assistant.automation.offline.OfflineExecutionResult.DegradedExplanation -> {
                        UnifiedExecutionResult.Degraded(
                            reason = offlineResult.unsupportedFeature,
                            fallbackMessage = offlineResult.userFeedback
                        )
                    }
                }
            }

            // 6. Council risk preflight + online mission execution.
            // The council is advisory and deterministic here; MissionEngine remains the
            // single source of truth for clarification and approval state.
            val councilReview = com.jarvis.assistant.automation.council.MultiAgentCouncil.deliberate(
                context = context,
                userGoal = input
            )
            val preflightPlan = MissionEngine.planMission(input, context = context)
            if (!councilReview.isApprovedToExecute &&
                preflightPlan.status != MissionOverallStatus.WAITING_FOR_APPROVAL &&
                preflightPlan.status != MissionOverallStatus.CLARIFICATION_REQUIRED
            ) {
                return@withContext UnifiedExecutionResult.BlockedBySecurity(
                    councilReview.finalVerdict
                )
            }

            val mission = MissionEngine.startMission(context, input)

            if (mission.status == MissionOverallStatus.CLARIFICATION_REQUIRED) {
                val question = mission.clarificationRequest?.question ?: "Could you please clarify your request, sir?"
                return@withContext UnifiedExecutionResult.Degraded(
                    reason = "CLARIFICATION_REQUIRED",
                    fallbackMessage = question
                )
            }

            if (mission.status == MissionOverallStatus.WAITING_FOR_APPROVAL) {
                return@withContext UnifiedExecutionResult.BlockedBySecurity(
                    "This action requires explicit approval before execution: ${mission.goal}"
                )
            }

            val resultSummary = mission.outcomeSummary?.ifBlank { null }
                ?: "Mission ${mission.status.name.lowercase().replace('_', ' ')}: ${mission.goal}"

            when (mission.status) {
                MissionOverallStatus.COMPLETED -> {
                    val record = provenanceCoordinator.recordEvidence(
                        claim = resultSummary,
                        subject = "mission_execution",
                        source = SourceAuthority.TOOL_EXECUTION,
                        uri = "jarvis://mission/${mission.missionId}",
                        excerpt = input
                    )
                    UnifiedExecutionResult.Success(
                        outputMessage = resultSummary,
                        specialistHandled = route.specialistTarget,
                        provenanceRecordId = record.id
                    )
                }
                MissionOverallStatus.PLANNING,
                MissionOverallStatus.EXECUTING,
                MissionOverallStatus.VERIFYING -> UnifiedExecutionResult.Degraded(
                    reason = "MISSION_IN_PROGRESS",
                    fallbackMessage = "Mission accepted and is still running. No completion claim has been made. Mission ID: ${mission.missionId}"
                )
                else -> UnifiedExecutionResult.Degraded(
                    reason = "MISSION_${mission.status.name}",
                    fallbackMessage = resultSummary
                )
            }
        } catch (cancelled: CancellationException) {
            // Cancellation is control flow, not a runtime failure. Preserve structured concurrency.
            throw cancelled
        } catch (t: Exception) {
            recoverFromCrash(t)
        }
    }

    /**
     * Uncaught crash recovery: safe forensics capture and state machine reset.
     */
    fun recoverFromCrash(t: Throwable): UnifiedExecutionResult.RecoveredCrash {
        Log.e("JarvisUnifiedRuntime", "Request execution failed; returning runtime to a safe state.", t)
        ambientCoordinator.transitionTo(ConversationState.RECOVERING, "request_recovery")
        ambientCoordinator.transitionTo(ConversationState.IDLE, "recovery_complete")
        // Keep exception messages and possible prompt/provider payloads out of user-visible responses.
        return UnifiedExecutionResult.RecoveredCrash(
            "The request could not be completed because of an internal error. Please try again."
        )
    }

    fun getLastBootReport(): SystemBootReport? = lastBootReport
    fun isReady(): Boolean = isInitialized.get()
}
