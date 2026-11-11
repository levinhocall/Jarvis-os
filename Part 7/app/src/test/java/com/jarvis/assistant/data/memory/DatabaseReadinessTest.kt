package com.jarvis.assistant.data.memory

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DatabaseReadinessTest {
    @Test fun concurrentWaitersShareOneInitializationAndDoNotBlockCaller() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var opens = 0
        val gate = DatabaseReadiness(scope) { opens++; entered.complete(Unit); release.await() }
        try {
            assertEquals(DatabaseReadiness.State.PENDING, gate.state.value)
            assertEquals(0, opens)
            val first = launch { gate.awaitReady() }
            val second = launch { gate.awaitReady() }
            withTimeout(5_000) { entered.await() }
            assertEquals(DatabaseReadiness.State.OPENING, gate.state.value)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { joinAll(first, second) }
            gate.awaitReady()
            assertEquals(1, opens)
            assertEquals(DatabaseReadiness.State.READY, gate.state.value)
        } finally { scope.cancel() }
    }

    @Test fun cancelledWaiterDoesNotCancelDatabaseInitialization() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = DatabaseReadiness(scope) { entered.complete(Unit); release.await() }
        try {
            val waiter = launch { gate.awaitReady() }
            withTimeout(5_000) { entered.await() }
            waiter.cancelAndJoin()
            release.complete(Unit)
            withTimeout(5_000) { gate.awaitReady() }
            assertEquals(DatabaseReadiness.State.READY, gate.state.value)
        } finally { scope.cancel() }
    }

    @Test fun failurePropagatesAndDoesNotRetryOrClaimReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var opens = 0
        val failure = IllegalStateException("key unavailable")
        val gate = DatabaseReadiness(scope) { opens++; throw failure }
        try {
            repeat(2) {
                val actual = runCatching { gate.awaitReady() }.exceptionOrNull()
                assertTrue(actual is IllegalStateException)
                assertEquals(failure.message, actual?.message)
            }
            assertEquals(1, opens)
            assertEquals(DatabaseReadiness.State.FAILED, gate.state.value)
        } finally { scope.cancel() }
    }
    @Test fun cancellingTheOwnerUnblocksWaitersWithoutClaimingReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val gate = DatabaseReadiness(scope) { entered.complete(Unit); hold.await() }
        try {
            val result = async { runCatching { gate.awaitReady() } }
            withTimeout(5_000) { entered.await() }
            scope.cancel()
            val error = withTimeout(5_000) { result.await().exceptionOrNull() }
            assertTrue(error is CancellationException)
            assertEquals(DatabaseReadiness.State.FAILED, gate.state.value)
        } finally { scope.cancel() }
    }
}
