package com.jarvis.assistant.automation.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class AccessibilityBreadcrumbsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testFile: File

    @Before
    fun setup() {
        testFile = tempFolder.newFile("test-breadcrumbs.log")
        AccessibilityBreadcrumbs.clear()
        AccessibilityBreadcrumbs.initWithFile(testFile)
    }

    @Test
    fun testRingBufferCapacity_capsAt200Entries() {
        for (i in 1..250) {
            AccessibilityBreadcrumbs.record("TEST", "Entry #$i")
        }

        val entries = AccessibilityBreadcrumbs.getBreadcrumbs()
        assertEquals(200, entries.size)
        // First entry should be #51, last should be #250
        assertTrue(entries.first().contains("Entry #51"))
        assertTrue(entries.last().contains("Entry #250"))
    }

    @Test
    fun testExceptionFormatting_capturesClassAndFrames() {
        val testEx = IllegalArgumentException("Invalid state in test")
        AccessibilityBreadcrumbs.recordException(testEx, "UnitTestContext")

        val entries = AccessibilityBreadcrumbs.getBreadcrumbs()
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertTrue(entry.contains("IllegalArgumentException"))
        assertTrue(entry.contains("Invalid state in test"))
        assertTrue(entry.contains("UnitTestContext"))
        assertTrue(entry.contains("AccessibilityBreadcrumbsTest"))
    }

    @Test
    fun testThreadSafety_concurrentWrites() {
        val threads = 10
        val writesPerThread = 20
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)

        for (t in 0 until threads) {
            executor.submit {
                try {
                    for (w in 0 until writesPerThread) {
                        AccessibilityBreadcrumbs.record("THREAD_$t", "Write_$w")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val entries = AccessibilityBreadcrumbs.getBreadcrumbs()
        assertEquals(200, entries.size)
    }

    @Test
    fun testPersistenceAndReload() {
        AccessibilityBreadcrumbs.record("EVENT", "Persisted entry 1")
        AccessibilityBreadcrumbs.record("EVENT", "Persisted entry 2")

        // Re-init with same file
        AccessibilityBreadcrumbs.initWithFile(testFile)
        val reloaded = AccessibilityBreadcrumbs.getBreadcrumbs()
        assertEquals(2, reloaded.size)
        assertTrue(reloaded[0].contains("Persisted entry 1"))
        assertTrue(reloaded[1].contains("Persisted entry 2"))
    }
}
