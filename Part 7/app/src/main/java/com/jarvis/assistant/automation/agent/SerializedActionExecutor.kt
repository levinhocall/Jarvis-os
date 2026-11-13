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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.accessibility.AccessibilityIssue
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService

/**
 * Ensures strict sequential execution of all UI mutation actions.
 * Guarantees that clicks, text inputs, scrolls, and key presses NEVER run concurrently,
 * preventing race conditions, overlapping gestures, and corrupted UI states.
 */
object SerializedActionExecutor {
    private const val TAG = "SerializedActionExec"
    private val mutex = Mutex()
    internal const val MAX_PENDING = 16
    private val admission = Semaphore(MAX_PENDING)
    private val pending = AtomicInteger(0)
    val pendingCount: Int get() = pending.get()
    private data class Returned<T>(val value: T)
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
        if (timeoutMs <= 0) return null
        val submittedGeneration = expectedGeneration ?: taskGeneration.get()
        val owner = JarvisAccessibilityService.instance?.connectionToken ?: 0L
        if (isCancelled.get() || submittedGeneration != taskGeneration.get()) return null
        if (!admission.tryAcquire()) {
            AccessibilityHealthModel.recordIssue(AccessibilityIssue.QUEUE_OVERLOAD, owner)
            return null
        }
        AccessibilityHealthModel.recordQueue(pending.incrementAndGet(), isBusy, owner)
        val category = actionName.substringBefore(':').lowercase(java.util.Locale.ROOT)
            .takeIf { it in setOf("click", "type", "scroll", "key", "tap", "swipe") } ?: "ui"
        try {
            // Both Main dispatch and mutex wait consume the same caller deadline.
            val returned = withTimeoutOrNull<Returned<T?>>(timeoutMs) {
                withContext(Dispatchers.Main.immediate) {
                    mutex.withLock {
                        val actionJob = currentCoroutineContext()[Job] ?: return@withLock Returned<T?>(null)
                        val registered = synchronized(lifecycleLock) {
                            if (isCancelled.get() || submittedGeneration != taskGeneration.get() ||
                                (owner != 0L && !AccessibilityHealthModel.owns(owner))) false
                            else { activeActionJob = actionJob; true }
                        }
                        if (!registered) return@withLock Returned<T?>(null)
                        AccessibilityHealthModel.recordQueue(pending.get(), true, owner)
                        val startedAt = System.nanoTime()
                        try {
                            currentCoroutineContext().ensureActive()
                            val value = block()
                            if (owner != 0L && !AccessibilityHealthModel.owns(owner)) return@withLock Returned<T?>(null)
                            if (value is Boolean) {
                                if (value) AccessibilityHealthModel.recordActionSuccess(category, owner)
                                else AccessibilityHealthModel.recordActionFailure(category, "rejected", owner)
                            }
                            Returned<T?>(value)
                        } finally {
                            AccessibilityHealthModel.recordDuration((System.nanoTime() - startedAt) / 1_000_000, owner)
                            synchronized(lifecycleLock) { if (activeActionJob === actionJob) activeActionJob = null }
                        }
                    }
                }
            }
            if (returned == null) AccessibilityHealthModel.recordIssue(AccessibilityIssue.ACTION_TIMEOUT, owner)
            return returned?.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "UI action failed (${error.javaClass.simpleName})")
            AccessibilityHealthModel.recordIssue(AccessibilityIssue.INTERNAL, owner)
            return null
        } finally {
            val remaining = pending.decrementAndGet()
            admission.release()
            AccessibilityHealthModel.recordQueue(remaining, isBusy, owner)
        }
    }
}
