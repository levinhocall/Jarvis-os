package com.jarvis.assistant.automation.macro

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class WaitScreen(val packageName: String, val labels: List<String>, val readable: Boolean)

/** An unreadable/wrong-app screen can never establish that text disappeared. */
object ScreenTextWaiter {
    suspend fun await(packageName: String, text: String, present: Boolean, timeoutMs: Long,
        sample: suspend () -> WaitScreen): Boolean {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
        require(text.isNotBlank() && text.length <= 160 && timeoutMs in 200..10_000)
        return withTimeoutOrNull(timeoutMs) {
            var stable = 0
            while (stable < 2) {
                val screen = sample()
                val found = screen.labels.any { it.contains(text, ignoreCase = true) }
                val verified = screen.readable && screen.labels.isNotEmpty() &&
                    screen.packageName == packageName && found == present
                stable = if (verified) stable + 1 else 0
                if (stable < 2) delay(200)
            }
            true
        } ?: false
    }
}
