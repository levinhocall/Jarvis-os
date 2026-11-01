package com.jarvis.assistant.core.reliability

import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object ReliabilityCoordinator {
    private const val TAG = "ReliabilityCoordinator"
    const val MAX_RETRIES = 3
    const val LOOP_BREAKER_WINDOW_MS = 60000L
    const val MAX_FAILURES_BEFORE_BREAKER = 3

    private val componentStates = ConcurrentHashMap<String, SystemHealthState>()
    private val failureTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val diagnosticLog = CopyOnWriteArrayList<DiagnosticEvent>()

    var overallState: SystemHealthState = SystemHealthState.HEALTHY
        private set

    fun sanitize(text: String): String {
        var clean = text
        clean = clean.replace(Regex("""(AIza[0-9A-Za-z_-]{30,})"""), "[REDACTED_GEMINI_KEY]")
        clean = clean.replace(Regex("""(sk-or-v1-[0-9A-Za-z_-]{20,})"""), "[REDACTED_PROVIDER_KEY]")
        clean = clean.replace(Regex("""(sk-[0-9A-Za-z_-]{32,})"""), "[REDACTED_PROVIDER_KEY]")
        clean = clean.replace(Regex("""(gsk_[0-9A-Za-z_-]{20,})"""), "[REDACTED_PROVIDER_KEY]")
        clean = clean.replace(Regex("""(github_pat_[0-9A-Za-z_]{20,})"""), "[REDACTED_GITHUB_TOKEN]")
        clean = clean.replace(Regex("""(gh[opusr]_[0-9A-Za-z]{20,})"""), "[REDACTED_GITHUB_TOKEN]")
        clean = clean.replace(Regex("""(glpat-[0-9A-Za-z_-]{20,})"""), "[REDACTED_GITLAB_TOKEN]")
        clean = clean.replace(Regex("""(xox[baprs]-[0-9A-Za-z-]{20,})"""), "[REDACTED_SLACK_TOKEN]")
        clean = clean.replace(Regex("""(Bearer\s+)[A-Za-z0-9._~+/-]+=*""", RegexOption.IGNORE_CASE), "$1[REDACTED]")
        clean = clean.replace(Regex("""[?&](?:key|api_key|access_token|refresh_token|token)=[^&\s]+""", RegexOption.IGNORE_CASE)) { match ->
            match.value.substringBefore("=") + "=[REDACTED]"
        }
        clean = clean.replace(
            Regex("""((?:api[_-]?key|apiKey|access[_-]?token|refresh[_-]?token|token|key|secret|password)\s*[:=]\s*['"]?)[^'"\s,;&]+""", RegexOption.IGNORE_CASE),
            "$1[REDACTED]"
        )
        return clean
    }
    /**
     * Reports a diagnostic event and updates component/system health.
     */
    fun reportDiagnostic(event: DiagnosticEvent) {
        // Never retain raw credentials in the diagnostic store; consumers can inspect rawMessage safely.
        val safeEvent = event.copy(rawMessage = sanitize(event.rawMessage))
        diagnosticLog.add(safeEvent)
        if (diagnosticLog.size > 100) {
            diagnosticLog.removeAt(0)
        }
        Log.w(TAG, "[${safeEvent.category}] (${safeEvent.component}): ${safeEvent.sanitizedMessage}")

        // Non-recoverable failures are recorded and degrade the component, but do not trip
        // the automatic retry circuit breaker.
        if (!safeEvent.isRecoverable) {
            componentStates[safeEvent.component] = SystemHealthState.DEGRADED
            recomputeOverallState()
            return
        }

        val timestamps = failureTimestamps.getOrPut(safeEvent.component) { mutableListOf() }
        val now = System.currentTimeMillis()
        synchronized(timestamps) {
            timestamps.add(now)
            timestamps.removeAll { now - it > LOOP_BREAKER_WINDOW_MS }

            if (timestamps.size >= MAX_FAILURES_BEFORE_BREAKER) {
                componentStates[safeEvent.component] = SystemHealthState.UNAVAILABLE
                recomputeOverallState()
                Log.e(TAG, "LOOP BREAKER TRIPPED for component '${safeEvent.component}'! Switched to UNAVAILABLE.")
            } else {
                componentStates[safeEvent.component] = SystemHealthState.DEGRADED
                recomputeOverallState()
            }
        }
    }

    /**
     * Executes an operation with self-healing, bounded retries, and non-idempotent action guards.
     */
    suspend fun <T> executeWithSelfHealing(
        component: String,
        actionName: String,
        isIdempotent: Boolean,
        action: suspend (attempt: Int) -> T
    ): Pair<T?, RecoveryResult> {
        // 1. Check if component is blocked by Loop Breaker
        if (getComponentState(component) == SystemHealthState.UNAVAILABLE) {
            val msg = "Component '$component' is in UNAVAILABLE state due to repeated failures."
            return Pair(null, RecoveryResult.Failed(component, msg, loopBreakerTriggered = true))
        }

        var attempts = 0
        var lastException: Exception? = null

        while (attempts < MAX_RETRIES) {
            attempts++
            try {
                if (attempts > 1) {
                    componentStates[component] = SystemHealthState.RECOVERING
                    recomputeOverallState()
                }

                val result = action(attempts)

                // Operation succeeded! Mark healthy
                componentStates[component] = SystemHealthState.HEALTHY
                recomputeOverallState()

                val recoveryOutcome = if (attempts > 1) {
                    RecoveryResult.Recovered(component, attempts, "Succeeded after $attempts attempts.")
                } else {
                    RecoveryResult.Recovered(component, 1, "Success on primary attempt.")
                }
                return Pair(result, recoveryOutcome)
            } catch (e: Exception) {
                lastException = e
                reportDiagnostic(
                    DiagnosticEvent(
                        category = ErrorCategory.SERVICE_DISCONNECTED,
                        component = component,
                        rawMessage = "Attempt $attempts for '$actionName' failed: ${e.message}",
                        isRecoverable = isIdempotent && attempts < MAX_RETRIES
                    )
                )

                // Non-idempotent action guard: NEVER blindly retry non-idempotent actions!
                if (!isIdempotent) {
                    val msg = "Action '$actionName' is non-idempotent. Aborting retry to prevent duplicate side effects."
                    Log.e(TAG, msg)
                    return Pair(null, RecoveryResult.CannotReplayNonIdempotentAction(actionName, msg))
                }

                if (attempts < MAX_RETRIES) {
                    delay(50L * attempts) // Bounded backoff
                }
            }
        }

        // All retries exhausted
        val finalReason = lastException?.message ?: "Unknown failure after $MAX_RETRIES attempts"
        val failedResult = RecoveryResult.Failed(component, finalReason)
        return Pair(null, failedResult)
    }

    fun getComponentState(component: String): SystemHealthState =
        componentStates.getOrDefault(component, SystemHealthState.HEALTHY)

    fun resetComponent(component: String) {
        componentStates[component] = SystemHealthState.HEALTHY
        failureTimestamps[component]?.clear()
        recomputeOverallState()
    }

    fun resetAllForTest() {
        componentStates.clear()
        failureTimestamps.clear()
        diagnosticLog.clear()
        overallState = SystemHealthState.HEALTHY
    }

    fun getRecentDiagnostics(): List<DiagnosticEvent> = diagnosticLog.toList()

    private fun recomputeOverallState() {
        val states = componentStates.values
        overallState = when {
            states.any { it == SystemHealthState.UNAVAILABLE } -> SystemHealthState.DEGRADED
            states.any { it == SystemHealthState.RECOVERING } -> SystemHealthState.RECOVERING
            states.any { it == SystemHealthState.DEGRADED } -> SystemHealthState.DEGRADED
            else -> SystemHealthState.HEALTHY
        }
    }
}
