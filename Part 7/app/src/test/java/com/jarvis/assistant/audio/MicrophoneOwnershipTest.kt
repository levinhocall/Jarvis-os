package com.jarvis.assistant.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneOwnershipTest {
    @Test fun wakeLiveAndEnrollmentCannotCaptureTogether() {
        for (owner in MicrophoneOwner.entries) {
            val lease = MicrophoneOwnership.acquire(owner)!!
            try {
                assertEquals(owner, MicrophoneOwnership.owner.value)
                for (other in MicrophoneOwner.entries) assertNull(MicrophoneOwnership.acquire(other))
            } finally { lease.close() }
            assertNull(MicrophoneOwnership.owner.value)
        }
    }

    @Test fun oldOrDuplicateReleaseCannotReleaseNewCapture() {
        val old = MicrophoneOwnership.acquire(MicrophoneOwner.WAKE_WORD)!!
        old.close()
        val current = MicrophoneOwnership.acquire(MicrophoneOwner.VOICE_SESSION)!!
        try {
            old.close()
            assertEquals(MicrophoneOwner.VOICE_SESSION, MicrophoneOwnership.owner.value)
            assertNull(MicrophoneOwnership.acquire(MicrophoneOwner.VOICE_ENROLLMENT))
        } finally { current.close() }
    }

    @Test fun concurrentStartsHaveExactlyOneOwner() {
        val pool = Executors.newFixedThreadPool(3)
        val ready = CountDownLatch(3)
        val start = CountDownLatch(1)
        val leases = java.util.Collections.synchronizedList(mutableListOf<MicrophoneOwnership.Lease>())
        try {
            val futures = MicrophoneOwner.entries.map { owner -> pool.submit {
                ready.countDown(); start.await()
                MicrophoneOwnership.acquire(owner)?.let { leases.add(it) }
            } }
            assertTrue(ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(3, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, leases.size)
        } finally {
            start.countDown()
            leases.forEach { it.close() }
            pool.shutdownNow()
        }
    }
    @Test fun waiterContinuesOnlyAfterRetiringCaptureReleasesItsLease() = runTest {
        val lease = MicrophoneOwnership.acquire(MicrophoneOwner.VOICE_SESSION)!!
        try {
            val waiter = async { MicrophoneOwnership.awaitAvailable(1_500) }
            runCurrent(); advanceTimeBy(1_000)
            assertFalse(waiter.isCompleted)
            lease.close(); runCurrent()
            assertTrue(waiter.await())
            val next = MicrophoneOwnership.acquire(MicrophoneOwner.WAKE_WORD)!!
            try { lease.close(); assertEquals(MicrophoneOwner.WAKE_WORD, MicrophoneOwnership.owner.value) }
            finally { next.close() }
        } finally { lease.close() }
    }

    @Test fun waitTimeoutDoesNotReleaseAnotherOwnersHardware() = runTest {
        val lease = MicrophoneOwnership.acquire(MicrophoneOwner.VOICE_SESSION)!!
        try {
            val waiter = async { MicrophoneOwnership.awaitAvailable(1_500) }
            runCurrent(); advanceTimeBy(1_501); runCurrent()
            assertFalse(waiter.await())
            assertEquals(MicrophoneOwner.VOICE_SESSION, MicrophoneOwnership.owner.value)
        } finally { lease.close() }
    }

    @Test fun cancellationDoesNotReleaseAnotherOwnersHardware() = runTest {
        val lease = MicrophoneOwnership.acquire(MicrophoneOwner.VOICE_SESSION)!!
        try {
            val waiter = async { MicrophoneOwnership.awaitAvailable() }
            runCurrent(); waiter.cancelAndJoin()
            assertTrue(waiter.isCancelled)
            assertEquals(MicrophoneOwner.VOICE_SESSION, MicrophoneOwnership.owner.value)
        } finally { lease.close() }
        assertTrue(MicrophoneOwnership.awaitAvailable())
    }

}
