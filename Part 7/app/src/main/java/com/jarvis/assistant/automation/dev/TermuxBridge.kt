package com.jarvis.assistant.automation.dev

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class TermuxCommandResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return
        val executionId = intent.getStringExtra(TermuxBridge.EXTRA_EXECUTION_ID) ?: return
        val exitCode = intent.getIntExtra("exitCode", 0)
        val errCode = intent.getIntExtra("errCode", 0)
        val errmsg = intent.getStringExtra("errmsg").orEmpty()
        val stdout = intent.getStringExtra("stdout").orEmpty()
        val stderr = intent.getStringExtra("stderr").orEmpty()
        TermuxBridge.notifyResult(
            executionId = executionId,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            errCode = errCode,
            errmsg = errmsg
        )
    }
}

object TermuxBridge {

    private const val TAG = "TermuxBridge"
    const val ACTION_RESULT = "com.jarvis.assistant.TERMUX_COMMAND_RESULT"
    const val EXTRA_EXECUTION_ID = "jarvis_execution_id"

    const val TERMUX_PACKAGE = "com.termux"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

    private const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_RUN_COMMAND_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    private val pendingExecutions = ConcurrentHashMap<String, CompletableDeferred<DevToolResult>>()

    /**
     * Checks if Termux application package is installed on the Android device.
     */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if com.termux.permission.RUN_COMMAND has been granted to JARVIS OS.
     */
    fun isRunCommandPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION_RUN_COMMAND
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Inspects the complete Termux integration status on this device.
     */
    fun inspectTermuxIntegration(context: Context): Pair<CapabilityState, String> {
        if (!isTermuxInstalled(context)) {
            return Pair(
                CapabilityState.NOT_CONFIGURED,
                "Termux app is not installed. Install Termux from F-Droid to enable Git and terminal execution."
            )
        }
        if (!isRunCommandPermissionGranted(context)) {
            return Pair(
                CapabilityState.BLOCKED,
                "Permission 'com.termux.permission.RUN_COMMAND' is not granted. Enable in Android App Settings."
            )
        }
        return Pair(
            CapabilityState.READY,
            "Termux bridge ready. Inter-app command execution permitted."
        )
    }

    /**
     * Sanitizes known credential patterns (API keys, tokens) to prevent leaking secrets into logs.
     */
    fun maskSecrets(input: String): String {
        return input
            .replace(Regex("""(AIza[0-9A-Za-z-_]{35})"""), "AIza[MASKED]")
            .replace(Regex("""(sk-[0-9A-Za-z-_]{32,})"""), "sk-[MASKED]")
            .replace(Regex("""(ghp_[0-9A-Za-z]{36})"""), "ghp_[MASKED]")
            .replace(Regex("""(password\s*=\s*['"]?)[^'"\s]+""", RegexOption.IGNORE_CASE), "$1[MASKED]")
    }

    /**
     * Called by TermuxCommandResultReceiver when Termux completes command execution.
     */
    fun notifyResult(
        executionId: String,
        exitCode: Int,
        stdout: String = "",
        stderr: String = "",
        errCode: Int = 0,
        errmsg: String = ""
    ) {
        val deferred = pendingExecutions.remove(executionId) ?: return

        // 1. Check for Termux IPC level failure (e.g. allow-external-apps=false)
        if (errCode != 0 || errmsg.isNotBlank()) {
            val failureDetail = buildString {
                append("Termux execution failed (errCode=$errCode)")
                if (errmsg.isNotBlank()) append(": $errmsg")
                if (errCode == 5 || errmsg.contains("allow-external-apps", ignoreCase = true)) {
                    append("\nActionable remediation: Add 'allow-external-apps = true' to ~/.termux/termux.properties and run 'termux-reload-settings'.")
                }
            }
            deferred.complete(
                DevToolResult.Failure(
                    toolName = "termux_execute",
                    error = maskSecrets(failureDetail),
                    exitCode = errCode,
                    stdout = maskSecrets(stdout),
                    stderr = maskSecrets(stderr)
                )
            )
            return
        }

        // 2. Normal execution result from shell
        val combinedOutput = maskSecrets(buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(stderr)
            }
        })

        if (exitCode == 0) {
            deferred.complete(
                DevToolResult.Success(
                    toolName = "termux_execute",
                    output = combinedOutput.ifBlank { "Command executed successfully in Termux with code 0." }
                )
            )
        } else {
            deferred.complete(
                DevToolResult.Failure(
                    toolName = "termux_execute",
                    error = "Termux command exited with non-zero code $exitCode",
                    exitCode = exitCode,
                    stdout = maskSecrets(stdout),
                    stderr = maskSecrets(stderr)
                )
            )
        }
    }

    /**
     * Dispatches an audited shell command to Termux via RUN_COMMAND service.
     */
    suspend fun executeCommand(
        context: Context,
        command: String,
        workDir: String? = null,
        timeoutSeconds: Long = 60L
    ): DevToolResult {
        if (!isTermuxInstalled(context)) {
            return DevToolResult.EnvironmentUnsupported(
                toolName = "termux_execute",
                reason = "Termux app is not installed on this device. Install Termux from F-Droid to enable Git and terminal execution on phone.",
                missingCapability = "com.termux"
            )
        }

        if (!isRunCommandPermissionGranted(context)) {
            return DevToolResult.EnvironmentUnsupported(
                toolName = "termux_execute",
                reason = "Permission 'com.termux.permission.RUN_COMMAND' is not granted. Enable this permission in Settings > Apps > JARVIS OS > Permissions.",
                missingCapability = PERMISSION_RUN_COMMAND
            )
        }

        val executionId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<DevToolResult>()
        pendingExecutions[executionId] = deferred

        val resultIntent = Intent(context, TermuxCommandResultReceiver::class.java).apply {
            action = ACTION_RESULT
            putExtra(EXTRA_EXECUTION_ID, executionId)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            executionId.hashCode(),
            resultIntent,
            flags
        )

        // Note: Android DAC forbids Termux from cd-ing into /data/user/0/com.jarvis.assistant/files.
        // If workDir points to app-internal private storage, omit it so Termux runs in its own $HOME.
        val effectiveWorkDir = if (workDir != null && (workDir.startsWith("/data/user/") || workDir.startsWith("/data/data/com.jarvis.assistant"))) {
            null
        } else {
            workDir
        }

        val runIntent = Intent(TERMUX_RUN_COMMAND_ACTION).apply {
            component = ComponentName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            putExtra(EXTRA_RUN_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
            if (effectiveWorkDir != null) {
                putExtra(EXTRA_RUN_COMMAND_WORKDIR, effectiveWorkDir)
            }
            putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
            putExtra(EXTRA_RUN_COMMAND_SESSION_ACTION, "0")
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            // Note: Termux RunCommandService is a standard started service.
            // When JARVIS Dev Studio is in the foreground, startService is fully permitted.
            context.startService(runIntent)
            Log.i(TAG, "Dispatched command '${maskSecrets(command)}' to Termux service (id=$executionId)")
        } catch (e: Exception) {
            pendingExecutions.remove(executionId)
            return DevToolResult.Failure(
                toolName = "termux_execute",
                error = "Failed dispatching command to Termux service."
            )
        }

        val result = withTimeoutOrNull(timeoutSeconds * 1000L) {
            deferred.await()
        }

        if (result == null) {
            pendingExecutions.remove(executionId)
            return DevToolResult.Failure(
                toolName = "termux_execute",
                error = "Termux execution timed out after $timeoutSeconds seconds. Verify Termux background execution is not restricted by battery saver.",
                exitCode = -1
            )
        }

        return result
    }
}
