package com.jarvis.assistant.automation.dev

import java.io.File
import java.util.concurrent.TimeUnit

/** How dangerous a shell command is, from least to most. */
enum class ShellRisk { READ_ONLY, MODIFYING, SENSITIVE, BLOCKED }

/**
 * Result of classifying one command line.
 * [argv] is the shell-free argument vector, or null when the command cannot be run without a
 * shell (operators, substitution) or is blocked.
 */
data class ShellClassification(
    val risk: ShellRisk,
    val reason: String,
    val argv: List<String>? = null,
    val usesShellFeatures: Boolean = false
)

/**
 * Outcome of a host command. [refusedReason] is set when the policy refused to start it
 * (nothing was executed).
 */
data class ShellRunResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val durationMs: Long = 0L,
    val refusedReason: String? = null
) {
    val refused: Boolean get() = refusedReason != null
    val succeeded: Boolean get() = !refused && !timedOut && exitCode == 0
}

/**
 * Single policy and executor for host shell commands (audit finding F1).
 *
 * - Host commands are run as a plain argument vector, never through `bash -c`, so quoting
 *   tricks, `;`, `&&`, pipes, redirects and `$(...)` cannot be interpolated.
 * - Every command is classified. BLOCKED is never run. SENSITIVE (push, deletes, privileged or
 *   network tools, destructive git, shell operators) runs only with explicit authorization from
 *   the caller, which must come from a user action, never from model output.
 * - Approval is bound to the exact command text and the working directory it was shown for.
 * - Host execution exists only when a host workspace is configured explicitly
 *   (`-Djarvis.host.workspace` or `JARVIS_HOST_WORKSPACE`) on a non-Android JVM.
 */
object ShellPolicy {
    const val MAX_COMMAND_LENGTH = 2000
    private const val MAX_OUTPUT_CHARS = 200_000
    const val HOST_WORKSPACE_PROPERTY = "jarvis.host.workspace"
    const val HOST_WORKSPACE_ENV = "JARVIS_HOST_WORKSPACE"

    private val PRIVILEGE_PROGRAMS = setOf("sudo", "su", "doas", "pkexec")
    private val HARD_BLOCKED_PROGRAMS = setOf("fdisk", "parted", "shutdown", "reboot", "poweroff", "halt")
    private val SENSITIVE_PROGRAMS = setOf(
        "rm", "rmdir", "mv", "chmod", "chown", "chgrp", "kill", "pkill", "killall", "curl", "wget",
        "ssh", "scp", "sftp", "nc", "ncat", "gh", "pm", "am", "settings", "svc", "dd", "truncate", "shred"
    )
    private val READ_ONLY_PROGRAMS = setOf(
        "ls", "cat", "pwd", "head", "tail", "wc", "grep", "rg", "echo", "which", "uname", "date", "stat", "du", "df"
    )
    private val GIT_READ_ONLY = setOf(
        "status", "diff", "log", "show", "rev-parse", "ls-files", "describe", "blame", "shortlog"
    )
    private val GIT_SENSITIVE = setOf(
        "push", "reset", "clean", "rebase", "config", "filter-branch", "update-ref", "credential", "gc", "prune"
    )

    // ---- host availability ------------------------------------------------------------------

    fun isAndroidRuntime(): Boolean =
        System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true) ||
            System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)

    /** Explicitly configured host workspace directory, or null. Nothing is probed or assumed. */
    fun hostWorkspace(): String? {
        val configured = System.getProperty(HOST_WORKSPACE_PROPERTY) ?: System.getenv(HOST_WORKSPACE_ENV)
        return configured?.takeIf { it.isNotBlank() && File(it).isDirectory }
    }

    private fun gitBinary(): File? =
        listOf("/usr/bin/git", "/bin/git", "/usr/local/bin/git").map(::File).firstOrNull { it.canExecute() }

    fun isHostShellAvailable(): Boolean =
        !isAndroidRuntime() && hostWorkspace() != null && gitBinary() != null

    // ---- classification ---------------------------------------------------------------------

    internal data class Tokens(val words: List<String>, val usesShellFeatures: Boolean, val unbalancedQuote: Boolean)

    /** Quote-aware word splitting. No expansion is ever performed; shell features are only flagged. */
    internal fun tokenize(command: String): Tokens {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var started = false
        var inSingle = false
        var inDouble = false
        var features = false
        var i = 0
        while (i < command.length) {
            val ch = command[i]
            val next = command.getOrNull(i + 1)
            when {
                inSingle -> if (ch == '\'') inSingle = false else current.append(ch)
                inDouble -> when {
                    ch == '"' -> inDouble = false
                    ch == '\\' && next != null && (next == '"' || next == '\\' || next == '$' || next == '`') -> { current.append(next); i++ }
                    ch == '`' || (ch == '$' && (next == '(' || next == '{')) -> { features = true; current.append(ch) }
                    else -> current.append(ch)
                }
                ch == '\'' -> { inSingle = true; started = true }
                ch == '"' -> { inDouble = true; started = true }
                ch == '\\' -> { if (next != null) { current.append(next); i++ }; started = true }
                ch.isWhitespace() -> if (started) { words.add(current.toString()); current.clear(); started = false }
                ch == ';' || ch == '|' || ch == '&' || ch == '<' || ch == '>' || ch == '`' -> { features = true; current.append(ch); started = true }
                ch == '$' && (next == '(' || next == '{') -> { features = true; current.append(ch); started = true }
                else -> { current.append(ch); started = true }
            }
            i++
        }
        if (started) words.add(current.toString())
        return Tokens(words, features, inSingle || inDouble)
    }

    fun classify(command: String): ShellClassification {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ShellClassification(ShellRisk.BLOCKED, "Empty command.")
        if (trimmed.length > MAX_COMMAND_LENGTH) {
            return ShellClassification(ShellRisk.BLOCKED, "Command longer than $MAX_COMMAND_LENGTH characters.")
        }
        if (trimmed.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return ShellClassification(ShellRisk.BLOCKED, "Newlines and control characters are not allowed.")
        }
        val tokens = tokenize(trimmed)
        if (tokens.unbalancedQuote) return ShellClassification(ShellRisk.BLOCKED, "Unbalanced quote.")
        val words = tokens.words
        if (words.isEmpty()) return ShellClassification(ShellRisk.BLOCKED, "Empty command.")

        val program = words.first().substringAfterLast('/')
        when {
            program in PRIVILEGE_PROGRAMS -> return ShellClassification(ShellRisk.BLOCKED, "Privilege escalation ('$program') is not allowed.")
            program in HARD_BLOCKED_PROGRAMS || program.startsWith("mkfs") ->
                return ShellClassification(ShellRisk.BLOCKED, "'$program' can destroy the device or host and is not allowed.")
            program == "dd" && words.any { it.startsWith("of=/dev/") } ->
                return ShellClassification(ShellRisk.BLOCKED, "Writing to a block device is not allowed.")
            program == "rm" && isRecursiveRootDelete(words) ->
                return ShellClassification(ShellRisk.BLOCKED, "Recursive delete of a root or home path is not allowed.")
        }

        if (tokens.usesShellFeatures) {
            return ShellClassification(
                ShellRisk.SENSITIVE,
                "Uses shell operators or substitution (; & | < > backticks \$( ) \${ }); needs explicit approval and cannot run without a shell.",
                argv = null,
                usesShellFeatures = true
            )
        }

        val risk = when {
            program == "git" -> classifyGit(words)
            program in SENSITIVE_PROGRAMS -> ShellClassification(ShellRisk.SENSITIVE, "'$program' can delete data, change permissions or reach the network.")
            program == "find" && words.any { it in setOf("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint") } ->
                ShellClassification(ShellRisk.SENSITIVE, "'find' with an action can modify files.")
            program in READ_ONLY_PROGRAMS || program == "find" -> ShellClassification(ShellRisk.READ_ONLY, "Read-only command.")
            else -> ShellClassification(ShellRisk.MODIFYING, "Can modify files or run build tooling.")
        }
        return risk.copy(argv = words)
    }

    private fun isRecursiveRootDelete(words: List<String>): Boolean {
        val flags = words.drop(1).filter { it.startsWith("-") }.joinToString("")
        val recursive = flags.contains('r') || flags.contains('R') || words.contains("--recursive")
        if (!recursive) return false
        return words.drop(1).filter { !it.startsWith("-") }.any {
            it == "/" || it == "/*" || it == "~" || it == "~/" || it == "\$HOME" || it == ".." || it == "../.."
        }
    }

    private fun classifyGit(words: List<String>): ShellClassification {
        // Skip global options: -C <dir>, -c <k=v>, other leading flags.
        var i = 1
        while (i < words.size && words[i].startsWith("-")) {
            i += if (words[i] == "-C" || words[i] == "-c") 2 else 1
        }
        val sub = words.getOrNull(i) ?: return ShellClassification(ShellRisk.READ_ONLY, "git without a subcommand.")
        val rest = words.drop(i + 1)
        return when {
            sub == "push" -> ShellClassification(ShellRisk.SENSITIVE, "git push publishes commits to a remote.")
            sub in GIT_SENSITIVE -> ShellClassification(ShellRisk.SENSITIVE, "git $sub can rewrite history, discard work or change configuration.")
            sub == "branch" && rest.any { it in setOf("-d", "-D", "-m", "-M", "--delete", "--move", "--force") } ->
                ShellClassification(ShellRisk.SENSITIVE, "git branch delete/move/force changes or discards branches.")
            sub == "branch" && rest.all { it in setOf("-a", "-r", "-v", "-vv", "--list", "--show-current", "--all") } ->
                ShellClassification(ShellRisk.READ_ONLY, "Lists branches.")
            sub == "remote" && rest.all { it in setOf("-v", "show", "get-url") || !it.startsWith("-") && rest.firstOrNull() in setOf("show", "get-url") } ->
                ShellClassification(ShellRisk.READ_ONLY, "Lists remotes.")
            sub == "remote" -> ShellClassification(ShellRisk.SENSITIVE, "Changing remotes can redirect where code is published.")
            sub == "checkout" && rest.any { it == "--" || it == "-f" || it == "--force" } ->
                ShellClassification(ShellRisk.SENSITIVE, "Discards uncommitted changes.")
            sub == "restore" -> ShellClassification(ShellRisk.SENSITIVE, "Discards uncommitted changes.")
            sub == "stash" && rest.firstOrNull() in setOf("drop", "clear") ->
                ShellClassification(ShellRisk.SENSITIVE, "Permanently drops stashed work.")
            sub == "tag" && rest.any { it == "-d" || it == "--delete" } ->
                ShellClassification(ShellRisk.SENSITIVE, "Deletes a tag.")
            sub in GIT_READ_ONLY || sub == "--version" || sub == "version" -> ShellClassification(ShellRisk.READ_ONLY, "Read-only git command.")
            else -> ShellClassification(ShellRisk.MODIFYING, "git $sub changes the local repository.")
        }
    }

    // ---- approval binding -------------------------------------------------------------------

    /**
     * True only when the user approved this exact command text for this exact working directory.
     * A Boolean alone, a different command, or a different directory is never enough.
     */
    fun isApproved(
        isApprovedByUser: Boolean,
        params: Map<String, Any?>,
        command: String,
        workDir: String
    ): Boolean = isApprovedByUser &&
        (params["approvedCommand"] as? String) == command &&
        (params["approvedWorkDir"] as? String) == workDir

    // ---- execution --------------------------------------------------------------------------

    /**
     * Runs [command] on the host without a shell. Refuses (and runs nothing) when the command is
     * BLOCKED, needs a shell, or is SENSITIVE and [authorized] is false. [authorized] must come
     * from an explicit user action for this exact command, never from model output.
     */
    fun runHost(command: String, workDir: File, timeoutSec: Long, authorized: Boolean = false): ShellRunResult {
        val classification = classify(command)
        if (classification.risk == ShellRisk.BLOCKED) {
            return ShellRunResult(-2, "", refusedReason = "Blocked by shell policy: ${classification.reason}")
        }
        val argv = classification.argv
        if (argv == null) {
            return ShellRunResult(
                -2, "",
                refusedReason = "Host commands run without a shell, so operators and substitution are not supported. Run each command separately."
            )
        }
        if (classification.risk == ShellRisk.SENSITIVE && !authorized) {
            return ShellRunResult(-2, "", refusedReason = "Explicit authorization required: ${classification.reason}")
        }

        val started = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(argv)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { input ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (output.length < MAX_OUTPUT_CHARS) {
                                synchronized(output) { output.append(buffer, 0, minOf(n, MAX_OUTPUT_CHARS - output.length)) }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // stream closed when the process is destroyed
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(2_000)
            val text = synchronized(output) { output.toString() }
            val elapsed = System.currentTimeMillis() - started
            if (!finished) ShellRunResult(-1, text, timedOut = true, durationMs = elapsed)
            else ShellRunResult(process.exitValue(), text, durationMs = elapsed)
        } catch (e: Exception) {
            ShellRunResult(-3, "", refusedReason = "Host execution failed: ${e.javaClass.simpleName}")
        }
    }
}
