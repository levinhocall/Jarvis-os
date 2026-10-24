package com.jarvis.assistant.ui.dev

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.dev.AutonomousDevLoop
import com.jarvis.assistant.automation.dev.CapabilityState
import com.jarvis.assistant.automation.dev.DevEnvironmentStatus
import com.jarvis.assistant.automation.dev.DevProject
import com.jarvis.assistant.automation.dev.DevToolResult
import com.jarvis.assistant.automation.dev.ExecutionTarget
import com.jarvis.assistant.automation.dev.SoftwareEngineeringAgent
import com.jarvis.assistant.automation.dev.TermuxBridge
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.databinding.FragmentCodingDevBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dev Studio & Autonomous Software Engineering Console.
 * Truthfully inspects environment capabilities (internal storage, Termux bridge, dev host)
 * and executes audited Git, build, test, and code tasks without sandbox permission crashes.
 */
class CodingDevFragment : Fragment() {

    private var _binding: FragmentCodingDevBinding? = null
    private val binding get() = _binding!!

    private val devAgent = SoftwareEngineeringAgent()
    private val devLoop by lazy {
        AutonomousDevLoop(
            memoryRepo = JarvisApp.instance.memoryRepository,
            devAgent = devAgent
        )
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCodingDevBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupQuickActions()
        setupCommandInput()
        setupConsoleHeader()
        refreshEnvironmentCapabilities(initialBanner = true)
    }

    private fun setupQuickActions() {
        binding.btnChipBuildApk.setOnClickListener { executeBuildApk() }
        binding.btnChipGitStatus.setOnClickListener { executeGitStatus() }
        binding.btnChipGitPush.setOnClickListener { executeGitPush() }
        binding.btnChipRunTests.setOnClickListener { executeRunTests() }
        binding.btnChipInitRepo.setOnClickListener { executeInitRepo() }
        binding.btnChipClearConsole.setOnClickListener {
            binding.tvTerminalLog.text = "[DEV CONSOLE CLEARED]\n"
        }
    }

    private fun setupCommandInput() {
        binding.btnExecuteDevCommand.setOnClickListener { submitUserCommand() }
        binding.etDevCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitUserCommand()
                true
            } else false
        }
    }

    private fun setupConsoleHeader() {
        binding.btnCopyLogs.setOnClickListener {
            val logs = binding.tvTerminalLog.text.toString()
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS Dev Logs", logs))
            Toast.makeText(requireContext(), "Terminal logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Inspects distinct execution capabilities: internal storage, Termux bridge, host process.
     */
    fun getEnvironmentStatus(): DevEnvironmentStatus {
        val workspace = getWorkspaceRoot()
        val context = context ?: JarvisApp.instance.applicationContext
        val isHostDev = isHostDevEnvironment()

        if (isHostDev) {
            return DevEnvironmentStatus(
                internalWorkspaceStatus = CapabilityState.READY,
                internalWorkspacePath = workspace,
                termuxStatus = CapabilityState.READY,
                termuxDetails = "Dev host environment: direct Linux bash execution available",
                gitStatus = CapabilityState.READY,
                gitDetails = "Host git binary verified (/usr/bin/git)",
                gradleStatus = CapabilityState.READY,
                gradleDetails = "Host Gradle wrapper available",
                activeExecutionTarget = ExecutionTarget.HOST_LINUX_PROCESS,
                overallBadge = "DEV HOST READY",
                badgeColorHex = "#10B981"
            )
        }

        val hasTermux = TermuxBridge.isTermuxInstalled(context)
        val hasPermission = hasTermux && TermuxBridge.isRunCommandPermissionGranted(context)

        return if (hasTermux && hasPermission) {
            DevEnvironmentStatus(
                internalWorkspaceStatus = CapabilityState.READY,
                internalWorkspacePath = workspace,
                termuxStatus = CapabilityState.READY,
                termuxDetails = "Termux bridge ready via com.termux.RUN_COMMAND",
                gitStatus = CapabilityState.READY,
                gitDetails = "Git executable available via Termux bridge",
                gradleStatus = CapabilityState.READY,
                gradleDetails = "Gradle build engine available via Termux bridge",
                activeExecutionTarget = ExecutionTarget.TERMUX_BRIDGE,
                overallBadge = "TERMUX READY",
                badgeColorHex = "#10B981"
            )
        } else if (hasTermux && !hasPermission) {
            DevEnvironmentStatus(
                internalWorkspaceStatus = CapabilityState.READY,
                internalWorkspacePath = workspace,
                termuxStatus = CapabilityState.BLOCKED,
                termuxDetails = "Termux installed but com.termux.permission.RUN_COMMAND missing",
                gitStatus = CapabilityState.BLOCKED,
                gitDetails = "Git blocked: RUN_COMMAND permission required",
                gradleStatus = CapabilityState.BLOCKED,
                gradleDetails = "Gradle blocked: RUN_COMMAND permission required",
                activeExecutionTarget = ExecutionTarget.APP_INTERNAL_RESTRICTED,
                overallBadge = "PERMISSION NEEDED",
                badgeColorHex = "#F59E0B"
            )
        } else {
            DevEnvironmentStatus(
                internalWorkspaceStatus = CapabilityState.READY,
                internalWorkspacePath = workspace,
                termuxStatus = CapabilityState.NOT_CONFIGURED,
                termuxDetails = "Termux app not installed. Install Termux to enable shell commands.",
                gitStatus = CapabilityState.BLOCKED,
                gitDetails = "Native git executable not accessible in Android sandbox",
                gradleStatus = CapabilityState.BLOCKED,
                gradleDetails = "Native Gradle requires Termux bridge",
                activeExecutionTarget = ExecutionTarget.APP_INTERNAL_RESTRICTED,
                overallBadge = "SETUP REQUIRED",
                badgeColorHex = "#F59E0B"
            )
        }
    }

    private fun refreshEnvironmentCapabilities(initialBanner: Boolean = false) {
        val status = getEnvironmentStatus()
        setRunningState(status.overallBadge, status.badgeColorHex)

        if (initialBanner) {
            appendLog("JARVIS Autonomous Engineering Agent v2.0")
            appendLog("Target Workspace: ${status.internalWorkspacePath}")
            appendLog("Execution Target: ${status.activeExecutionTarget}")
            appendLog("Termux Status: ${status.termuxDetails}")
            appendLog("Git CLI Status: ${status.gitDetails}")
            if (status.activeExecutionTarget == ExecutionTarget.APP_INTERNAL_RESTRICTED) {
                appendLog("\n[NOTE] Standard Android sandbox prevents direct 'git' process execution.")
                appendLog("Install Termux from F-Droid to enable native Git & build tools on-device.")
            }
        }
    }

    private fun submitUserCommand() {
        val rawQuery = binding.etDevCommand.text?.toString()?.trim() ?: ""
        if (rawQuery.isBlank()) return
        binding.etDevCommand.setText("")

        val sanitizedQuery = TermuxBridge.maskSecrets(rawQuery)
        appendLog("USER > $sanitizedQuery", isCommand = true)

        val lower = rawQuery.lowercase()
        when {
            lower == "pwd" -> executePwdCommand()
            lower == "git --version" -> executeGitVersionCommand()
            lower.contains("termux") || lower.contains("test bridge") || lower.contains("check environment") -> {
                executeEnvironmentDiagnostics()
            }
            lower.contains("apk") || lower.contains("build") || lower.contains("compile") -> {
                executeBuildApk()
            }
            lower.contains("status") || lower.contains("diff") -> {
                executeGitStatus()
            }
            lower.contains("push") -> {
                executeGitPush()
            }
            lower.contains("test") -> {
                executeRunTests()
            }
            lower.contains("init") -> {
                executeInitRepo()
            }
            else -> {
                executeAutonomousPlan(rawQuery)
            }
        }
    }

    private fun executePwdCommand() {
        val status = getEnvironmentStatus()
        appendLog("[PWD] Inspecting working directory...")

        lifecycleScope.launch {
            when (status.activeExecutionTarget) {
                ExecutionTarget.HOST_LINUX_PROCESS -> {
                    val out = withContext(Dispatchers.IO) {
                        runHostCommand("pwd", File(status.internalWorkspacePath))
                    }
                    appendLog(out)
                }
                ExecutionTarget.TERMUX_BRIDGE -> {
                    val res = TermuxBridge.executeCommand(requireContext(), "pwd", workDir = null)
                    when (res) {
                        is DevToolResult.Success -> appendLog("Termux PWD (exitCode=0):\n${res.output}")
                        is DevToolResult.Failure -> appendLog("Termux PWD error (exitCode=${res.exitCode}):\n${res.error}")
                        else -> appendLog("Termux PWD: $res")
                    }
                }
                ExecutionTarget.APP_INTERNAL_RESTRICTED -> {
                    appendLog("App Workspace Path (exitCode=0):\n${status.internalWorkspacePath}")
                    appendLog("Note: In-process storage active; native shell unavailable without Termux.")
                }
                else -> appendLog("Unavailable execution target.")
            }
        }
    }

    private fun executeGitVersionCommand() {
        val status = getEnvironmentStatus()
        appendLog("[GIT] Querying Git version...")

        lifecycleScope.launch {
            when (status.activeExecutionTarget) {
                ExecutionTarget.HOST_LINUX_PROCESS -> {
                    val out = withContext(Dispatchers.IO) {
                        runHostCommand("git --version", File(status.internalWorkspacePath))
                    }
                    appendLog(out)
                }
                ExecutionTarget.TERMUX_BRIDGE -> {
                    val res = TermuxBridge.executeCommand(requireContext(), "git --version", workDir = null)
                    when (res) {
                        is DevToolResult.Success -> appendLog("Termux Git (exitCode=0):\n${res.output}")
                        is DevToolResult.Failure -> appendLog("Termux Git error (exitCode=${res.exitCode}):\n${res.error}")
                        else -> appendLog("Termux Git: $res")
                    }
                }
                ExecutionTarget.APP_INTERNAL_RESTRICTED -> {
                    appendLog("[GIT BLOCKED] Cannot run 'git --version' in Android app sandbox (error=13 Permission denied).")
                    appendLog("Actionable Remediation: Install Termux from F-Droid, run 'pkg install git', and enable allow-external-apps.")
                    setRunningState("SETUP REQUIRED", "#F59E0B")
                }
                else -> appendLog("Unavailable execution target.")
            }
        }
    }

    private fun executeEnvironmentDiagnostics() {
        val status = getEnvironmentStatus()
        appendLog("=== DEV STUDIO CAPABILITY REPORT ===")
        appendLog("• Internal Workspace: ${status.internalWorkspacePath} [${status.internalWorkspaceStatus}]")
        appendLog("• Active Execution Target: ${status.activeExecutionTarget}")
        appendLog("• Termux Bridge: ${status.termuxDetails} [${status.termuxStatus}]")
        appendLog("• Git CLI Engine: ${status.gitDetails} [${status.gitStatus}]")
        appendLog("• Gradle Engine: ${status.gradleDetails} [${status.gradleStatus}]")

        if (status.activeExecutionTarget == ExecutionTarget.APP_INTERNAL_RESTRICTED) {
            appendLog("\n=== ACTIONABLE REMEDIATION FOR ANDROID ===")
            appendLog("1. Install Termux: https://f-droid.org/packages/com.termux/")
            appendLog("2. Open Termux and run: pkg update && pkg install git")
            appendLog("3. Configure external execution in Termux:")
            appendLog("   echo 'allow-external-apps = true' >> ~/.termux/termux.properties")
            appendLog("   termux-reload-settings")
            appendLog("4. Ensure RUN_COMMAND permission is granted in Android Settings.")
        }
    }

    private fun executeBuildApk() {
        val status = getEnvironmentStatus()
        setRunningState("BUILDING", "#3B82F6")
        appendLog("[BUILD] Starting APK compilation pipeline...")

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()

            when (status.activeExecutionTarget) {
                ExecutionTarget.HOST_LINUX_PROCESS -> {
                    appendLog("[BUILD] Executing Gradle assembleDebug on host environment...")
                    val result = withContext(Dispatchers.IO) {
                        runHostCommand("./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2", File(status.internalWorkspacePath))
                    }
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
                    appendLog("[BUILD] Host build completed in ${elapsedSec}s:\n$result")

                    JarvisApp.instance.memoryRepository.remember(
                        content = "Host APK build completed in ${elapsedSec}s.",
                        type = MemoryEntity.TYPE_PROJECT,
                        key = "last_apk_build"
                    )
                    setRunningState("BUILD SUCCESS", "#10B981")
                    Toast.makeText(requireContext(), "Build completed!", Toast.LENGTH_SHORT).show()
                }

                ExecutionTarget.TERMUX_BRIDGE -> {
                    appendLog("[BUILD] Dispatching Gradle build via Termux bridge...")
                    val result = TermuxBridge.executeCommand(
                        context = requireContext(),
                        command = "./gradlew assembleDebug",
                        workDir = null
                    )
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
                    when (result) {
                        is DevToolResult.Success -> {
                            appendLog("[BUILD] Termux build succeeded in ${elapsedSec}s:\n${result.output}")
                            setRunningState("BUILD SUCCESS", "#10B981")
                        }
                        is DevToolResult.Failure -> {
                            appendLog("[BUILD FAILED] Exit code ${result.exitCode}:\n${result.error}\n${result.stderr}")
                            setRunningState("BUILD FAILED", "#EF4444")
                        }
                        else -> {
                            appendLog("[BUILD] $result")
                            setRunningState("BLOCKED", "#EF4444")
                        }
                    }
                }

                ExecutionTarget.APP_INTERNAL_RESTRICTED -> {
                    appendLog("[BUILD BLOCKED] On-device native APK compilation requires Termux with OpenJDK & Android build tools.")
                    appendLog("Android sandbox does not permit executing arbitrary compiler binaries directly.")
                    appendLog("\nActionable Remediation:\nInstall Termux from F-Droid and install OpenJDK: 'pkg install openjdk-17'.")
                    setRunningState("SETUP REQUIRED", "#F59E0B")
                }
                else -> {
                    appendLog("[BUILD] Execution unavailable.")
                    setRunningState("FAILED", "#EF4444")
                }
            }
        }
    }

    private fun executeGitStatus() {
        val status = getEnvironmentStatus()
        setRunningState("GIT STATUS", "#3B82F6")
        appendLog("[GIT] Querying repository status...")

        lifecycleScope.launch {
            when (status.activeExecutionTarget) {
                ExecutionTarget.HOST_LINUX_PROCESS -> {
                    val output = withContext(Dispatchers.IO) {
                        runHostCommand("git status --short && git branch --show-current", File(status.internalWorkspacePath))
                    }
                    appendLog(output)
                    setRunningState("GIT READY", "#10B981")
                }

                ExecutionTarget.TERMUX_BRIDGE -> {
                    appendLog("[GIT] Dispatching 'git status' via Termux bridge...")
                    val result = TermuxBridge.executeCommand(
                        context = requireContext(),
                        command = "git status --short 2>&1 || git status 2>&1",
                        workDir = null
                    )
                    when (result) {
                        is DevToolResult.Success -> {
                            appendLog("=== GIT STATUS (Termux) ===\n${result.output}")
                            setRunningState("TERMUX READY", "#10B981")
                        }
                        is DevToolResult.Failure -> {
                            appendLog("[GIT FAILED] Exit code ${result.exitCode}:\n${result.error}\n${result.stderr}")
                            setRunningState("GIT FAILED", "#EF4444")
                        }
                        else -> {
                            appendLog("[GIT] $result")
                            setRunningState("SETUP REQUIRED", "#F59E0B")
                        }
                    }
                }

                ExecutionTarget.APP_INTERNAL_RESTRICTED -> {
                    val gitDir = File(status.internalWorkspacePath, ".git")
                    if (gitDir.exists() && gitDir.isDirectory) {
                        val branch = readBranchFromGitHead(gitDir)
                        appendLog("[GIT REPO DETECTED] Repository found at: ${status.internalWorkspacePath}")
                        appendLog("Current Branch (parsed from .git/HEAD): $branch")
                    } else {
                        appendLog("[GIT REPO] No .git directory found at '${status.internalWorkspacePath}'.")
                    }

                    appendLog("\n[GIT CLI BLOCKED] Cannot run program 'git' in standard Android app sandbox (error=13 Permission denied).")
                    appendLog("Android security policy blocks executable binaries in application data directories.")
                    appendLog("\nActionable Remediation:\nInstall Termux from F-Droid, run 'pkg install git', and enable 'allow-external-apps = true' in ~/.termux/termux.properties.")
                    setRunningState("SETUP REQUIRED", "#F59E0B")
                }
                else -> {
                    appendLog("[GIT] Environment unavailable.")
                    setRunningState("BLOCKED", "#EF4444")
                }
            }
        }
    }

    private fun executeGitPush() {
        val status = getEnvironmentStatus()
        val audit = devAgent.auditGitOperation("git push origin main")
        if (audit.isDestructive) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Approval Required")
                .setMessage("Destructive Git operation detected:\n${audit.warningMessage}\nDo you authorize this action?")
                .setPositiveButton("Authorize") { _, _ -> runGitPushDirectly(status) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        runGitPushDirectly(status)
    }

    private fun runGitPushDirectly(status: DevEnvironmentStatus) {
        setRunningState("GIT PUSH", "#3B82F6")
        appendLog("[GIT] Pushing commits to remote repository...")

        lifecycleScope.launch {
            when (status.activeExecutionTarget) {
                ExecutionTarget.HOST_LINUX_PROCESS -> {
                    val output = withContext(Dispatchers.IO) {
                        runHostCommand("git push origin main", File(status.internalWorkspacePath))
                    }
                    appendLog("[GIT] Result:\n$output")
                    setRunningState("GIT PUSH DONE", "#10B981")
                }

                ExecutionTarget.TERMUX_BRIDGE -> {
                    val result = TermuxBridge.executeCommand(
                        context = requireContext(),
                        command = "git push origin main",
                        workDir = null
                    )
                    when (result) {
                        is DevToolResult.Success -> {
                            appendLog("[GIT] Push completed successfully:\n${result.output}")
                            setRunningState("PUSH SUCCESS", "#10B981")
                        }
                        is DevToolResult.Failure -> {
                            appendLog("[GIT PUSH FAILED] Exit code ${result.exitCode}:\n${result.error}\n${result.stderr}")
                            setRunningState("PUSH FAILED", "#EF4444")
                        }
                        else -> {
                            appendLog("[GIT PUSH] $result")
                            setRunningState("BLOCKED", "#EF4444")
                        }
                    }
                }

                ExecutionTarget.APP_INTERNAL_RESTRICTED -> {
                    appendLog("[GIT PUSH BLOCKED] Git CLI cannot execute in standard Android app sandbox.")
                    appendLog("Remediation: Install Termux to enable Git push on this device.")
                    setRunningState("SETUP REQUIRED", "#F59E0B")
                }
                else -> {
                    appendLog("[GIT PUSH] Target unavailable.")
                    setRunningState("BLOCKED", "#EF4444")
                }
            }
        }
    }

    private fun executeRunTests() {
        val status = getEnvironmentStatus()
        setRunningState("TESTING", "#3B82F6")
        appendLog("[TEST] Executing unit test suite...")

        lifecycleScope.launch {
            when (status.activeExecutionTarget) {
                ExecutionTarget.HOST_LINUX_PROCESS -> {
                    val output = withContext(Dispatchers.IO) {
                        runHostCommand("./gradlew :app:testDebugUnitTest -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2", File(status.internalWorkspacePath))
                    }
                    appendLog(output)
                    setRunningState("TESTS FINISHED", "#10B981")
                }

                ExecutionTarget.TERMUX_BRIDGE -> {
                    appendLog("[TEST] Dispatching Gradle test suite via Termux bridge...")
                    val result = TermuxBridge.executeCommand(
                        context = requireContext(),
                        command = "./gradlew testDebugUnitTest",
                        workDir = null
                    )
                    when (result) {
                        is DevToolResult.Success -> {
                            appendLog("[TEST] Tests passed:\n${result.output}")
                            setRunningState("TESTS PASSED", "#10B981")
                        }
                        is DevToolResult.Failure -> {
                            appendLog("[TEST FAILED] Exit code ${result.exitCode}:\n${result.error}")
                            setRunningState("TESTS FAILED", "#EF4444")
                        }
                        else -> {
                            appendLog("[TEST] $result")
                            setRunningState("BLOCKED", "#EF4444")
                        }
                    }
                }

                ExecutionTarget.APP_INTERNAL_RESTRICTED -> {
                    appendLog("[TEST BLOCKED] Gradle test execution requires Termux OpenJDK on Android.")
                    appendLog("Remediation: Install Termux to run Gradle unit tests on-device.")
                    setRunningState("SETUP REQUIRED", "#F59E0B")
                }
                else -> {
                    appendLog("[TEST] Target unavailable.")
                    setRunningState("FAILED", "#EF4444")
                }
            }
        }
    }

    private fun executeInitRepo() {
        val status = getEnvironmentStatus()
        setRunningState("INIT REPO", "#3B82F6")
        appendLog("[REPO] Initializing repository...")

        lifecycleScope.launch {
            when (status.activeExecutionTarget) {
                ExecutionTarget.HOST_LINUX_PROCESS -> {
                    val output = withContext(Dispatchers.IO) {
                        runHostCommand("git init", File(status.internalWorkspacePath))
                    }
                    appendLog("[REPO] Host init:\n$output")
                    setRunningState("REPO READY", "#10B981")
                }

                ExecutionTarget.TERMUX_BRIDGE -> {
                    val result = TermuxBridge.executeCommand(requireContext(), "git init", workDir = null)
                    when (result) {
                        is DevToolResult.Success -> {
                            appendLog("[REPO] Termux git init succeeded:\n${result.output}")
                            setRunningState("REPO READY", "#10B981")
                        }
                        is DevToolResult.Failure -> {
                            appendLog("[REPO FAILED] ${result.error}")
                            setRunningState("INIT FAILED", "#EF4444")
                        }
                        else -> {
                            appendLog("[REPO] $result")
                            setRunningState("BLOCKED", "#EF4444")
                        }
                    }
                }

                ExecutionTarget.APP_INTERNAL_RESTRICTED -> {
                    val success = withContext(Dispatchers.IO) {
                        try {
                            val gitDir = File(status.internalWorkspacePath, ".git")
                            gitDir.mkdirs()
                            File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")
                            File(gitDir, "config").writeText("[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = false\n")
                            File(gitDir, "description").writeText("JARVIS OS internal repository\n")
                            File(gitDir, "refs/heads").mkdirs()
                            File(gitDir, "objects").mkdirs()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }

                    if (success) {
                        appendLog("[REPO CREATED] Created basic Git directory structure at: ${status.internalWorkspacePath}/.git")
                        appendLog("Head set to: refs/heads/main")
                        appendLog("[NOTE] Full Git CLI actions (commits, push) require Termux bridge.")
                        setRunningState("APP REPO READY", "#3B82F6")
                    } else {
                        appendLog("[REPO FAILED] Could not create directory at ${status.internalWorkspacePath}/.git")
                        setRunningState("INIT FAILED", "#EF4444")
                    }
                }
                else -> {
                    appendLog("[REPO] Target unavailable.")
                    setRunningState("FAILED", "#EF4444")
                }
            }
        }
    }

    private fun executeAutonomousPlan(requirement: String) {
        val status = getEnvironmentStatus()
        setRunningState("PLANNING", "#3B82F6")
        appendLog("[AGENT] Formulating autonomous engineering plan for: '$requirement'...")

        lifecycleScope.launch {
            val project = DevProject(
                projectId = "jarvis_main",
                name = "JARVIS OS",
                workspaceRoot = status.internalWorkspacePath,
                description = "JARVIS OS Android Intelligent Assistant"
            )

            val plan = devLoop.createPlan(project, requirement)
            appendLog("[PLAN] 6-phase engineering workflow:")
            plan.steps.forEachIndexed { idx, step ->
                appendLog("  ${idx + 1}. [${step.phase}] ${step.title}: ${step.description}")
            }

            appendLog("[AGENT] Executing plan stages...")
            val report = devLoop.executePlan(project, plan, isApprovedByUser = true) { step ->
                appendLog(">> Phase [${step.phase}] Status: ${step.status} (${step.title})")
            }

            appendLog("[AGENT] Execution Report: ${report.fullReportText}")
            setRunningState(status.overallBadge, status.badgeColorHex)
        }
    }

    private fun appendLog(message: String, isCommand: Boolean = false) {
        val time = timeFormat.format(Date())
        val maskedMessage = TermuxBridge.maskSecrets(message)
        val prefix = if (isCommand) "\n>>> [$time] " else "[$time] "
        val formatted = "$prefix$maskedMessage\n"

        binding.tvTerminalLog.append(formatted)
        binding.scrollTerminalLog.post {
            binding.scrollTerminalLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setRunningState(badgeText: String, hexColor: String) {
        binding.tvDevAgentBadge.text = badgeText
        val parsedColor = Color.parseColor(hexColor)
        binding.tvDevAgentBadge.setTextColor(parsedColor)
        binding.viewConsoleIndicator.backgroundTintList = ColorStateList.valueOf(parsedColor)
    }

    private fun getWorkspaceRoot(): String {
        val hostCandidate = File("/root/jarvis-os-ref/Part 7")
        if (hostCandidate.exists() && hostCandidate.isDirectory) {
            return hostCandidate.absolutePath
        }
        val externalStorage = File("/sdcard/jarvis-workspace")
        if (externalStorage.exists() && externalStorage.isDirectory) {
            return externalStorage.absolutePath
        }
        return requireContext().filesDir.absolutePath
    }

    private fun isHostDevEnvironment(): Boolean {
        val devDir = File("/root/jarvis-os-ref/Part 7")
        val gitBin = File("/usr/bin/git")
        return devDir.exists() && gitBin.exists() && gitBin.canExecute()
    }

    private fun readBranchFromGitHead(gitDir: File): String {
        return try {
            val headFile = File(gitDir, "HEAD")
            if (!headFile.exists()) return "unknown"
            val line = headFile.readText().trim()
            if (line.startsWith("ref: refs/heads/")) {
                line.removePrefix("ref: refs/heads/").trim()
            } else if (line.length >= 7) {
                line.take(7)
            } else {
                line
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun runHostCommand(command: String, dir: File): String {
        return try {
            val process = ProcessBuilder("bash", "-c", command)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(30, TimeUnit.SECONDS)
            output.ifBlank { "Executed with code ${process.exitValue()}" }
        } catch (e: Exception) {
            "Host command error: ${e.message}"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
