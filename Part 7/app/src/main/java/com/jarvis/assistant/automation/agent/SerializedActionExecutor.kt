package com.jarvis.assistant.automation.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ensures strict sequential execution of all UI mutation actions.
 * Guarantees that clicks, text inputs, scrolls, and key presses NEVER run concurrently,
 * preventing race conditions, overlapping gestures, and corrupted UI states.
 */
object SerializedActionExecutor {
    private const val TAG = "SerializedActionExec"
    private val mutex = Mutex()
    private val isCancelled = AtomicBoolean(false)
    private val taskGeneration = AtomicLong(0L)
    private val lifecycleLock = Any()
    private var activeActionJob: Job? = null

    val isBusy: Boolean
        get() = mutex.isLocked

    fun cancelAllPendingActions() {
        Log.w(TAG, "Cancelling all active/pending UI actions")
        advanceGeneration(cancelled = true)
    }

    fun resetCancellation() {
        advanceGeneration(cancelled = false)
    }

    /** Starts a fresh task epoch; actions queued by an older task remain invalid. */
    fun startNewTask(): Long {
        return advanceGeneration(cancelled = false)
    }

    private fun advanceGeneration(cancelled: Boolean): Long {
        val (generation, previous) = synchronized(lifecycleLock) {
            isCancelled.set(cancelled)
            val generation = taskGeneration.incrementAndGet()
            val previous = activeActionJob
            activeActionJob = null
            generation to previous
        }
        // Cancel outside the state lock: action cleanup can publish its own completion.
        previous?.cancel(CancellationException("UI task was stopped or replaced"))
        return generation
    }

    /**
     * Executes the given UI mutation block exclusively under a Mutex.
     * Only one block can execute at a time across the entire application.
     */
    suspend fun <T> execute(
        actionName: String,
        timeoutMs: Long = 15_000L,
        expectedGeneration: Long? = null,
        block: suspend () -> T
    ): T? {
        // Capture before waiting for the mutex so cancellation followed by a new task
        // cannot accidentally revive an older queued mutation.
        val submittedGeneration = expectedGeneration ?: taskGeneration.get()
        return withContext(Dispatchers.IO) {
            if (isCancelled.get() || submittedGeneration != taskGeneration.get()) {
                Log.w(TAG, "UI action rejected: task is cancelled or superseded")
                return@withContext null
            }
            // Log only a known category, never a caller-supplied UI label or message.
            val category = actionName.substringBefore(':').lowercase(java.util.Locale.ROOT)
                .takeIf { it in setOf("click", "type", "scroll", "key", "tap", "swipe") } ?: "ui"
            val startTime = System.nanoTime()
            try {
                // Queueing is part of the deadline; a blocked mutex must not extend a tool budget.
                val result = withTimeoutOrNull(timeoutMs) {
                    mutex.withLock {
                        val actionJob = currentCoroutineContext()[Job] ?: return@withLock null
                        val registered = synchronized(lifecycleLock) {
                            if (isCancelled.get() || submittedGeneration != taskGeneration.get()) false
                            else { activeActionJob = actionJob; true }
                        }
                        if (!registered) return@withLock null
                        try {
                            currentCoroutineContext().ensureActive()
                            block()
                        } finally {
                            synchronized(lifecycleLock) {
                                if (activeActionJob === actionJob) activeActionJob = null
                            }
                        }
                    }
                }
                val elapsed = (System.nanoTime() - startTime) / 1_000_000
                Log.i(TAG, "UI $category action returned in ${elapsed}ms; resultAvailable=${result != null}")
                result
            } catch (e: CancellationException) {
                Log.w(TAG, "UI action cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "UI action failed (${e.javaClass.simpleName})")
                null
            }
        }
    }
}
