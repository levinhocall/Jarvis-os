package com.jarvis.assistant.automation.dev

import com.jarvis.assistant.data.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        // Register default project workspace if available
        val defaultProject = DevProject(
            name = "JARVIS OS",
            workspaceRoot = "/root/jarvis-os-ref/Part 7",
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

    fun submitTask(requirement: String, autoApproveSafe: Boolean = true) {
        val project = _uiState.value.selectedProject ?: return
        val plan = devLoop.createPlan(project, requirement)

        _uiState.value = _uiState.value.copy(
            currentPlan = plan,
            isExecuting = true,
            pendingApprovalStep = null,
            consoleLog = listOf("Initiating autonomous dev loop for: '$requirement'")
        )

        scope.launch {
            val report = devLoop.executePlan(
                project = project,
                plan = plan,
                isApprovedByUser = autoApproveSafe,
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

        scope.launch {
            val report = devLoop.executePlan(
                project = project,
                plan = plan,
                isApprovedByUser = true,
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

    fun cancelTask() {
        devLoop.cancelExecution()
        val logs = _uiState.value.consoleLog.toMutableList()
        logs.add("[CANCELLED] Task cancelled by user.")
        _uiState.value = _uiState.value.copy(
            isExecuting = false,
            consoleLog = logs
        )
    }
}
