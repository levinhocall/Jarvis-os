package com.jarvis.assistant.data.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** One authoritative state for the existing local database; never a simulated readiness signal. */
sealed interface DatabaseState<out T : Any> {
    data object Loading : DatabaseState<Nothing>
    data class Ready<T : Any>(val database: T) : DatabaseState<T>
    data class Failed(val cause: Throwable) : DatabaseState<Nothing>
}

/** A caller deadline, not native initialization failure or user cancellation. */
class DatabaseReadinessTimeoutException(val timeoutMs: Long) : IllegalStateException(
    "Local database readiness wait timed out after $timeoutMs ms. This wait did not cancel initialization or delete stored data. Retry shortly."
)

/** Single-flight initialization owned by the database, never by an individual UI waiter. */
internal class DatabaseReadiness<T : Any>(scope: CoroutineScope, initialize: suspend () -> T) {
    private val _state = MutableStateFlow<DatabaseState<T>>(DatabaseState.Loading)
    val state: StateFlow<DatabaseState<T>> = _state.asStateFlow()
    private val initialization = scope.async(start = CoroutineStart.LAZY) {
        try {
            initialize().also { _state.value = DatabaseState.Ready(it) }
        } catch (error: Throwable) {
            _state.value = DatabaseState.Failed(error)
            throw error
        }
    }

    /** A deadline/cancelled waiter never cancels the owner's opener or changes its actual state. */
    suspend fun awaitReady(timeoutMs: Long = DEFAULT_WAIT_MS): T {
        require(timeoutMs > 0) { "Database readiness wait must be positive." }
        currentCoroutineContext().ensureActive()
        // Warm DAO calls avoid allocating a deadline job/timer for an already-open database.
        when (val current = _state.value) {
            is DatabaseState.Ready -> return current.database
            is DatabaseState.Failed -> throw current.cause
            DatabaseState.Loading -> Unit
        }
        return withTimeoutOrNull(timeoutMs) { initialization.await() }
            ?: throw DatabaseReadinessTimeoutException(timeoutMs)
    }

    companion object {
        const val DEFAULT_WAIT_MS = 30_000L
    }
}
