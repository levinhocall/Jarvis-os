package com.jarvis.assistant.automation.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfflineIntelligenceTest {

    private lateinit var coordinator: OfflineIntelligenceCoordinator

    @Before
    fun setUp() {
        coordinator = OfflineIntelligenceCoordinator()
    }

    @Test
    fun `device hardware controls execute locally offline`() {
        val result = coordinator.handleCommandOffline("turn on flashlight")
        assertTrue(result is OfflineExecutionResult.ExecutedLocally)
        assertEquals("DEVICE_HARDWARE", (result as OfflineExecutionResult.ExecutedLocally).actionType)
        assertEquals("ON", result.outputData["state"])
    }

    @Test
    fun `local notes save to cache and can be searched offline`() {
        val noteResult = coordinator.handleCommandOffline("remember flight confirmation code is XYZ-789")
        assertTrue(noteResult is OfflineExecutionResult.ExecutedLocally)

        val found = coordinator.searchLocalKnowledge("XYZ-789")
        assertEquals(1, found.size)
        assertTrue(found[0].contains("XYZ-789"))
    }

    @Test
    fun `non consequential search query is queued for automatic online sync`() {
        val result = coordinator.handleCommandOffline("search weather in Tokyo")
        assertTrue(result is OfflineExecutionResult.QueuedForOnline)
        assertFalse((result as OfflineExecutionResult.QueuedForOnline).requiresConfirmationOnSync)

        assertEquals(1, coordinator.getSyncQueue().size)
    }

    @Test
    fun `consequential action is queued with confirmation requirement`() {
        val result = coordinator.handleCommandOffline("send email to boss with contract attached")
        assertTrue(result is OfflineExecutionResult.QueuedForOnline)
        assertTrue((result as OfflineExecutionResult.QueuedForOnline).requiresConfirmationOnSync)

        val queue = coordinator.getSyncQueue()
        assertEquals(1, queue.size)
        assertTrue(queue[0].isConsequential)
    }

    @Test
    fun `degraded explanation gives honest offline alternatives for unsupported features`() {
        val result = coordinator.handleCommandOffline("generate a python script for deep learning")
        assertTrue(result is OfflineExecutionResult.DegradedExplanation)
        val explanation = result as OfflineExecutionResult.DegradedExplanation
        assertTrue(explanation.userFeedback.contains("cannot perform complex cloud reasoning"))
        assertTrue(explanation.availableOfflineAlternatives.isNotEmpty())
    }

    @Test
    fun `processSyncQueue flushes non-consequential items and holds consequential for confirmation`() {
        coordinator.handleCommandOffline("search recipe for pasta") // non-consequential
        coordinator.handleCommandOffline("pay utility bill of $50") // consequential

        // While still offline, sync is deferred
        val offlineSync = coordinator.processSyncQueue(isOnline = false)
        assertEquals(0, offlineSync.syncedCount)

        // When online returns
        val onlineSync = coordinator.processSyncQueue(isOnline = true)
        assertEquals(1, onlineSync.syncedCount)
        assertEquals(1, onlineSync.pendingConfirmationCount)

        val remainingQueue = coordinator.getSyncQueue()
        assertEquals(1, remainingQueue.size)
        assertEquals(SyncStatus.AWAITING_CONFIRMATION, remainingQueue[0].syncStatus)
    }

    @Test
    fun `honest capability matrix reports accurate availability`() {
        val matrix = coordinator.getHonestCapabilityMatrix()
        assertTrue(matrix["Hardware Control (Torch, Volume)"] == true)
        assertTrue(matrix["Local Memory & Notes"] == true)
        assertFalse(matrix["Web Search & Scraping"] == true)
        assertFalse(matrix["Cloud LLM Reasoning"] == true)
    }
}
