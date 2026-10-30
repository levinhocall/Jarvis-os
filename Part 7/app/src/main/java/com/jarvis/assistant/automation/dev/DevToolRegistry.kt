package com.jarvis.assistant.automation.dev

import com.jarvis.assistant.security.sandbox.CapabilitySandbox
import com.jarvis.assistant.security.sandbox.SandboxCheckResult
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                targetFile.writeText(fileMap[relPath]!!)
                DevToolResult.Success(
                    toolName = name,
                    output = "Successfully updated '$relPath'",
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
        val isHostDev = File("/root/jarvis-os-ref").exists() && File("/usr/bin/git").exists()

        if (isHostDev) {
            try {
                val statusProcess = ProcessBuilder("git", "status", "--short")
                    .directory(root)
                    .redirectErrorStream(true)
                    .start()

                val statusOutput = statusProcess.inputStream.bufferedReader().use { it.readText() }
                statusProcess.waitFor(5, TimeUnit.SECONDS)

                val diffProcess = ProcessBuilder("git", "diff", "--stat")
                    .directory(root)
                    .redirectErrorStream(true)
                    .start()
                val diffStat = diffProcess.inputStream.bufferedReader().use { it.readText() }
                diffProcess.waitFor(5, TimeUnit.SECONDS)

                val summary = buildString {
                    append("=== GIT STATUS ===\n").append(statusOutput.ifBlank { "Clean working tree\n" })
                    append("\n=== DIFF STAT ===\n").append(diffStat.ifBlank { "No modified tracked files\n" })
                }

                return@withContext DevToolResult.Success(toolName = name, output = summary)
            } catch (e: Exception) {
                return@withContext DevToolResult.Failure(name, "Host git execution error: ${e.message}")
            }
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

        // 2. Destructive Git Check
        val gitRisk = devAgent.auditGitOperation(command)
        if (gitRisk.isDestructive && !isApprovedByUser) {
            return@withContext DevToolResult.AwaitingApproval(
                toolName = name,
                reason = "Potentially destructive command requires explicit user approval: ${gitRisk.warningMessage}",
                proposedAction = command
            )
        }
        if (!isApprovedByUser) {
            return@withContext DevToolResult.AwaitingApproval(
                toolName = name,
                reason = "Shell execution requires explicit approval for this exact command.",
                proposedAction = command
            )
        }

        val timeoutSec = (params["timeoutSeconds"] as? Number)?.toLong() ?: 60L
        val isHostDev = File("/root/jarvis-os-ref").exists() && File("/bin/bash").exists()

        if (isHostDev) {
            val startTime = System.currentTimeMillis()
            try {
                val process = ProcessBuilder("bash", "-c", command)
                    .directory(File(project.workspaceRoot))
                    .redirectErrorStream(true)
                    .start()

                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
                val duration = System.currentTimeMillis() - startTime

                if (!finished) {
                    process.destroyForcibly()
                    return@withContext DevToolResult.Failure(
                        toolName = name,
                        error = "Command timed out after $timeoutSec seconds",
                        exitCode = -1,
                        stdout = stdout
                    )
                }

                val exitCode = process.exitValue()
                return@withContext if (exitCode == 0) {
                    DevToolResult.Success(toolName = name, output = stdout, durationMs = duration)
                } else {
                    DevToolResult.Failure(toolName = name, error = "Command exited with non-zero code $exitCode", exitCode = exitCode, stdout = stdout)
                }
            } catch (e: Exception) {
                return@withContext DevToolResult.Failure(toolName = name, error = "Host execution failed: ${e.message}")
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

        val gitRisk = devAgent.auditGitOperation(command)
        if (gitRisk.isDestructive && !isApprovedByUser) {
            return DevToolResult.AwaitingApproval(
                toolName = name,
                reason = "Potentially destructive command requires approval in Termux: ${gitRisk.warningMessage}",
                proposedAction = command
            )
        }
        if (!isApprovedByUser) {
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
        register(ArtifactVerificationDevTool())
    }

    fun register(tool: DevTool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): DevTool? = tools[name]

    fun getAllTools(): List<DevTool> = tools.values.toList()

    fun detectEnvironment(workspacePath: String, context: android.content.Context? = null): DevEnvironmentCapability {
        val file = File(workspacePath)
        val canAccessFiles = file.exists() && file.canRead()
        val hasTermux = context != null && TermuxBridge.isTermuxInstalled(context)
        val termuxPermitted = hasTermux && context != null && TermuxBridge.isRunCommandPermissionGranted(context)

        val isHostDev = File("/root/jarvis-os-ref").exists() && File("/usr/bin/git").exists()

        val canRunProcess = isHostDev || try {
            val p = ProcessBuilder("echo", "test").start()
            p.waitFor(1, TimeUnit.SECONDS)
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }

        val canRunGit = isHostDev || try {
            val p = ProcessBuilder("git", "--version").start()
            p.waitFor(1, TimeUnit.SECONDS)
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }

        val type = when {
            isHostDev && canRunProcess && canRunGit -> DevEnvironmentType.LOCAL_PROCESS
            termuxPermitted -> DevEnvironmentType.TERMUX_BRIDGE
            hasTermux -> DevEnvironmentType.TERMUX_BRIDGE
            canAccessFiles -> DevEnvironmentType.RESTRICTED_STORAGE_ONLY
            else -> DevEnvironmentType.RESTRICTED_STORAGE_ONLY
        }

        return DevEnvironmentCapability(
            type = type,
            canExecuteCommands = canRunProcess || hasTermux,
            canAccessFiles = canAccessFiles,
            canRunGit = canRunGit || hasTermux,
            canRunBuildTools = canRunProcess || hasTermux,
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
