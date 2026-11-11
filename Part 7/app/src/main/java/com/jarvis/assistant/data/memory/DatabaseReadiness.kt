package com.jarvis.assistant.data.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single-flight initialization owned by the database, never by an individual UI waiter. */
internal class DatabaseReadiness(scope: CoroutineScope, initialize: suspend () -> Unit) {
    enum class State { PENDING, OPENING, READY, FAILED }
    private val _state = MutableStateFlow(State.PENDING)
    val state: StateFlow<State> = _state.asStateFlow()
    private val initialization = scope.async(start = CoroutineStart.LAZY) {
        _state.value = State.OPENING
        try {
            initialize()
            _state.value = State.READY
        } catch (error: Throwable) {
            _state.value = State.FAILED
            throw error
        }
    }

    /** Suspends without blocking Main. A failed open stays failed; no silent data reset/retry. */
    suspend fun awaitReady() = initialization.await()
}
