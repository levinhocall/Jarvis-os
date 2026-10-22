package com.jarvis.assistant.core.ambient

import com.jarvis.assistant.data.model.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AmbientExperienceTest {

    @Before
    fun setUp() {
        AmbientExperienceCoordinator.resetForTest()
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(ConversationState.IDLE, AmbientExperienceCoordinator.conversationState.value)
        assertTrue(AmbientExperienceCoordinator.activeMissions.value.isEmpty())
    }

    @Test
    fun testStateTransitionsCoverUnifiedStateModel() {
        val states = listOf(
            ConversationState.LISTENING,
            ConversationState.THINKING,
            ConversationState.SPEAKING,
            ConversationState.EXECUTING,
            ConversationState.WAITING_FOR_APPROVAL,
            ConversationState.RECOVERING,
            ConversationState.FAILED,
            ConversationState.INTERRUPTED,
            ConversationState.IDLE
        )

        for (st in states) {
            AmbientExperienceCoordinator.transitionTo(st)
            assertEquals(st, AmbientExperienceCoordinator.conversationState.value)
        }
    }

    @Test
    fun testExecutionLockPreventsConcurrencyCollision() {
        val acquired1 = AmbientExperienceCoordinator.acquireExecutionLock("MissionA")
        assertTrue("First lock should succeed", acquired1)
        assertEquals(ConversationState.EXECUTING, AmbientExperienceCoordinator.conversationState.value)

        val acquired2 = AmbientExperienceCoordinator.acquireExecutionLock("MissionB")
        assertFalse("Second concurrent lock should be rejected", acquired2)

        AmbientExperienceCoordinator.releaseExecutionLock("MissionA")
        assertEquals(ConversationState.IDLE, AmbientExperienceCoordinator.conversationState.value)

        val acquiredAfterRelease = AmbientExperienceCoordinator.acquireExecutionLock("MissionB")
        assertTrue("Lock should now succeed after release", acquiredAfterRelease)
    }

    @Test
    fun testRegisterAndUpdateMissionProgress() {
        val mission = OngoingMissionInfo(
            missionId = "m_101",
            title = "Export Reports",
            currentStepDescription = "Generating PDF",
            progressFraction = 0.25f
        )
        AmbientExperienceCoordinator.registerMission(mission)

        val active = AmbientExperienceCoordinator.activeMissions.value
        assertEquals(1, active.size)
        assertEquals("Generating PDF", active["m_101"]?.currentStepDescription)

        AmbientExperienceCoordinator.updateMissionProgress("m_101", "Compressing archive", 0.75f)
        val updated = AmbientExperienceCoordinator.activeMissions.value["m_101"]
        assertEquals("Compressing archive", updated?.currentStepDescription)
        assertEquals(0.75f, updated?.progressFraction ?: 0f, 0.001f)
    }

    @Test
    fun testCancelSpecificMissionInvokesCallback() {
        var callbackFired = false
        val mission = OngoingMissionInfo(
            missionId = "m_202",
            title = "Backup to Drive",
            currentStepDescription = "Uploading chunk 3",
            progressFraction = 0.5f,
            isCancellable = true
        )

        AmbientExperienceCoordinator.registerMission(mission) {
            callbackFired = true
        }

        val cancelled = AmbientExperienceCoordinator.cancelMission("m_202")
        assertTrue(cancelled)
        assertTrue(callbackFired)
        assertTrue(AmbientExperienceCoordinator.activeMissions.value.isEmpty())
    }

    @Test
    fun testBargeInCancelsAllCancellableMissionsAndTransitionsToInterrupted() {
        var mission1Cancelled = false
        var mission2Cancelled = false

        AmbientExperienceCoordinator.registerMission(
            OngoingMissionInfo("m_1", "Web Search", "Fetching results", 0.3f, isCancellable = true)
        ) { mission1Cancelled = true }

        AmbientExperienceCoordinator.registerMission(
            OngoingMissionInfo("m_2", "Audio Transcription", "Transcribing", 0.6f, isCancellable = true)
        ) { mission2Cancelled = true }

        AmbientExperienceCoordinator.acquireExecutionLock("m_1")
        AmbientExperienceCoordinator.transitionTo(ConversationState.SPEAKING)

        // Trigger Barge-In
        AmbientExperienceCoordinator.handleUserBargeIn()

        assertEquals(ConversationState.INTERRUPTED, AmbientExperienceCoordinator.conversationState.value)
        assertTrue("Mission 1 should be cancelled", mission1Cancelled)
        assertTrue("Mission 2 should be cancelled", mission2Cancelled)
        assertTrue(AmbientExperienceCoordinator.activeMissions.value.isEmpty())
    }

    @Test
    fun testQuietHoursAudioSuppression() {
        // Daytime: 14:00 (2 PM)
        assertFalse(AmbientExperienceCoordinator.isQuietHours(currentHourOverride = 14))
        assertTrue(AmbientExperienceCoordinator.canPlayAudioOutLoud(currentHourOverride = 14))

        // Nighttime: 23:00 (11 PM)
        assertTrue(AmbientExperienceCoordinator.isQuietHours(currentHourOverride = 23))
        assertFalse(AmbientExperienceCoordinator.canPlayAudioOutLoud(currentHourOverride = 23))

        // Early morning: 04:00 (4 AM)
        assertTrue(AmbientExperienceCoordinator.isQuietHours(currentHourOverride = 4))
        assertFalse(AmbientExperienceCoordinator.canPlayAudioOutLoud(currentHourOverride = 4))
    }
}
