package com.jarvis.assistant.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

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
}
