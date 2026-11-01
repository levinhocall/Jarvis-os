package com.jarvis.assistant.automation.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfflineIntelligenceRouterTest {
    @Before
    fun setUp() {
        OfflineIntelligenceRouter.clearForTest()
    }

    @Test
    fun pendingActionsAreNotDroppedWhenNoExecutorIsRegistered() {
        assertTrue(OfflineIntelligenceRouter.enqueueDeferredAction("BACKGROUND_QUERY", "weather", false))
        assertEquals(0, OfflineIntelligenceRouter.processPendingDeferredActions())
        assertEquals(1, OfflineIntelligenceRouter.getPendingDeferredActions().size)
    }

    @Test
    fun onlyExecutorConfirmedNonConsequentialActionsAreRemoved() {
        OfflineIntelligenceRouter.enqueueDeferredAction("BACKGROUND_QUERY", "weather", false)
        OfflineIntelligenceRouter.enqueueDeferredAction("SEND_MESSAGE", "private message", true)

        OfflineIntelligenceRouter.setDeferredActionExecutor { it.actionType == "BACKGROUND_QUERY" }
        assertEquals(1, OfflineIntelligenceRouter.processPendingDeferredActions())

        val remaining = OfflineIntelligenceRouter.getPendingDeferredActions()
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().isConsequential)
    }
}
