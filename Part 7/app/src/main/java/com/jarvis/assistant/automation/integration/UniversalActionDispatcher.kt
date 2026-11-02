package com.jarvis.assistant.automation.integration

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

object UniversalActionDispatcher {
    private const val TAG = "UniversalActionDispatch"
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val CIRCUIT_BREAKER_RESET_MS = 30000L

    data class CircuitState(
        val failureCount: Int = 0,
        val lastFailureTime: Long = 0L,
        val isOpen: Boolean = false
    )

    private val circuitBreakers = ConcurrentHashMap<String, CircuitState>()

    /**
     * Executes a universal action safely with input validation, permission pre-checks,
     * circuit breaker protection, and bounded timeout.
     */
    suspend fun executeAction(
        identifier: String,
        parameters: Map<String, Any?>,
        context: Context? = null,
        isConfirmedByUser: Boolean = false,
        executor: suspend (params: Map<String, Any?>) -> String
    ): UniversalExecutionResult = withContext(Dispatchers.Default) {
        val contract = UniversalActionRegistry.getContract(identifier)
            ?: return@withContext UniversalExecutionResult.Failure(
                toolIdentifier = identifier,
                error = "Unrecognized universal tool: '$identifier'"
            )

        // 1. Check Circuit Breaker
        val now = System.currentTimeMillis()
        // Atomically transition an expired OPEN breaker into a clean HALF-OPEN trial state.
        val circuit = circuitBreakers.compute(identifier) { _, existing ->
            val current = existing ?: CircuitState()
            if (current.isOpen && now - current.lastFailureTime > CIRCUIT_BREAKER_RESET_MS) {
                Log.i(TAG, "Circuit breaker for '$identifier' entering half-open trial.")
                CircuitState()
            } else current
        } ?: CircuitState()
        if (circuit.isOpen) {
            return@withContext UniversalExecutionResult.ServiceUnavailable(
                toolIdentifier = identifier,
                reason = "Circuit breaker is OPEN. Integration '$identifier' is temporarily disabled due to consecutive failures.",
                guidance = "Please wait a moment before retrying, or check device connectivity."
            )
        }

        // 2. Pre-execution Validation
        val validationFailure = UniversalActionRegistry.validateInvocation(
            identifier = identifier,
            parameters = parameters,
            context = context,
            isConfirmedByUser = isConfirmedByUser
        )
        if (validationFailure != null) {
            return@withContext validationFailure
        }

        // 3. Timed Execution
        val startTime = System.currentTimeMillis()
        val timeoutMs = contract.defaultTimeoutMs

        val resultText = try {
            withTimeoutOrNull(timeoutMs) {
                executor(parameters)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action '$identifier': ${e.message}", e)
            recordFailure(identifier)
            return@withContext UniversalExecutionResult.Failure(
                toolIdentifier = identifier,
                error = e.message ?: "Unknown execution error",
                isRecoverable = true
            )
        }

        if (resultText == null) {
            // Execution timed out
            recordFailure(identifier)
            return@withContext UniversalExecutionResult.TimedOut(
                toolIdentifier = identifier,
                timeoutMs = timeoutMs
            )
        }

        // Success: atomically close the breaker and clear its failure history.
        circuitBreakers[identifier] = CircuitState()

        val duration = System.currentTimeMillis() - startTime
        UniversalExecutionResult.Success(
            toolIdentifier = identifier,
            output = resultText,
            evidence = "Executed with strategy: ${contract.verificationStrategy}",
            durationMs = duration
        )
    }

    private fun recordFailure(identifier: String) {
        val failureTime = System.currentTimeMillis()
        circuitBreakers.compute(identifier) { _, existing ->
            val previous = existing ?: CircuitState()
            val failures = previous.failureCount + 1
            val isOpen = failures >= MAX_CONSECUTIVE_FAILURES
            if (isOpen && !previous.isOpen) {
                Log.w(TAG, "Circuit breaker TRIPPED for '$identifier' after $failures failures.")
            }
            CircuitState(failureCount = failures, lastFailureTime = failureTime, isOpen = isOpen)
        }
    }

    fun resetCircuitBreaker(identifier: String) {
        circuitBreakers.computeIfPresent(identifier) { _, _ -> CircuitState() }
    }
}
