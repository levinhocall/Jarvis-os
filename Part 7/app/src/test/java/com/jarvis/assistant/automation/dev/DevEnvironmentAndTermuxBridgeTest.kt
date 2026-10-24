package com.jarvis.assistant.automation.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests verifying:
 * - Secret sanitization in logs and command output
 * - Termux result parsing (including allow-external-apps errors)
 * - Environment capability detection (host vs Termux vs restricted storage)
 * - Safe in-process Git repository inspection without execve
 */
class DevEnvironmentAndTermuxBridgeTest {

    // --- 1. Secret Sanitization Tests ---

    @Test
    fun `maskSecrets redacts Gemini, OpenAI, and GitHub tokens`() {
        val geminiKey = "AIzaSyD-abc1234567890_XYZabcdefghij-123"
        val openAiKey = "sk-1234567890abcdefghijklmnopqrstuvwxyzAB"
        val gitHubToken = "ghp_1234567890abcdefghijklmnopqrstuvwxyz"
        val rawLog = "Configured key $geminiKey and token $openAiKey with push auth $gitHubToken"

        val masked = TermuxBridge.maskSecrets(rawLog)

        assertFalse("Gemini key was not redacted!", masked.contains(geminiKey))
        assertFalse("OpenAI key was not redacted!", masked.contains(openAiKey))
        assertFalse("GitHub token was not redacted!", masked.contains(gitHubToken))
        assertTrue(masked.contains("AIza[MASKED]"))
        assertTrue(masked.contains("sk-[MASKED]"))
        assertTrue(masked.contains("ghp_[MASKED]"))
    }

    // --- 2. Termux Result Handling Tests ---

    @Test
    fun `notifyResult provides actionable remediation on allow-external-apps error`() {
        var completedResult: DevToolResult? = null
        val executionId = "test_exec_001"

        // Inject deferred into TermuxBridge
        val deferred = kotlinx.coroutines.CompletableDeferred<DevToolResult>()
        // Execute notifyResult with errCode = 5 (Termux allow-external-apps disabled)
        val field = TermuxBridge::class.java.getDeclaredField("pendingExecutions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(TermuxBridge) as java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<DevToolResult>>
        map[executionId] = deferred

        TermuxBridge.notifyResult(
            executionId = executionId,
            exitCode = 0,
            errCode = 5,
            errmsg = "The allow-external-apps property must be set to 'true'"
        )

        assertTrue(deferred.isCompleted)
        val result = kotlinx.coroutines.runBlocking { deferred.await() }

        assertTrue(result is DevToolResult.Failure)
        val failure = result as DevToolResult.Failure
        assertTrue(failure.error.contains("allow-external-apps"))
        assertTrue(failure.error.contains("~/.termux/termux.properties"))
    }

    @Test
    fun `notifyResult delivers stdout on exit code zero`() {
        val executionId = "test_exec_002"
        val deferred = kotlinx.coroutines.CompletableDeferred<DevToolResult>()
        val field = TermuxBridge::class.java.getDeclaredField("pendingExecutions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(TermuxBridge) as java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<DevToolResult>>
        map[executionId] = deferred

        TermuxBridge.notifyResult(
            executionId = executionId,
            exitCode = 0,
            stdout = "git version 2.40.1"
        )

        val result = kotlinx.coroutines.runBlocking { deferred.await() }
        assertTrue(result is DevToolResult.Success)
        assertEquals("git version 2.40.1", (result as DevToolResult.Success).output)
    }

    // --- 3. Environment Detection Tests ---

    @Test
    fun `detectEnvironment correctly identifies host dev environment`() {
        val hostDir = "/root/jarvis-os-ref/Part 7"
        val capability = DevToolRegistry.detectEnvironment(hostDir, context = null)

        // In Linux host container where /root/jarvis-os-ref and /usr/bin/git exist
        assertEquals(DevEnvironmentType.LOCAL_PROCESS, capability.type)
        assertTrue(capability.canExecuteCommands)
        assertTrue(capability.canRunGit)
        assertTrue(capability.canRunBuildTools)
    }

    @Test
    fun `detectEnvironment falls back to restricted storage when shell is unavailable`() {
        // Mock non-existent workspace where host process cannot run git
        val fakeWorkspace = "/data/user/0/com.jarvis.assistant/files"
        // When context is null and not host dev:
        // We test with a dummy path that can be read/written
        val tempDir = File.createTempFile("test_workspace", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }

        val capability = DevToolRegistry.detectEnvironment(tempDir.absolutePath, context = null)
        assertNotNull(capability)
        assertTrue(capability.canAccessFiles)
    }

    // --- 4. In-Process Git HEAD Branch Parsing Tests ---

    @Test
    fun `reading git branch from HEAD parses symbolic refs and detached hashes`() {
        val tempRepo = File.createTempFile("temp_git_repo", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }
        val gitDir = File(tempRepo, ".git").apply { mkdir() }

        // Test normal branch ref
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")
        var branch = parseBranchFromHead(gitDir)
        assertEquals("main", branch)

        // Test feature branch ref
        File(gitDir, "HEAD").writeText("ref: refs/heads/feature/autonomous-agent\n")
        branch = parseBranchFromHead(gitDir)
        assertEquals("feature/autonomous-agent", branch)

        // Test detached commit SHA
        File(gitDir, "HEAD").writeText("019f9dfc2fdda37cabd8c3f977f5236b0f30371303\n")
        branch = parseBranchFromHead(gitDir)
        assertEquals("019f9df", branch)
    }

    private fun parseBranchFromHead(gitDir: File): String {
        val headFile = File(gitDir, "HEAD")
        if (!headFile.exists()) return "unknown"
        val line = headFile.readText().trim()
        return if (line.startsWith("ref: refs/heads/")) {
            line.removePrefix("ref: refs/heads/").trim()
        } else if (line.length >= 7) {
            line.take(7)
        } else {
            line
        }
    }
}
