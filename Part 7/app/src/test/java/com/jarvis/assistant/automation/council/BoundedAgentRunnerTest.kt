package com.jarvis.assistant.automation.council

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BoundedAgentRunnerTest {
    @Test fun budgetsDeduplicateRolesAndKeepRoleOrder() = runBlocking {
        val runner = BoundedAgentRunner(maxCalls = 2)
        val result = runner.run(listOf("planner", "planner", "critic", "verifier")) { "REVIEW: $it" }
        assertEquals(2, result.callsUsed)
        assertEquals(listOf("planner", "critic"), result.reports.keys.toList())
    }

    @Test fun boundsWorkersAcrossConcurrentMissions() = runBlocking {
        val runner = BoundedAgentRunner(parallelism = 2)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val saturated = CompletableDeferred<Unit>()
        val jobs = (1..4).map {
            async {
                runner.run(listOf("planner", "critic", "verifier")) {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { old -> maxOf(old, count) }
                    if (count == 2) saturated.complete(Unit)
                    try { release.await(); "REVIEW: done" } finally { active.decrementAndGet() }
                }
            }
        }
        withTimeout(3000) { saturated.await() }
        assertEquals(2, peak.get())
        release.complete(Unit)
        assertTrue(jobs.awaitAll().all { it.callsUsed == 3 && it.reports.size == 3 })
        assertEquals(2, peak.get())
        assertEquals(0, active.get())
    }

    @Test fun isolatedFailureDoesNotDiscardPeerReports() = runBlocking {
        val result = BoundedAgentRunner().run(listOf("planner", "critic", "verifier")) {
            if (it == "critic") error("private provider error") else "REVIEW: $it"
        }
        assertEquals(3, result.callsUsed)
        assertEquals(listOf("planner", "verifier"), result.reports.keys.toList())
    }

    @Test fun deadlinePreservesCompletedReviews() = runBlocking {
        val result = BoundedAgentRunner(callTimeoutMs = 2000, missionTimeoutMs = 100).run(listOf("fast", "slow")) {
            if (it == "slow") delay(2000)
            "REVIEW: $it"
        }
        assertEquals(2, result.callsUsed)
        assertEquals(setOf("fast"), result.reports.keys)
    }

    @Test fun cancellationPropagatesAndReleasesWorkerPermits() = runBlocking {
        val runner = BoundedAgentRunner(parallelism = 1)
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            runner.run(listOf("first")) { entered.complete(Unit); delay(10000); "unreachable" }
        }
        withTimeout(3000) { entered.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        val result = withTimeout(3000) { runner.run(listOf("next")) { "REVIEW: recovered" } }
        assertEquals(1, result.callsUsed)
        assertEquals("REVIEW: recovered", result.reports["next"])
    }

    @Test fun queuedCallsConsumeDeadlineButNoProviderBudget() = runBlocking {
        val runner = BoundedAgentRunner(parallelism = 1, callTimeoutMs = 100, missionTimeoutMs = 30)
        val result = runner.run(listOf("planner", "critic", "verifier")) { delay(1000); "unreachable" }
        assertEquals(1, result.callsUsed)
        assertTrue(result.reports.isEmpty())
    }
}
