package com.jarvis.assistant.data.memory

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseReadinessTest {
    @Test fun constructionIsLoadingAndDoesNotOpenUntilAwaited() = runTest {
        var opens = 0
        val gate = DatabaseReadiness(backgroundScope) { opens++; "database" }
        assertEquals(DatabaseState.Loading, gate.state.value)
        runCurrent()
        assertEquals(0, opens)
        val waiter = async { gate.awaitReady() }
        runCurrent()
        assertEquals("database", waiter.await())
        assertEquals(DatabaseState.Ready("database"), gate.state.value)
    }

    @Test fun concurrentWaitersShareOneInitializationAndDoNotBlockCaller() = runTest {
        var opens = 0
        val release = CompletableDeferred<Unit>()
        val gate = DatabaseReadiness(backgroundScope) { opens++; release.await(); "database" }
        val first = async { gate.awaitReady() }
        val second = async { gate.awaitReady() }
        runCurrent()
        assertEquals(1, opens)
        assertEquals(DatabaseState.Loading, gate.state.value)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertEquals("database", first.await())
        assertEquals("database", second.await())
        assertEquals(DatabaseState.Ready("database"), gate.state.value)
        assertEquals(1, opens)
    }

    @Test fun cancelledWaiterDoesNotCancelDatabaseInitialization() = runTest {
        val release = CompletableDeferred<Unit>()
        val gate = DatabaseReadiness(backgroundScope) { release.await(); "database" }
        val waiter = launch { gate.awaitReady() }
        runCurrent()
        waiter.cancelAndJoin()
        assertEquals(DatabaseState.Loading, gate.state.value)
        release.complete(Unit)
        runCurrent()
        assertEquals("database", gate.awaitReady())
    }

    @Test fun failurePropagatesAndDoesNotRetryOrClaimReady() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var opens = 0
        val failure = IllegalStateException("key unavailable")
        val gate = DatabaseReadiness<String>(scope) { opens++; throw failure }
        try {
            repeat(2) {
                val actual = runCatching { gate.awaitReady() }.exceptionOrNull()
                assertTrue(actual is IllegalStateException)
                assertEquals(failure.message, actual?.message)
            }
            assertEquals(1, opens)
            assertTrue(gate.state.value is DatabaseState.Failed)
            assertEquals(failure.message, (gate.state.value as DatabaseState.Failed).cause.message)
        } finally { scope.cancel() }
    }

    @Test fun cancellingTheOwnerUnblocksWaitersWithoutClaimingReady() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val hold = CompletableDeferred<Unit>()
        val gate = DatabaseReadiness(scope) { hold.await(); "database" }
        try {
            val result = async { runCatching { gate.awaitReady() } }
            runCurrent()
            scope.cancel()
            runCurrent()
            assertTrue(result.await().exceptionOrNull() is CancellationException)
            assertTrue(gate.state.value is DatabaseState.Failed)
        } finally { scope.cancel() }
    }

    @Test fun timeoutIsNotCancellationAndDoesNotCancelTheOpenerOrClaimFailure() = runTest {
        var opens = 0
        val gate = DatabaseReadiness(backgroundScope) { opens++; delay(1_000); "database" }
        val shortWait = async { runCatching { gate.awaitReady(100) } }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        val failure = shortWait.await().exceptionOrNull()
        assertTrue(failure is DatabaseReadinessTimeoutException)
        assertFalse(failure is CancellationException)
        assertEquals(100L, (failure as DatabaseReadinessTimeoutException).timeoutMs)
        assertEquals(DatabaseState.Loading, gate.state.value)
        advanceTimeBy(900)
        runCurrent()
        assertEquals("database", gate.awaitReady(1))
        assertEquals(DatabaseState.Ready("database"), gate.state.value)
        assertEquals(1, opens)
    }

    @Test fun differentWaiterDeadlinesDoNotCancelAnotherWaiter() = runTest {
        val gate = DatabaseReadiness(backgroundScope) { delay(500); "database" }
        val shortWait = async { runCatching { gate.awaitReady(50) } }
        val longWait = async { gate.awaitReady(1_000) }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()
        assertTrue(shortWait.await().exceptionOrNull() is DatabaseReadinessTimeoutException)
        assertFalse(longWait.isCompleted)
        advanceTimeBy(450)
        runCurrent()
        assertEquals("database", longWait.await())
    }

    @Test fun invalidDeadlineDoesNotStartInitialization() = runTest {
        var opens = 0
        val gate = DatabaseReadiness(backgroundScope) { opens++; "database" }
        for (budget in listOf(0L, -1L)) {
            assertTrue(runCatching { gate.awaitReady(budget) }.exceptionOrNull() is IllegalArgumentException)
        }
        runCurrent()
        assertEquals(0, opens)
        assertEquals(DatabaseState.Loading, gate.state.value)
    }

    @Test fun warmReadyCallsDoNotAdvanceTimeOrReinitialize() = runTest {
        var opens = 0
        val gate = DatabaseReadiness(backgroundScope) { opens++; "database" }
        assertEquals("database", gate.awaitReady())
        val now = testScheduler.currentTime
        repeat(100) { assertEquals("database", gate.awaitReady(1)) }
        runCurrent()
        assertEquals(now, testScheduler.currentTime)
        assertEquals(1, opens)
    }
}
