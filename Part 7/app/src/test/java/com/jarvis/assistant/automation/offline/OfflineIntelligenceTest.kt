package com.jarvis.assistant.automation.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OfflineIntelligenceTest {

    private lateinit var coordinator: OfflineIntelligenceCoordinator

    @Before
    fun setUp() {
        coordinator = OfflineIntelligenceCoordinator()
    }

    @Test
    fun `device hardware command without Android context is not reported as executed`() = runBlocking {
        val result = coordinator.handleCommandOffline("turn on flashlight")
        assertTrue(result is OfflineExecutionResult.DegradedExplanation)
        assertTrue((result as OfflineExecutionResult.DegradedExplanation).userFeedback.contains("was not executed"))
    }

    @Test
    fun `local notes save to temporary cache when persistent app storage is unavailable`() = runBlocking {
        val noteResult = coordinator.handleCommandOffline("remember flight confirmation code is XYZ-789")
        assertTrue(noteResult is OfflineExecutionResult.ExecutedLocally)
        assertTrue(noteResult.userFeedback.contains("temporary offline cache"))

        val found = coordinator.searchLocalKnowledge("XYZ-789")
        assertEquals(1, found.size)
        assertTrue(found[0].contains("XYZ-789"))
    }

    @Test
    fun `non consequential search is queued without claiming automatic sync`() = runBlocking {
        val result = coordinator.handleCommandOffline("search weather in Tokyo")
        assertTrue(result is OfflineExecutionResult.QueuedForOnline)
        assertFalse((result as OfflineExecutionResult.QueuedForOnline).requiresConfirmationOnSync)
        assertTrue(result.userFeedback.contains("automatic sync is not currently connected"))
        assertEquals(1, coordinator.getSyncQueue().size)
    }

    @Test
    fun `consequential action is queued with confirmation requirement`() = runBlocking {
        val result = coordinator.handleCommandOffline("send email to boss with contract attached")
        assertTrue(result is OfflineExecutionResult.QueuedForOnline)
        assertTrue((result as OfflineExecutionResult.QueuedForOnline).requiresConfirmationOnSync)

        val queue = coordinator.getSyncQueue()
        assertEquals(1, queue.size)
        assertTrue(queue[0].isConsequential)
    }

    @Test
    fun `degraded explanation gives honest offline alternatives for unsupported features`() = runBlocking {
        val result = coordinator.handleCommandOffline("generate a python script for deep learning")
        assertTrue(result is OfflineExecutionResult.DegradedExplanation)
        val explanation = result as OfflineExecutionResult.DegradedExplanation
        assertTrue(explanation.userFeedback.contains("cannot perform complex cloud reasoning"))
        assertTrue(explanation.availableOfflineAlternatives.isNotEmpty())
    }

    @Test
    fun `processSyncQueue retains non-consequential items until a real handler succeeds`() = runBlocking {
        coordinator.handleCommandOffline("search recipe for pasta") // non-consequential
        coordinator.handleCommandOffline("pay utility bill of $50") // consequential

        // While still offline, sync is deferred
        val offlineSync = coordinator.processSyncQueue(isOnline = false)
        assertEquals(0, offlineSync.syncedCount)

        // When online returns
        val onlineSync = coordinator.processSyncQueue(isOnline = true)
        assertEquals(0, onlineSync.syncedCount)
        assertEquals(1, onlineSync.pendingConfirmationCount)
        assertTrue(onlineSync.details.any { it.contains("no real sync executor") })

        val remainingQueue = coordinator.getSyncQueue()
        assertEquals(2, remainingQueue.size)
        assertTrue(remainingQueue.any { it.syncStatus == SyncStatus.AWAITING_CONFIRMATION })
        assertTrue(remainingQueue.any { it.syncStatus == SyncStatus.PENDING })

        val handled = coordinator.processSyncQueue(isOnline = true) { it.actionType == "BACKGROUND_QUERY_SYNC" }
        assertEquals(1, handled.syncedCount)
        assertEquals(1, handled.pendingConfirmationCount)
        assertEquals(1, coordinator.getSyncQueue().size)
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
