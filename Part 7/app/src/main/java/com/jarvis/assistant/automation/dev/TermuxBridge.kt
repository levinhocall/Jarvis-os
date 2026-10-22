package com.jarvis.assistant.automation.dev

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class TermuxCommandResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return
        val executionId = intent.getStringExtra(TermuxBridge.EXTRA_EXECUTION_ID) ?: return
        val exitCode = intent.getIntExtra("exitCode", 0)
        val stdout = intent.getStringExtra("stdout").orEmpty()
        val stderr = intent.getStringExtra("stderr").orEmpty()
        TermuxBridge.notifyResult(executionId, exitCode, stdout, stderr)
    }
}

object TermuxBridge {

    private const val TAG = "TermuxBridge"
    const val ACTION_RESULT = "com.jarvis.assistant.TERMUX_COMMAND_RESULT"
    const val EXTRA_EXECUTION_ID = "jarvis_execution_id"

    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

    private const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_RUN_COMMAND_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    private val pendingExecutions = ConcurrentHashMap<String, CompletableDeferred<DevToolResult>>()

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

    fun notifyResult(executionId: String, exitCode: Int, stdout: String, stderr: String) {
        val deferred = pendingExecutions.remove(executionId) ?: return
        val combinedOutput = buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(stderr)
            }
        }

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
                    error = "Termux command failed with exit code $exitCode",
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr
                )
            )
        }
    }

    suspend fun executeCommand(
        context: Context,
        command: String,
        workDir: String,
        timeoutSeconds: Long = 120L
    ): DevToolResult {
        if (!isTermuxInstalled(context)) {
            return DevToolResult.EnvironmentUnsupported(
                toolName = "termux_execute",
                reason = "Termux app is not installed on this device. Install Termux from F-Droid to enable native compilation and shell builds on phone.",
                missingCapability = "com.termux"
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

        val runIntent = Intent(TERMUX_RUN_COMMAND_ACTION).apply {
            component = ComponentName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            putExtra(EXTRA_RUN_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_RUN_COMMAND_WORKDIR, workDir)
            putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
            putExtra(EXTRA_RUN_COMMAND_SESSION_ACTION, "0")
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(runIntent)
            } else {
                context.startService(runIntent)
            }
            Log.i(TAG, "Dispatched command '$command' to Termux service (id=$executionId)")
        } catch (e: Exception) {
            pendingExecutions.remove(executionId)
            return DevToolResult.Failure(
                toolName = "termux_execute",
                error = "Failed dispatching command to Termux service: ${e.message}"
            )
        }

        val result = withTimeoutOrNull(timeoutSeconds * 1000L) {
            deferred.await()
        }

        if (result == null) {
            pendingExecutions.remove(executionId)
            return DevToolResult.Failure(
                toolName = "termux_execute",
                error = "Termux execution timed out after $timeoutSeconds seconds",
                exitCode = -1
            )
        }

        return result
    }
}
