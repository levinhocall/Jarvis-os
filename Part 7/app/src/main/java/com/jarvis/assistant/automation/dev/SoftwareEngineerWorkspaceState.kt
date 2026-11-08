package com.jarvis.assistant.automation.dev

import com.jarvis.assistant.data.memory.MemoryRepository
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val projects: List<DevProject> = emptyList(),
    val selectedProject: DevProject? = null,
    val currentPlan: DevTaskPlan? = null,
    val isExecuting: Boolean = false,
    val pendingApprovalStep: DevTaskStep? = null,
    val lastReport: DevExecutionReport? = null,
    val consoleLog: List<String> = emptyList(),
    val environmentCapability: DevEnvironmentCapability? = null
)

class SoftwareEngineerWorkspaceCoordinator(
    private val memoryRepo: MemoryRepository? = null,
    private val devLoop: AutonomousDevLoop = AutonomousDevLoop(memoryRepo),
    injectedScope: CoroutineScope? = null
) {
    // A caller-supplied scope stays owned by the caller; a default one is owned (and closed) here.
    private val ownsScope = injectedScope == null
    private val scope: CoroutineScope = injectedScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var executionJob: Job? = null

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        // Register default project workspace if available
        val defaultProject = DevProject(
            name = "JARVIS OS",
            workspaceRoot = ShellPolicy.hostWorkspace() ?: "/sdcard/jarvis-workspace",
            projectType = "ANDROID_GRADLE",
            description = "Main personal AI assistant application codebase"
        )
        val capability = DevToolRegistry.detectEnvironment(defaultProject.workspaceRoot)
        _uiState.value = _uiState.value.copy(
            projects = listOf(defaultProject),
            selectedProject = defaultProject,
            environmentCapability = capability
        )
    }

    fun selectProject(project: DevProject) {
        val capability = DevToolRegistry.detectEnvironment(project.workspaceRoot)
        _uiState.value = _uiState.value.copy(
            selectedProject = project,
            environmentCapability = capability,
            currentPlan = null,
            lastReport = null
        )
    }

    fun submitTask(requirement: String, autoApproveSafe: Boolean = false) {
        val project = _uiState.value.selectedProject ?: return
        val plan = devLoop.createPlan(project, requirement)

        _uiState.value = _uiState.value.copy(
            currentPlan = plan,
            isExecuting = true,
            pendingApprovalStep = null,
            consoleLog = listOf("Initiating autonomous dev loop for: '$requirement'")
        )

        executionJob = launchGuarded {
            val report = devLoop.executePlan(
                project = project,
                plan = plan,
                isApprovedByUser = false,
                approvedAction = null,
                onStepProgress = { step ->
                    val logs = _uiState.value.consoleLog.toMutableList()
                    logs.add("[${step.phase}] ${step.title} -> ${step.status}")
                    val pendingApproval = if (step.status == DevStepStatus.AWAITING_APPROVAL) step else null
                    _uiState.value = _uiState.value.copy(
                        currentPlan = plan,
                        consoleLog = logs,
                        pendingApprovalStep = pendingApproval
                    )
                }
            )

            _uiState.value = _uiState.value.copy(
                isExecuting = false,
                lastReport = report
            )
        }
    }

    fun approvePendingStep() {
        val plan = _uiState.value.currentPlan ?: return
        val project = _uiState.value.selectedProject ?: return
        val pending = _uiState.value.pendingApprovalStep ?: return

        pending.status = DevStepStatus.PENDING
        _uiState.value = _uiState.value.copy(
            pendingApprovalStep = null,
            isExecuting = true
        )

        executionJob = launchGuarded {
            val report = devLoop.executePlan(
                project = project,
                plan = plan,
                isApprovedByUser = true,
                approvedAction = pending.outputSummary,
                onStepProgress = { step ->
                    val logs = _uiState.value.consoleLog.toMutableList()
                    logs.add("[APPROVED] [${step.phase}] ${step.title} -> ${step.status}")
                    _uiState.value = _uiState.value.copy(
                        currentPlan = plan,
                        consoleLog = logs
                    )
                }
            )

            _uiState.value = _uiState.value.copy(
                isExecuting = false,
                lastReport = report
            )
        }
    }

    /** Runs one execution; cancellation propagates, any other failure is reported in the console log. */
    private fun launchGuarded(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("SoftwareEngineerWorkspace", "Dev execution failed (${error.javaClass.simpleName})")
            _uiState.value = _uiState.value.copy(
                isExecuting = false,
                consoleLog = _uiState.value.consoleLog + "[ERROR] Execution failed: ${error.javaClass.simpleName}"
            )
        }
    }

    /** Cancels any running execution and, when this coordinator created its own scope, that scope. */
    fun close() {
        executionJob?.cancel()
        if (ownsScope) scope.cancel()
    }

    fun cancelTask() {
        executionJob?.cancel()
        devLoop.cancelExecution()
        val logs = _uiState.value.consoleLog.toMutableList()
        logs.add("[CANCELLED] Task cancelled by user.")
        _uiState.value = _uiState.value.copy(
            isExecuting = false,
            consoleLog = logs
        )
    }
}
