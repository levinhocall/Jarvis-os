package com.jarvis.assistant.automation.mission

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistentMissionCheckpointStorageInstrumentedTest {
    @Test
    fun encryptedStorageRestoresMissionAndReplayGuardAcrossCoordinatorInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PersistentMissionCheckpointStorage(context)
        val step = MissionStep(
            stepId = "external_payment",
            title = "External payment",
            toolName = "payment",
            isIdempotent = false
        )
        val coordinator = LongHorizonMissionCoordinator(storage)
        val mission = coordinator.createMission(
            "Persist payment workflow",
            listOf(MilestoneDefinition(id = "payment", title = "Payment", steps = listOf(step)))
        )
        coordinator.startOrResume(mission.missionId)
        coordinator.recordStepExecution(
            mission.missionId,
            "payment",
            step.stepId,
            isSuccess = true,
            isNonIdempotent = true,
            outputVariables = mapOf("receipt" to "receipt-verified")
        )

        val recreated = LongHorizonMissionCoordinator(storage)
        val restored = recreated.getMission(mission.missionId)

        assertNotNull(restored)
        assertEquals("receipt-verified", restored!!.stateVariables["receipt"])
        assertEquals(LongHorizonStatus.PAUSED, restored.status)
        assertTrue(
            recreated.checkStepEligibility(mission.missionId, "payment", step)
                is StepExecutionEligibility.ReplayPrevented
        )
        storage.clear(mission.missionId)
    }
}
