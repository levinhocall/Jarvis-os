package com.jarvis.assistant.automation.dev

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Covers ShellPolicy (audit finding F1): classification, no-shell execution, approval binding. */
class ShellPolicyTest {

    private fun risk(command: String) = ShellPolicy.classify(command).risk

    private fun tempDir(): File =
        File.createTempFile("shellpolicy", "").apply { delete(); mkdirs(); deleteOnExit() }

    // ---- classification ----

    @Test
    fun readOnlyCommandsAreClassifiedReadOnly() {
        listOf("ls -la", "pwd", "git status --short", "git diff --stat", "git log --oneline", "git branch --show-current", "git --version", "git remote -v")
            .forEach { assertEquals(it, ShellRisk.READ_ONLY, risk(it)) }
    }

    @Test
    fun publishingDeletingAndPrivilegedCommandsAreSensitive() {
        listOf(
            "git push origin main", "git push", "git reset --hard HEAD~1", "git clean -fd", "git branch -D feature",
            "git remote add evil https://example.com/x.git", "git checkout -- app", "git rebase main",
            "rm notes.txt", "mv a b", "curl https://example.com", "chmod 777 file", "find . -delete"
        ).forEach { assertEquals(it, ShellRisk.SENSITIVE, risk(it)) }
    }

    @Test
    fun ordinaryBuildAndGitWorkIsModifying() {
        listOf("git add -A", "git commit -m \"message\"", "./gradlew :app:assembleDebug", "mkdir out", "git init")
            .forEach { assertEquals(it, ShellRisk.MODIFYING, risk(it)) }
    }

    @Test
    fun dangerousCommandsAreBlocked() {
        listOf("sudo ls", "su -c id", "rm -rf /", "rm -rf ~", "rm -fr /*", "mkfs.ext4 /dev/sda", "dd if=x of=/dev/sda", "reboot", "")
            .forEach { assertEquals("'$it'", ShellRisk.BLOCKED, risk(it)) }
        assertEquals(ShellRisk.BLOCKED, risk("echo hi\nrm file"))
        assertEquals(ShellRisk.BLOCKED, risk("echo 'unterminated"))
        assertEquals(ShellRisk.BLOCKED, risk("echo " + "a".repeat(ShellPolicy.MAX_COMMAND_LENGTH)))
    }

    @Test
    fun shellOperatorsAndSubstitutionAreSensitiveAndNeverRunnable() {
        listOf("echo a; rm b", "echo a && echo b", "cat x | grep y", "echo a > file", "echo \$(id)", "echo `id`", "echo \"\$(id)\"", "echo \${HOME}")
            .forEach {
                val c = ShellPolicy.classify(it)
                assertEquals(it, ShellRisk.SENSITIVE, c.risk)
                assertTrue(it, c.usesShellFeatures)
                assertNull(it, c.argv)
            }
    }

    @Test
    fun quotedOperatorCharactersAreLiteralText() {
        val c = ShellPolicy.classify("echo 'a;b && c | d'")
        assertFalse(c.usesShellFeatures)
        assertEquals(listOf("echo", "a;b && c | d"), c.argv)
    }

    @Test
    fun tokenizerKeepsQuotedWordsTogether() {
        assertEquals(
            listOf("git", "commit", "-m", "hello world"),
            ShellPolicy.tokenize("git commit -m \"hello world\"").words
        )
        assertEquals(listOf("echo", "it's"), ShellPolicy.tokenize("echo it\\'s").words)
    }

    // ---- approval binding ----

    @Test
    fun approvalIsBoundToExactCommandAndWorkingDirectory() {
        val params = mapOf("approvedCommand" to "git status", "approvedWorkDir" to "/work")
        assertTrue(ShellPolicy.isApproved(true, params, "git status", "/work"))
        assertFalse("not approved by user", ShellPolicy.isApproved(false, params, "git status", "/work"))
        assertFalse("changed command", ShellPolicy.isApproved(true, params, "git status ", "/work"))
        assertFalse("changed command", ShellPolicy.isApproved(true, params, "git push", "/work"))
        assertFalse("changed directory", ShellPolicy.isApproved(true, params, "git status", "/other"))
        assertFalse("missing context", ShellPolicy.isApproved(true, mapOf("approvedCommand" to "git status"), "git status", "/work"))
        assertFalse("missing command", ShellPolicy.isApproved(true, mapOf("approvedWorkDir" to "/work"), "git status", "/work"))
    }

    // ---- host availability ----

    @Test
    fun hostWorkspaceIsOptInAndNeverAssumedFromAHardcodedPath() {
        val previous = System.getProperty(ShellPolicy.HOST_WORKSPACE_PROPERTY)
        try {
            System.clearProperty(ShellPolicy.HOST_WORKSPACE_PROPERTY)
            assumeTrue("host env var is configured", System.getenv(ShellPolicy.HOST_WORKSPACE_ENV).isNullOrBlank())
            assertNull(ShellPolicy.hostWorkspace())
            assertFalse(ShellPolicy.isHostShellAvailable())

            val dir = tempDir()
            System.setProperty(ShellPolicy.HOST_WORKSPACE_PROPERTY, dir.absolutePath)
            assertEquals(dir.absolutePath, ShellPolicy.hostWorkspace())

            System.setProperty(ShellPolicy.HOST_WORKSPACE_PROPERTY, File(dir, "missing").absolutePath)
            assertNull("a non-existent directory is not a host workspace", ShellPolicy.hostWorkspace())
        } finally {
            if (previous == null) System.clearProperty(ShellPolicy.HOST_WORKSPACE_PROPERTY)
            else System.setProperty(ShellPolicy.HOST_WORKSPACE_PROPERTY, previous)
        }
    }

    // ---- execution ----

    @Test
    fun runHostExecutesWithoutAShellAndRefusesOperators() {
        val echo = listOf("/bin/echo", "/usr/bin/echo").firstOrNull { File(it).canExecute() }
        assumeTrue("echo binary unavailable", echo != null)
        val dir = tempDir()

        val ok = ShellPolicy.runHost("echo hello", dir, 10)
        assertTrue(ok.succeeded)
        assertEquals("hello", ok.output.trim())

        val marker = File(dir, "injected")
        val refused = ShellPolicy.runHost("echo a; touch '${marker.absolutePath}'", dir, 10, authorized = true)
        assertTrue("operators must be refused even when authorized", refused.refused)
        assertFalse("nothing may have executed", marker.exists())

        val literal = ShellPolicy.runHost("echo '\$(touch ${marker.absolutePath})'", dir, 10)
        // single-quoted: no expansion because there is no shell, so the marker must not exist
        assertFalse(marker.exists())
        assertNotNull(literal)
    }

    @Test
    fun runHostRefusesSensitiveCommandsWithoutAuthorization() {
        val rm = listOf("/bin/rm", "/usr/bin/rm").firstOrNull { File(it).canExecute() }
        assumeTrue("rm binary unavailable", rm != null)
        val dir = tempDir()
        val victim = File(dir, "victim.txt").apply { writeText("keep me") }

        val refused = ShellPolicy.runHost("rm victim.txt", dir, 10, authorized = false)
        assertTrue(refused.refused)
        assertTrue("file must survive an unauthorized sensitive command", victim.exists())

        val allowed = ShellPolicy.runHost("rm victim.txt", dir, 10, authorized = true)
        assertTrue(allowed.succeeded)
        assertFalse(victim.exists())
    }

    @Test
    fun runHostNeverRunsBlockedCommandsEvenWhenAuthorized() {
        val result = ShellPolicy.runHost("sudo ls", tempDir(), 5, authorized = true)
        assertTrue(result.refused)
        assertEquals(-2, result.exitCode)
    }

    @Test
    fun runHostEnforcesTimeout() {
        val sleep = listOf("/bin/sleep", "/usr/bin/sleep").firstOrNull { File(it).canExecute() }
        assumeTrue("sleep binary unavailable", sleep != null)
        val started = System.currentTimeMillis()
        val result = ShellPolicy.runHost("sleep 30", tempDir(), 1)
        assertTrue(result.timedOut)
        assertFalse(result.succeeded)
        assertTrue("timeout must not wait for the process", System.currentTimeMillis() - started < 15_000)
    }
}
