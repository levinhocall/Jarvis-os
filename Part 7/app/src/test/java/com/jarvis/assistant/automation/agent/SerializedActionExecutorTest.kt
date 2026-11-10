package com.jarvis.assistant.automation.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SerializedActionExecutorTest {
    @Test fun stopCancelsRunningActionAndPreventsLaterSteps() = runBlocking {
        val generation = SerializedActionExecutor.startNewTask()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var laterStep = false
        val running = async {
            SerializedActionExecutor.execute("test", expectedGeneration = generation) {
                entered.complete(Unit)
                release.await()
                laterStep = true
            }
        }
        try {
            withTimeout(3000) { entered.await() }
            SerializedActionExecutor.cancelAllPendingActions()
            withTimeout(3000) { running.join() }
            assertTrue(running.isCancelled)
            assertFalse(laterStep)
        } finally { release.complete(Unit); running.cancelAndJoin(); SerializedActionExecutor.resetCancellation() }
    }

    @Test fun replacementCancelsOldActionAndRejectsItsQueuedWork() = runBlocking {
        val old = SerializedActionExecutor.startNewTask()
        val entered = CompletableDeferred<Unit>()
        var staleActionRan = false
        val running = async {
            SerializedActionExecutor.execute("test", expectedGeneration = old) { entered.complete(Unit); awaitCancellation() }
        }
        try {
            withTimeout(3000) { entered.await() }
            val queued = async { SerializedActionExecutor.execute("test", expectedGeneration = old) { staleActionRan = true } }
            val fresh = SerializedActionExecutor.startNewTask()
            assertEquals(42, withTimeout(3000) { SerializedActionExecutor.execute("test", expectedGeneration = fresh) { 42 } })
            assertNull(withTimeout(3000) { queued.await() })
            assertFalse(staleActionRan)
            // The mutex may be released before withContext dispatches cancellation
            // back to the caller. Await that boundary instead of racing isCancelled.
            withTimeout(3000) { running.join() }
            assertTrue(running.isCancelled)
        } finally { running.cancelAndJoin(); SerializedActionExecutor.resetCancellation() }
    }

    @Test fun deadlineIncludesTimeWaitingForActionMutex() = runBlocking {
        val generation = SerializedActionExecutor.startNewTask()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = async {
            SerializedActionExecutor.execute("test", expectedGeneration = generation) { entered.complete(Unit); release.await() }
        }
        try {
            withTimeout(3000) { entered.await() }
            var queuedRan = false
            val result = withTimeout(3000) {
                SerializedActionExecutor.execute("test", timeoutMs = 50, expectedGeneration = generation) { queuedRan = true }
            }
            assertNull(result)
            assertFalse(queuedRan)
            assertTrue(held.isActive)
        } finally { release.complete(Unit); held.cancelAndJoin(); SerializedActionExecutor.resetCancellation() }
    }
}
