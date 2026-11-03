package com.jarvis.assistant.automation.council

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Shared across missions: queued work consumes its deadline, but no provider call budget. */
internal class BoundedAgentRunner(
    parallelism: Int = 3,
    private val maxCalls: Int = 3,
    private val callTimeoutMs: Long = 18_000L,
    private val missionTimeoutMs: Long = 24_000L
) {
    init {
        require(parallelism > 0 && maxCalls > 0)
        require(callTimeoutMs > 0 && missionTimeoutMs > 0)
    }

    private val permits = Semaphore(parallelism)

    data class Result(val reports: Map<String, String>, val callsUsed: Int)

    suspend fun run(
        roles: List<String>,
        review: suspend (String) -> String?
    ): Result = coroutineScope {
        val calls = AtomicInteger()
        val reports = ConcurrentHashMap<String, String>()
        val uniqueRoles = roles.distinct().take(maxCalls)
        withTimeoutOrNull(missionTimeoutMs) {
            uniqueRoles.map { role ->
                async {
                    permits.withPermit {
                        calls.incrementAndGet()
                        try {
                            val report = withTimeoutOrNull(callTimeoutMs) { review(role) }
                            report?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                reports[role] = it.take(4000)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A failed reviewer cannot cancel its peers or grant execution authority.
                        }
                    }
                }
            }.awaitAll()
        }
        // Preserve completed evidence on deadline; deterministic role order, never completion order.
        Result(uniqueRoles.mapNotNull { role -> reports[role]?.let { role to it } }.toMap(), calls.get())
    }
}
