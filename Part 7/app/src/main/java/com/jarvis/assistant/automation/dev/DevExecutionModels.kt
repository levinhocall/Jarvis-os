package com.jarvis.assistant.automation.dev

import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class DevEnvironmentType {
    LOCAL_PROCESS,
    RESTRICTED_STORAGE_ONLY,
    TERMUX_BRIDGE,
    REMOTE_ENVIRONMENT
}

enum class CapabilityState {
    READY,
    RUNNING,
    NOT_CONFIGURED,
    UNAVAILABLE,
    BLOCKED,
    FAILED
}

enum class ExecutionTarget {
    HOST_LINUX_PROCESS,
    TERMUX_BRIDGE,
    APP_INTERNAL_RESTRICTED,
    UNAVAILABLE
}

data class DevEnvironmentStatus(
    val internalWorkspaceStatus: CapabilityState,
    val internalWorkspacePath: String,
    val termuxStatus: CapabilityState,
    val termuxDetails: String,
    val gitStatus: CapabilityState,
    val gitDetails: String,
    val gradleStatus: CapabilityState,
    val gradleDetails: String,
    val activeExecutionTarget: ExecutionTarget,
    val overallBadge: String,
    val badgeColorHex: String
)

data class DevEnvironmentCapability(
    val type: DevEnvironmentType,
    val canExecuteCommands: Boolean,
    val canAccessFiles: Boolean,
    val canRunGit: Boolean,
    val canRunBuildTools: Boolean,
    val description: String
)

enum class DevToolRisk {
    READ_ONLY,
    MUTATING_SAFE,
    MUTATING_CONSEQUENTIAL,
    DESTRUCTIVE
}

sealed class DevToolResult {
    data class Success(
        val toolName: String,
        val output: String,
        val modifiedFiles: List<String> = emptyList(),
        val artifacts: List<ArtifactVerification> = emptyList(),
        val durationMs: Long = 0L
    ) : DevToolResult()

    data class Failure(
        val toolName: String,
        val error: String,
        val exitCode: Int = -1,
        val isRecoverable: Boolean = true,
        val stdout: String = "",
        val stderr: String = ""
    ) : DevToolResult()

    data class AwaitingApproval(
        val toolName: String,
        val reason: String,
        val proposedAction: String
    ) : DevToolResult()

    data class EnvironmentUnsupported(
        val toolName: String,
        val reason: String,
        val missingCapability: String
    ) : DevToolResult()
}

data class DevProject(
    val projectId: String = UUID.randomUUID().toString(),
    val name: String,
    val workspaceRoot: String,
    val projectType: String = "ANDROID_GRADLE", // ANDROID_GRADLE, KOTLIN_JVM, NODE_WEB, PYTHON, GENERIC
    val description: String = "",
    val gitBranch: String = "main",
    val lastScannedTimestamp: Long = System.currentTimeMillis()
)

enum class DevPhase {
    PLAN,
    INSPECT,
    PROPOSE,
    EXECUTE,
    TEST,
    ANALYZE,
    REPAIR,
    VERIFY,
    REPORT
}

enum class DevStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    BLOCKED,
    AWAITING_APPROVAL,
    ROLLED_BACK
}

data class DevTaskStep(
    val stepId: String = UUID.randomUUID().toString().take(8),
    val phase: DevPhase,
    val title: String,
    val description: String,
    var status: DevStepStatus = DevStepStatus.PENDING,
    var toolName: String? = null,
    var toolParams: Map<String, Any?>? = null,
    var outputSummary: String? = null,
    var error: String? = null,
    var durationMs: Long = 0L
)

data class DevTaskPlan(
    val planId: String = UUID.randomUUID().toString(),
    val projectId: String,
    val userRequirement: String,
    val steps: MutableList<DevTaskStep> = mutableListOf(),
    var status: DevStepStatus = DevStepStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var repairAttempts: Int = 0,
    val maxRepairAttempts: Int = 3
)

data class ArtifactVerification(
    val artifactPath: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val sha256Checksum: String,
    val isValid: Boolean,
    val validationNotes: String = ""
) {
    companion object {
        fun verifyFile(file: File): ArtifactVerification {
            if (!file.exists() || !file.isFile) {
                return ArtifactVerification(
                    artifactPath = file.absolutePath,
                    exists = false,
                    sizeBytes = 0L,
                    sha256Checksum = "",
                    isValid = false,
                    validationNotes = "File does not exist or is not a regular file"
                )
            }

            val size = file.length()
            val checksum = computeSha256(file)
            val isValid = size > 0 && checksum.isNotBlank()

            return ArtifactVerification(
                artifactPath = file.absolutePath,
                exists = true,
                sizeBytes = size,
                sha256Checksum = checksum,
                isValid = isValid,
                validationNotes = if (isValid) "Artifact verified on disk" else "Artifact is empty (0 bytes)"
            )
        }

        private fun computeSha256(file: File): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { fis ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                ""
            }
        }
    }
}

data class DevExecutionReport(
    val planId: String,
    val projectId: String,
    val success: Boolean,
    val completedSteps: Int,
    val totalSteps: Int,
    val modifiedFiles: List<String>,
    val artifacts: List<ArtifactVerification>,
    val testResultsSummary: String,
    val limitations: List<String>,
    val fullReportText: String
)
