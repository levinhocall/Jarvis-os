package com.jarvis.assistant.automation.dev

/** App-level dispatch policy, not an OS sandbox. Models cannot supply the review argument. */
object ShellPolicy {
    enum class Decision { ALLOW, REQUIRE_REVIEW, REJECT }
    private val diagnostics=setOf("pwd", "whoami", "uname -a", "git --version", "git status --short", "git diff --stat", "date")
    fun evaluate(command: String, reviewedCommand: String? = null): Decision {
        if(command.isBlank() || command.length > 16_384 || command.any { it == '\u0000' || it == '\r' }) return Decision.REJECT
        if(command.trim() in diagnostics && '\n' !in command) return Decision.ALLOW
        // Review must be bound to these exact bytes by a trusted UI or existing approval gate.
        return if(reviewedCommand == command) Decision.ALLOW else Decision.REQUIRE_REVIEW
    }
    fun quoteArgument(value: String): String {
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' })
        return "'"+value.replace("'", "'\\''")+"'"
    }
}

/** Official Termux Bundle uses err=-1 for no internal error, not err=0. */
object TermuxResultDecoder {
    data class Result(val exit: Int, val error: Int, val message: String)
    fun decode(exit: Int?, error: Int?, message: String, legacyFlat: Boolean = false): Result {
        if(exit == null && (error == null || error == -1 || legacyFlat && error == 0)) {
            return Result(-1,-100,"Termux returned no verifiable exit status; execution outcome is unknown.")
        }
        val successMarker=if(legacyFlat) 0 else -1
        if(error != null && error != successMarker) {
            return Result(exit ?: -1,if(error == 0) -101 else error,message.take(4096).ifBlank { "Termux reported an internal execution error." })
        }
        if(message.isNotBlank()) return Result(exit ?: -1,-102,message.take(4096))
        return Result(exit ?: -1,0,"")
    }
}
