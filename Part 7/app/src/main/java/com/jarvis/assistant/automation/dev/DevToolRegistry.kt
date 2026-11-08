package com.jarvis.assistant.automation.dev

import com.jarvis.assistant.security.sandbox.CapabilitySandbox
import com.jarvis.assistant.security.sandbox.SandboxCheckResult
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun isSensitiveDevWorkspacePath(path: String): Boolean {
    val normalized = path.replace('\\', '/').lowercase()
    val fileName = normalized.substringAfterLast('/')
    return fileName in setOf(
        ".env", ".env.local", "local.properties", "gradle.properties",
        "keystore.properties", "credentials.json", "service-account.json"
    ) || normalized.contains("/secrets/") ||
        normalized.endsWith(".jks") || normalized.endsWith(".keystore")
}

private fun writeFileAtomically(target: File, content: String): Boolean {
    val parent = target.parentFile ?: return false
    if (!parent.exists() || !parent.isDirectory) return false
    val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
    return try {
        FileOutputStream(temporary).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target.isFile && target.readText() == content
    } catch (_: Exception) {
        false
    } finally {
        temporary.delete()
    }
}

interface DevTool {
    val name: String
    val description: String
    val riskLevel: DevToolRisk
    val requiresApproval: Boolean
    suspend fun execute(
        project: DevProject,
        params: Map<String, Any?>,
        sandbox: CapabilitySandbox,
        devAgent: SoftwareEngineeringAgent,
        isApprovedByUser: Boolean = false
    ): DevToolResult
}

class ReadFileDevTool : DevTool {
    override val name: String = "read_file"
    override val description: String = "Reads file content safely from within the workspace root"
    override val riskLevel: DevToolRisk = DevToolRisk.READ_ONLY
    override val requiresApproval: Boolean = false

    override suspend fun execute(
        project: DevProject,
        params: Map<String, Any?>,
        sandbox: CapabilitySandbox,
        devAgent: SoftwareEngineeringAgent,
        isApprovedByUser: Boolean
    ): DevToolResult = withContext(Dispatchers.IO) {
        val relPath = params["path"] as? String
            ?: return@withContext DevToolResult.Failure(name, "Missing parameter 'path'")

        if (isSensitiveDevWorkspacePath(relPath)) {
            return@withContext DevToolResult.Failure(
                name,
                "Sensitive configuration and credential files cannot be read through Dev Studio.",
                isRecoverable = false
            )
        }

        val pathCheck = sandbox.validateFilePath(project.workspaceRoot, relPath)
        if (pathCheck is SandboxCheckResult.Blocked) {
            return@withContext DevToolResult.Failure(name, "Path rejected: ${pathCheck.reason}", isRecoverable = false)
        }

        val targetFile = File(project.workspaceRoot, relPath)
        if (!targetFile.exists() || !targetFile.isFile) {
            return@withContext DevToolResult.Failure(name, "File '$relPath' not found in workspace", isRecoverable = false)
        }

        try {
            val maxLines = (params["maxLines"] as? Number)?.toInt() ?: 2000
            val lines = targetFile.bufferedReader().useLines { it.take(maxLines).toList() }
            val content = lines.joinToString("\n")
            DevToolResult.Success(
                toolName = name,
                output = content,
                durationMs = 0L
            )
        } catch (e: Exception) {
            DevToolResult.Failure(name, "Failed reading file: ${e.message}")
        }
    }
}

class EditFileDevTool : DevTool {
    override val name: String = "edit_file"
    override val description: String = "Applies atomic snippet replacement to a file in workspace"
    override val riskLevel: DevToolRisk = DevToolRisk.MUTATING_SAFE
    override val requiresApproval: Boolean = false

    override suspend fun execute(
        project: DevProject,
        params: Map<String, Any?>,
        sandbox: CapabilitySandbox,
        devAgent: SoftwareEngineeringAgent,
        isApprovedByUser: Boolean
    ): DevToolResult = withContext(Dispatchers.IO) {
        val relPath = params["path"] as? String
            ?: return@withContext DevToolResult.Failure(name, "Missing parameter 'path'")
        val original = params["originalSnippet"] as? String
            ?: return@withContext DevToolResult.Failure(name, "Missing parameter 'originalSnippet'")
        val replacement = params["replacementSnippet"] as? String
            ?: return@withContext DevToolResult.Failure(name, "Missing parameter 'replacementSnippet'")

        if (isSensitiveDevWorkspacePath(relPath)) {
            return@withContext DevToolResult.Failure(
                name,
                "Sensitive configuration and credential files cannot be modified through Dev Studio.",
                isRecoverable = false
            )
        }

        val pathCheck = sandbox.validateFilePath(project.workspaceRoot, relPath)
        if (pathCheck is SandboxCheckResult.Blocked) {
            return@withContext DevToolResult.Failure(name, "Path rejected: ${pathCheck.reason}", isRecoverable = false)
        }

        val targetFile = File(project.workspaceRoot, relPath)
        if (!targetFile.exists()) {
            return@withContext DevToolResult.Failure(name, "File '$relPath' does not exist")
        }

        val existingContent = targetFile.readText()
        val fileMap = mutableMapOf(relPath to existingContent)
        val patch = MultiFilePatch(
            description = "Update $relPath",
            hunks = listOf(
                PatchHunk(targetFilePath = relPath, originalSnippet = original, replacementSnippet = replacement)
            )
        )

        val patchResult = devAgent.applyMultiFilePatch(project.workspaceRoot, fileMap, patch)
        when (patchResult) {
            is PatchApplicationResult.Success -> {
                val updatedContent = fileMap[relPath]
                    ?: return@withContext DevToolResult.Failure(name, "Patch output was missing; file was not changed.")
                if (!writeFileAtomically(targetFile, updatedContent)) {
                    return@withContext DevToolResult.Failure(
                        name,
                        "Atomic file replacement or read-back verification failed for '$relPath'."
                    )
                }
                DevToolResult.Success(
                    toolName = name,
                    output = "Successfully updated and read-back verified '$relPath'",
                    modifiedFiles = listOf(relPath)
                )
            }
            is PatchApplicationResult.Failed -> {
                DevToolResult.Failure(
                    toolName = name,
                    error = "Patch application failed: ${patchResult.reason} (Rolled back: ${patchResult.rolledBack})"
                )
            }
            is PatchApplicationResult.RejectedBoundaryViolation -> {
                DevToolResult.Failure(
                    toolName = name,
                    error = "Security boundary violation: ${patchResult.reason}",
                    isRecoverable = false
                )
            }
        }
    }
}

class InspectStructureDevTool : DevTool {
    override val name: String = "inspect_structure"
    override val description: String = "Discovers project directory structure and files"
    override val riskLevel: DevToolRisk = DevToolRisk.READ_ONLY
    override val requiresApproval: Boolean = false

    override suspend fun execute(
        project: DevProject,
        params: Map<String, Any?>,
        sandbox: CapabilitySandbox,
        devAgent: SoftwareEngineeringAgent,
        isApprovedByUser: Boolean
    ): DevToolResult = withContext(Dispatchers.IO) {
        val root = File(project.workspaceRoot)
        if (!root.exists() || !root.isDirectory) {
            return@withContext DevToolResult.Failure(name, "Workspace root '$root' is not a valid directory")
        }

        val maxDepth = (params["maxDepth"] as? Number)?.toInt() ?: 3
        val ignoredDirs = setOf(".git", ".gradle", "build", "node_modules", ".idea", "dist", "out", "target")

        fun scan(dir: File, currentDepth: Int): List<String> {
            if (currentDepth > maxDepth) return emptyList()
            val list = mutableListOf<String>()
            val files = dir.listFiles() ?: return emptyList()
            for (f in files.sortedBy { it.name }) {
                if (f.name in ignoredDirs) continue
                val rel = f.relativeTo(root).path
                if (f.isDirectory) {
                    list.add("[DIR]  $rel")
                    list.addAll(scan(f, currentDepth + 1))
                } else {
                    list.add("[FILE] $rel (${f.length()} bytes)")
                }
            }
            return list
        }

        val tree = scan(root, 1)
        DevToolResult.Success(
            toolName = name,
            output = tree.joinToString("\n")
        )
    }
}

class GitStatusAndDiffDevTool(private val contextProvider: () -> android.content.Context? = { null }) : DevTool {
    override val name: String = "git_status_and_diff"
    override val description: String = "Inspects Git repository status and current uncommitted diffs"
    override val riskLevel: DevToolRisk = DevToolRisk.READ_ONLY
    override val requiresApproval: Boolean = false

    override suspend fun execute(
        project: DevProject,
        params: Map<String, Any?>,
        sandbox: CapabilitySandbox,
        devAgent: SoftwareEngineeringAgent,
        isApprovedByUser: Boolean
    ): DevToolResult = withContext(Dispatchers.IO) {
        val root = File(project.workspaceRoot)
        val gitDir = File(root, ".git")
        if (!gitDir.exists()) {
            return@withContext DevToolResult.Failure(name, "Not a Git repository (no .git directory found at '${project.workspaceRoot}')")
        }

        val context = contextProvider()

        if (ShellPolicy.isHostShellAvailable()) {
            // Fixed, read-only commands run through the central policy (no shell involved).
            val status = ShellPolicy.runHost("git status --short", root, 5)
            val diff = ShellPolicy.runHost("git diff --stat", root, 5)
            val failure = status.refusedReason ?: diff.refusedReason
            if (failure != null) {
                return@withContext DevToolResult.Failure(name, "Host git execution error: $failure")
            }
            val summary = buildString {
                append("=== GIT STATUS ===\n").append(status.output.ifBlank { "Clean working tree\n" })
                append("\n=== DIFF STAT ===\n").append(diff.output.ifBlank { "No modified tracked files\n" })
            }
            return@withContext DevToolResult.Success(toolName = name, output = summary)
        }

        // On Android: check Termux
        if (context != null && TermuxBridge.isTermuxInstalled(context)) {
            return@withContext TermuxBridge.executeCommand(
                context = context,
                command = "git -C '${project.workspaceRoot}' status --short && git -C '${project.workspaceRoot}' diff --stat",
                workDir = project.workspaceRoot
            )
        }

        // Safe in-process inspection of .git metadata without execve
        val branch = try {
            val headFile = File(gitDir, "HEAD")
            if (headFile.exists()) {
                val line = headFile.readText().trim()
                if (line.startsWith("ref: refs/heads/")) line.removePrefix("ref: refs/heads/").trim() else line.take(8)
            } else "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        DevToolResult.EnvironmentUnsupported(
            toolName = name,
            reason = "Native 'git' CLI cannot run directly in standard Android app sandbox (Repository detected, Branch: $branch). Install Termux and configure allow-external-apps to run full git CLI on-device.",
            missingCapability = "com.termux"
        )
    }
}

class ExecuteApprovedCommandDevTool(private val contextProvider: () -> android.content.Context? = { null }) : DevTool {
    override val name: String = "execute_command"
    override val description: String = "Executes an approved, audited shell command inside workspace root"
    override val riskLevel: DevToolRisk = DevToolRisk.MUTATING_CONSEQUENTIAL
    override val requiresApproval: Boolean = true

    override suspend fun execute(
        project: DevProject,
        params: Map<String, Any?>,
        sandbox: CapabilitySandbox,
        devAgent: SoftwareEngineeringAgent,
        isApprovedByUser: Boolean
    ): DevToolResult = withContext(Dispatchers.IO) {
        val command = params["command"] as? String
            ?: return@withContext DevToolResult.Failure(name, "Missing parameter 'command'")

        // 1. Prompt Injection & Malicious Directive Check
        val textCheck = sandbox.validateInputText(command)
        if (textCheck is SandboxCheckResult.Blocked) {
            return@withContext DevToolResult.Failure(name, "Security check blocked command: ${textCheck.reason}", isRecoverable = false)
        }

        // Central policy: blocked commands never run, whatever the approval state.
        val classification = ShellPolicy.classify(command)
        if (classification.risk == ShellRisk.BLOCKED) {
            return@withContext DevToolResult.Failure(name, "Shell policy blocked command: ${classification.reason}", isRecoverable = false)
        }

        // Approval is bound to the exact command text AND the working directory it was shown for.
        // A caller-supplied Boolean alone is not proof that this specific command was approved.
        val explicitlyApproved = ShellPolicy.isApproved(isApprovedByUser, params, command, project.workspaceRoot)

        // 2. Destructive Git Check
        val gitRisk = devAgent.auditGitOperation(command)
        if (gitRisk.isDestructive && !explicitlyApproved) {
            return@withContext DevToolResult.AwaitingApproval(
                toolName = name,
                reason = "Potentially destructive command requires explicit user approval: ${gitRisk.warningMessage}",
                proposedAction = command
            )
        }
        if (!explicitlyApproved) {
            return@withContext DevToolResult.AwaitingApproval(
                toolName = name,
                reason = "Shell execution requires explicit approval for this exact command (${classification.risk}: ${classification.reason}).",
                proposedAction = command
            )
        }

        val timeoutSec = (params["timeoutSeconds"] as? Number)?.toLong() ?: 60L

        if (ShellPolicy.isHostShellAvailable()) {
            // Approval for this exact command and directory counts as the explicit authorization.
            val run = ShellPolicy.runHost(command, File(project.workspaceRoot), timeoutSec, authorized = true)
            return@withContext when {
                run.refused -> DevToolResult.Failure(toolName = name, error = run.refusedReason ?: "Refused by shell policy", isRecoverable = false)
                run.timedOut -> DevToolResult.Failure(
                    toolName = name,
                    error = "Command timed out after $timeoutSec seconds",
                    exitCode = -1,
                    stdout = run.output
                )
                run.exitCode == 0 -> DevToolResult.Success(toolName = name, output = run.output, durationMs = run.durationMs)
                else -> DevToolResult.Failure(
                    toolName = name,
                    error = "Command exited with non-zero code ${run.exitCode}",
                    exitCode = run.exitCode,
                    stdout = run.output
                )
            }
        }

        // On Android: check Termux
        val context = contextProvider()
        if (context != null && TermuxBridge.isTermuxInstalled(context)) {
            return@withContext TermuxBridge.executeCommand(context, command, project.workspaceRoot, timeoutSec)
        }

        DevToolResult.EnvironmentUnsupported(
            toolName = name,
            reason = "Shell execution is blocked by Android app sandbox. Install Termux from F-Droid to enable shell commands.",
            missingCapability = "com.termux"
        )
    }
}

class TermuxBridgeDevTool(private val contextProvider: () -> android.content.Context?) : DevTool {
    override val name: String = "termux_execute"
    override val description: String = "Executes native commands via Termux RUN_COMMAND service on Android without root"
    override val riskLevel: DevToolRisk = DevToolRisk.MUTATING_CONSEQUENTIAL
    override val requiresApproval: Boolean = true

    override suspend fun execute(
        project: DevProject,
        params: Map<String, Any?>,
        sandbox: CapabilitySandbox,
        devAgent: SoftwareEngineeringAgent,
        isApprovedByUser: Boolean
    ): DevToolResult {
        val context = contextProvider()
            ?: return DevToolResult.Failure(name, "Android application context unavailable for Termux bridge")
        val command = params["command"] as? String
            ?: return DevToolResult.Failure(name, "Missing parameter 'command'")

        val textCheck = sandbox.validateInputText(command)
        if (textCheck is SandboxCheckResult.Blocked) {
            return DevToolResult.Failure(name, "Security check blocked command: ${textCheck.reason}", isRecoverable = false)
        }

        val classification = ShellPolicy.classify(command)
        if (classification.risk == ShellRisk.BLOCKED) {
            return DevToolResult.Failure(name, "Shell policy blocked command: ${classification.reason}", isRecoverable = false)
        }

        val explicitlyApproved = ShellPolicy.isApproved(isApprovedByUser, params, command, project.workspaceRoot)

        val gitRisk = devAgent.auditGitOperation(command)
        if (gitRisk.isDestructive && !explicitlyApproved) {
            return DevToolResult.AwaitingApproval(
                toolName = name,
                reason = "Potentially destructive command requires approval in Termux: ${gitRisk.warningMessage}",
                proposedAction = command
            )
        }
        if (!explicitlyApproved) {
            return DevToolResult.AwaitingApproval(
                toolName = name,
                reason = "Termux shell execution requires explicit approval for this exact command.",
                proposedAction = command
            )
        }

        val timeoutSec = (params["timeoutSeconds"] as? Number)?.toLong() ?: 120L
        return TermuxBridge.executeCommand(context, command, project.workspaceRoot, timeoutSec)
    }
}

class ArtifactVerificationDevTool : DevTool {
    override val name: String = "verify_artifact"
    override val description: String = "Verifies artifact existence, non-empty size, and SHA-256 on disk"
    override val riskLevel: DevToolRisk = DevToolRisk.READ_ONLY
    override val requiresApproval: Boolean = false

    override suspend fun execute(
        project: DevProject,
        params: Map<String, Any?>,
        sandbox: CapabilitySandbox,
        devAgent: SoftwareEngineeringAgent,
        isApprovedByUser: Boolean
    ): DevToolResult = withContext(Dispatchers.IO) {
        val relPath = params["artifactPath"] as? String
            ?: return@withContext DevToolResult.Failure(name, "Missing parameter 'artifactPath'")

        val targetFile = if (File(relPath).isAbsolute) File(relPath) else File(project.workspaceRoot, relPath)
        val verification = ArtifactVerification.verifyFile(targetFile)

        if (verification.isValid) {
            DevToolResult.Success(
                toolName = name,
                output = "Artifact verified: ${verification.artifactPath} (${verification.sizeBytes} bytes, SHA-256: ${verification.sha256Checksum})",
                artifacts = listOf(verification)
            )
        } else {
            DevToolResult.Failure(
                toolName = name,
                error = "Artifact invalid or missing: ${verification.validationNotes} (${verification.artifactPath})"
            )
        }
    }
}

object DevToolRegistry {
    private val tools = ConcurrentHashMap<String, DevTool>()

    init {
        register(ReadFileDevTool())
        register(EditFileDevTool())
        register(InspectStructureDevTool())
        register(GitStatusAndDiffDevTool())
        register(ExecuteApprovedCommandDevTool())
        register(TermuxBridgeDevTool { null })
        register(ArtifactVerificationDevTool())
    }

    fun register(tool: DevTool) {
        tools[tool.name] = tool
    }

    /**
     * Binds Android-backed tools to the application context without retaining an Activity.
     * Reconfiguration is safe across Fragment recreation and process-level app startup.
     */
    fun configureContext(contextProvider: () -> android.content.Context?) {
        register(GitStatusAndDiffDevTool(contextProvider))
        register(ExecuteApprovedCommandDevTool(contextProvider))
        register(TermuxBridgeDevTool(contextProvider))
    }

    fun getTool(name: String): DevTool? = tools[name]

    fun getAllTools(): List<DevTool> = tools.values.toList()

    fun detectEnvironment(workspacePath: String, context: android.content.Context? = null): DevEnvironmentCapability {
        val file = File(workspacePath)
        val canAccessFiles = file.exists() && file.canRead()
        val hasTermux = context != null && TermuxBridge.isTermuxInstalled(context)
        val termuxPermitted = hasTermux && context != null && TermuxBridge.isRunCommandPermissionGranted(context)

        val isHostDev = ShellPolicy.isHostShellAvailable()

        // Android's ability to spawn a small system utility does not imply that a usable
        // shell, Git, compiler, or Gradle installation is available. Only the verified host
        // toolchain or an installed + permission-enabled Termux bridge counts as execution.
        val canRunProcess = isHostDev || termuxPermitted
        val canRunGit = isHostDev || termuxPermitted

        val type = when {
            isHostDev && canRunProcess && canRunGit -> DevEnvironmentType.LOCAL_PROCESS
            termuxPermitted -> DevEnvironmentType.TERMUX_BRIDGE
            hasTermux -> DevEnvironmentType.TERMUX_BRIDGE
            canAccessFiles -> DevEnvironmentType.RESTRICTED_STORAGE_ONLY
            else -> DevEnvironmentType.RESTRICTED_STORAGE_ONLY
        }

        return DevEnvironmentCapability(
            type = type,
            canExecuteCommands = canRunProcess || termuxPermitted,
            canAccessFiles = canAccessFiles,
            canRunGit = canRunGit || termuxPermitted,
            canRunBuildTools = canRunProcess || termuxPermitted,
            description = when (type) {
                DevEnvironmentType.TERMUX_BRIDGE -> if (termuxPermitted) {
                    "Termux Bridge active: full Linux shell, compiler, Git, and build tools available on-device without root"
                } else {
                    "Termux installed but RUN_COMMAND permission is missing; enable permission in app settings"
                }
                DevEnvironmentType.LOCAL_PROCESS -> "Host development environment with native shell execution and Git support"
                DevEnvironmentType.RESTRICTED_STORAGE_ONLY -> "Android app sandbox: internal file editing supported; install Termux to enable shell commands and Git on phone"
                else -> "Standard environment"
            }
        )
    }
}
