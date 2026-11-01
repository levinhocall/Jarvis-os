package com.jarvis.assistant.automation.dev

import android.content.Context
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.security.sandbox.CapabilitySandbox
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AutonomousDevLoop(
    private val memoryRepo: MemoryRepository? = null,
    private val sandbox: CapabilitySandbox = CapabilitySandbox(),
    private val devAgent: SoftwareEngineeringAgent = SoftwareEngineeringAgent(),
    private val contextProvider: () -> Context? = { null }
) {

    private val _currentPlan = MutableStateFlow<DevTaskPlan?>(null)
    val currentPlan: StateFlow<DevTaskPlan?> = _currentPlan.asStateFlow()

    private val _isCancelled = MutableStateFlow(false)
    val isCancelled: StateFlow<Boolean> = _isCancelled.asStateFlow()

    fun cancelExecution() {
        _isCancelled.value = true
        _currentPlan.value?.status = DevStepStatus.FAILED
    }

    /**
     * Creates an initial structured plan for a user requirement.
     */
    fun createPlan(project: DevProject, requirement: String): DevTaskPlan {
        _isCancelled.value = false
        val plan = DevTaskPlan(
            projectId = project.projectId,
            userRequirement = requirement
        )

        plan.steps.addAll(
            listOf(
                DevTaskStep(
                    phase = DevPhase.INSPECT,
                    title = "Inspect Project Structure & Git Status",
                    description = "Discover project layout, existing source files, and uncommitted changes",
                    toolName = "inspect_structure",
                    toolParams = mapOf("maxDepth" to 3)
                ),
                DevTaskStep(
                    phase = DevPhase.PROPOSE,
                    title = "Analyze Requirements & Propose Changes",
                    description = "Formulate code changes to satisfy '$requirement'"
                ),
                DevTaskStep(
                    phase = DevPhase.EXECUTE,
                    title = "Apply Code Modifications",
                    description = "Atomically edit source files with boundary checking and rollback protection"
                ),
                DevTaskStep(
                    phase = DevPhase.TEST,
                    title = "Execute Verification & Unit Tests",
                    description = "Run tests to confirm zero regressions"
                ),
                DevTaskStep(
                    phase = DevPhase.VERIFY,
                    title = "Verify Output Artifacts",
                    description = "Confirm generated artifacts exist on disk with valid checksums"
                ),
                DevTaskStep(
                    phase = DevPhase.REPORT,
                    title = "Generate Completion Report",
                    description = "Produce evidence-based summary of changes, test outcomes, and limitations"
                )
            )
        )

        _currentPlan.value = plan
        return plan
    }

    /**
     * Executes the task plan sequentially through the 9-stage workflow with bounded repair and rollback.
     */
    suspend fun executePlan(
        project: DevProject,
        plan: DevTaskPlan,
        isApprovedByUser: Boolean = false,
        approvedAction: String? = null,
        onStepProgress: ((DevTaskStep) -> Unit)? = null
    ): DevExecutionReport {
        plan.status = DevStepStatus.IN_PROGRESS
        plan.updatedAt = System.currentTimeMillis()
        _currentPlan.value = plan

        // Persist project context to memory
        memoryRepo?.remember(
            content = "Dev Project '${project.name}': ${project.description} [Root: ${project.workspaceRoot}]",
            type = MemoryEntity.TYPE_PROJECT,
            key = "dev_project_${project.projectId}"
        )

        val modifiedFilesList = mutableListOf<String>()
        val verifiedArtifacts = mutableListOf<ArtifactVerification>()
        val limitations = mutableListOf<String>()
        var testSummary = "Tests not executed"
        var overallSuccess = true

        DevToolRegistry.configureContext(contextProvider)
        val capability = DevToolRegistry.detectEnvironment(project.workspaceRoot, contextProvider())
        if (!capability.canExecuteCommands) {
            limitations.add("Shell command execution is restricted in this environment. Using file modification only.")
        }

        for (step in plan.steps) {
            // Completed stages are immutable on resume; only the explicitly approved
            // pending action may run again.
            if (step.status == DevStepStatus.COMPLETED) continue
            if (_isCancelled.value) {
                step.status = DevStepStatus.FAILED
                step.error = "Execution cancelled by user"
                overallSuccess = false
                break
            }

            step.status = DevStepStatus.IN_PROGRESS
            onStepProgress?.invoke(step)
            val stepStartTime = System.currentTimeMillis()

            try {
                when (step.phase) {
                    DevPhase.PLAN -> {
                        step.status = DevStepStatus.COMPLETED
                        step.outputSummary = "Task plan formulated."
                    }

                    DevPhase.INSPECT -> {
                        val inspectTool = DevToolRegistry.getTool("inspect_structure")
                        val result = inspectTool?.execute(project, step.toolParams ?: emptyMap(), sandbox, devAgent, isApprovedByUser)
                        when (result) {
                            is DevToolResult.Success -> {
                                step.status = DevStepStatus.COMPLETED
                                step.outputSummary = result.output.take(500)
                            }
                            is DevToolResult.Failure -> {
                                step.status = DevStepStatus.FAILED
                                step.error = result.error
                                overallSuccess = false
                            }
                            is DevToolResult.EnvironmentUnsupported -> {
                                step.status = DevStepStatus.BLOCKED
                                step.error = result.reason
                                limitations.add(result.reason)
                                overallSuccess = false
                            }
                            is DevToolResult.AwaitingApproval -> {
                                step.status = DevStepStatus.AWAITING_APPROVAL
                                step.error = result.reason
                                overallSuccess = false
                                break
                            }
                            null -> {
                                step.status = DevStepStatus.BLOCKED
                                step.error = "Project inspection tool is not registered; inspection was not performed."
                                limitations.add(step.error!!)
                                overallSuccess = false
                            }
                        }
                    }

                    DevPhase.PROPOSE -> {
                        step.status = DevStepStatus.BLOCKED
                        step.outputSummary = "No proposal provider is connected; an AI-generated implementation strategy was not produced."
                        limitations.add("Proposal stage is not integrated with an LLM planning provider.")
                        overallSuccess = false
                    }

                    DevPhase.EXECUTE -> {
                        val toolName = step.toolName ?: "edit_file"
                        val tool = DevToolRegistry.getTool(toolName)
                        if (tool != null && step.toolParams != null) {
                            val actionText = step.toolParams!!["command"] as? String
                            val actionApproved = isApprovedByUser && actionText != null && actionText == approvedAction
                            val executionParams = if (actionApproved) step.toolParams!! + mapOf("approvedCommand" to actionText) else step.toolParams!!
                            val result = tool.execute(project, executionParams, sandbox, devAgent, actionApproved)
                            when (result) {
                                is DevToolResult.Success -> {
                                    step.status = DevStepStatus.COMPLETED
                                    step.outputSummary = result.output
                                    modifiedFilesList.addAll(result.modifiedFiles)
                                }
                                is DevToolResult.AwaitingApproval -> {
                                    step.status = DevStepStatus.AWAITING_APPROVAL
                                    step.outputSummary = result.proposedAction
                                    step.error = result.reason
                                    overallSuccess = false
                                    break // Pause for approval
                                }
                                is DevToolResult.Failure -> {
                                    step.status = DevStepStatus.FAILED
                                    step.error = result.error
                                    overallSuccess = false
                                }
                                is DevToolResult.EnvironmentUnsupported -> {
                                    step.status = DevStepStatus.BLOCKED
                                    step.error = result.reason
                                    limitations.add(result.reason)
                                }
                            }
                        } else {
                            step.status = DevStepStatus.BLOCKED
                            step.error = "No registered execution tool and parameters were supplied; no source changes were made."
                            overallSuccess = false
                            limitations.add(step.error!!)
                        }
                    }

                    DevPhase.TEST -> {
                        val testCmd = step.toolParams?.get("command") as? String
                        if (testCmd.isNullOrBlank()) {
                            step.status = DevStepStatus.BLOCKED
                            step.error = "No explicit test command was configured; no tests were run."
                            testSummary = "Not run: no test command configured"
                            limitations.add(step.error!!)
                            overallSuccess = false
                        } else if (!capability.canExecuteCommands) {
                            step.status = DevStepStatus.BLOCKED
                            step.error = "Test execution is unavailable in this environment; no syntax or test pass is inferred."
                            testSummary = "Not run: command execution unavailable"
                            limitations.add(step.error!!)
                            overallSuccess = false
                        } else {
                            val cmdTool = DevToolRegistry.getTool("execute_command")
                            val result = cmdTool?.execute(
                                project,
                                buildMap {
                                    put("command", testCmd)
                                    put("timeoutSeconds", 120)
                                    if (isApprovedByUser && testCmd == approvedAction) put("approvedCommand", testCmd)
                                },
                                sandbox,
                                devAgent,
                                isApprovedByUser && testCmd == approvedAction
                            )
                            when (result) {
                                is DevToolResult.Success -> {
                                    step.status = DevStepStatus.COMPLETED
                                    step.outputSummary = result.output.take(500)
                                    testSummary = "Command exited successfully. Inspect its output to confirm test counts."
                                }
                                is DevToolResult.AwaitingApproval -> {
                                    step.status = DevStepStatus.AWAITING_APPROVAL
                                    step.outputSummary = result.proposedAction
                                    step.error = result.reason
                                    overallSuccess = false
                                    break
                                }
                                is DevToolResult.Failure -> {
                                    step.status = DevStepStatus.FAILED
                                    step.error = result.error
                                    testSummary = "Command failed: ${result.error}"
                                    overallSuccess = false
                                }
                                is DevToolResult.EnvironmentUnsupported -> {
                                    step.status = DevStepStatus.BLOCKED
                                    step.error = result.reason
                                    testSummary = "Not run: ${result.reason}"
                                    limitations.add(result.reason)
                                    overallSuccess = false
                                }
                                null -> {
                                    step.status = DevStepStatus.BLOCKED
                                    step.error = "Test command tool is not registered; no tests were run."
                                    testSummary = "Not run: test command tool unavailable"
                                    overallSuccess = false
                                }
                            }
                        }
                    }

                    DevPhase.ANALYZE -> {
                        step.status = DevStepStatus.BLOCKED
                        step.error = "No diagnostic analyzer is connected; no root-cause analysis was performed."
                        limitations.add(step.error!!)
                        overallSuccess = false
                    }

                    DevPhase.REPAIR -> {
                        step.status = DevStepStatus.BLOCKED
                        step.error = "No repair patch was generated or applied by an automated repair provider."
                        limitations.add(step.error!!)
                        overallSuccess = false
                    }

                    DevPhase.VERIFY -> {
                        val artifactPath = step.toolParams?.get("artifactPath") as? String
                        if (!artifactPath.isNullOrBlank()) {
                            val verifyTool = DevToolRegistry.getTool("verify_artifact")
                            val result = verifyTool?.execute(project, mapOf("artifactPath" to artifactPath), sandbox, devAgent, isApprovedByUser)
                            if (result is DevToolResult.Success && result.artifacts.isNotEmpty()) {
                                step.status = DevStepStatus.COMPLETED
                                step.outputSummary = result.output
                                verifiedArtifacts.addAll(result.artifacts)
                            } else {
                                step.status = DevStepStatus.FAILED
                                step.error = (result as? DevToolResult.Failure)?.error ?: "Artifact verification failed"
                                overallSuccess = false
                            }
                        } else {
                            step.status = DevStepStatus.BLOCKED
                            step.error = "No artifact path was supplied; artifact existence and checksum were not verified."
                            limitations.add(step.error!!)
                            overallSuccess = false
                        }
                    }

                    DevPhase.REPORT -> {
                        step.status = DevStepStatus.COMPLETED
                        step.outputSummary = "Final report ready."
                    }
                }
            } catch (e: Exception) {
                step.status = DevStepStatus.FAILED
                step.error = "Step exception: ${e.message}"
                overallSuccess = false
            } finally {
                step.durationMs = System.currentTimeMillis() - stepStartTime
                onStepProgress?.invoke(step)
            }

            if (step.status == DevStepStatus.FAILED && step.phase != DevPhase.TEST) {
                overallSuccess = false
                break
            }
        }

        plan.status = if (overallSuccess) DevStepStatus.COMPLETED else DevStepStatus.FAILED
        plan.updatedAt = System.currentTimeMillis()
        _currentPlan.value = plan

        // Persist final status to Memory
        memoryRepo?.remember(
            content = "Dev Task '${plan.userRequirement}' finished with status: ${plan.status}. Modified ${modifiedFilesList.size} file(s).",
            type = MemoryEntity.TYPE_ONGOING_TASK,
            key = "dev_task_${plan.planId}"
        )

        val completedCount = plan.steps.count { it.status == DevStepStatus.COMPLETED }
        val reportText = buildString {
            append("=== SOFTWARE ENGINEERING EXECUTION REPORT ===\n")
            append("• Project: ${project.name} (${project.workspaceRoot})\n")
            append("• Requirement: ${plan.userRequirement}\n")
            append("• Status: ${plan.status}\n")
            append("• Steps Completed: $completedCount / ${plan.steps.size}\n")
            append("• Repair Attempts: ${plan.repairAttempts} / ${plan.maxRepairAttempts}\n")
            append("• Modified Files: ${if (modifiedFilesList.isEmpty()) "None" else modifiedFilesList.joinToString()}\n")
            append("• Test Summary: $testSummary\n")
            if (verifiedArtifacts.isNotEmpty()) {
                append("• Verified Artifacts:\n")
                for (a in verifiedArtifacts) {
                    append("   - ${a.artifactPath} (${a.sizeBytes} bytes, SHA-256: ${a.sha256Checksum})\n")
                }
            }
            if (limitations.isNotEmpty()) {
                append("• Limitations Encountered:\n")
                for (l in limitations) {
                    append("   - $l\n")
                }
            }
        }

        return DevExecutionReport(
            planId = plan.planId,
            projectId = project.projectId,
            success = overallSuccess,
            completedSteps = completedCount,
            totalSteps = plan.steps.size,
            modifiedFiles = modifiedFilesList,
            artifacts = verifiedArtifacts,
            testResultsSummary = testSummary,
            limitations = limitations,
            fullReportText = reportText
        )
    }
}
