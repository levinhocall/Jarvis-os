package com.jarvis.assistant.util

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers [launchPersisting], the helper that replaced unmanaged `CoroutineScope(...).launch`
 * calls in SecurityGuardManager and ExpenseLedgerTracker (audit finding F4).
 */
class BackgroundPersistenceTest {
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        // Same shape as JarvisApp.applicationScope, deliberately without an exception handler:
        // if the helper let a failure escape, the job would end cancelled.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun successfulWorkRunsAndCompletesNormally() = runBlocking {
        val ran = AtomicBoolean(false)
        val job = scope.launchPersisting("test", "work") { ran.set(true) }
        withTimeout(5_000) { job.join() }
        assertTrue(ran.get())
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
    }

    @Test
    fun failureIsContainedAndDoesNotCancelJobOrScope() = runBlocking {
        val failing = scope.launchPersisting("test", "failing work") {
            error("sensitive text that must not be needed by the caller")
        }
        withTimeout(5_000) { failing.join() }
        assertTrue(failing.isCompleted)
        assertFalse("A contained failure must not cancel the job", failing.isCancelled)
        assertTrue("A contained failure must not cancel the scope", scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun failureInOneJobDoesNotAffectSiblingJobs() = runBlocking {
        val completed = AtomicInteger(0)
        val jobs = listOf(
            scope.launchPersisting("test", "a") { completed.incrementAndGet() },
            scope.launchPersisting("test", "b") { throw IllegalStateException("boom") },
            scope.launchPersisting("test", "c") { completed.incrementAndGet() },
        )
        withTimeout(5_000) { jobs.forEach { it.join() } }
        assertEquals(2, completed.get())
        assertTrue(jobs.none { it.isCancelled })
    }

    @Test
    fun scopeCancellationPropagatesAndRunsCleanup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cleanedUp = CompletableDeferred<Unit>()
        val continuedAfterCancel = AtomicBoolean(false)
        val job = scope.launchPersisting("test", "long work") {
            try {
                started.complete(Unit)
                delay(60_000) // only ever ends via cancellation within this test
            } finally {
                cleanedUp.complete(Unit)
            }
            continuedAfterCancel.set(true)
        }
        withTimeout(5_000) { started.await() }
        scope.cancel()
        withTimeout(5_000) { job.join() }
        withTimeout(5_000) { cleanedUp.await() }
        assertTrue("Cancellation must not be swallowed as a failure", job.isCancelled)
        assertFalse(continuedAfterCancel.get())
    }

    @Test
    fun cancellingOneJobLeavesScopeAndOtherJobsRunning() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = AtomicBoolean(false)
        val cancelled = scope.launchPersisting("test", "to cancel") { awaitCancellation() }
        val survivor = scope.launchPersisting("test", "survivor") {
            started.complete(Unit)
            release.await()
            finished.set(true)
        }
        withTimeout(5_000) { started.await() }
        cancelled.cancel()
        withTimeout(5_000) { cancelled.join() }
        assertTrue(cancelled.isCancelled)
        assertFalse("Survivor must still be running", survivor.isCompleted)
        release.complete(Unit)
        withTimeout(5_000) { survivor.join() }
        assertTrue(finished.get())
        assertFalse(survivor.isCancelled)
    }
}
