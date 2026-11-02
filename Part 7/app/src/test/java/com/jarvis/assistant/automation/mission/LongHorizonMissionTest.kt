package com.jarvis.assistant.automation.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LongHorizonMissionTest {

    private lateinit var coordinator: LongHorizonMissionCoordinator
    private lateinit var storage: InMemoryCheckpointStorage

    @Before
    fun setUp() {
        storage = InMemoryCheckpointStorage()
        coordinator = LongHorizonMissionCoordinator(storage)
    }


    @Test
    fun `older checkpoint never discards newer non idempotent replay guards`() {
        val storage = InMemoryCheckpointStorage()
        val engine = LongHorizonMissionCoordinator(storage)
        val step = MissionStep(stepId = "send", title = "Send", toolName = "send_message", isIdempotent = false)
        val milestone = MilestoneDefinition(id = "m", title = "Send", steps = listOf(step))
        val mission = engine.createMission("Send once", listOf(milestone))
        engine.startOrResume(mission.missionId)
        val oldCheckpoint = engine.createCheckpoint(mission.missionId, "Before action")
        engine.recordStepExecution(mission.missionId, "m", "send", true, true)
        // Simulate a stale checkpoint being selected after a partial checkpoint-store write.
        storage.saveCheckpoint(oldCheckpoint)
        val restored = engine.restoreFromLatestCheckpoint(mission.missionId)
        assertTrue(restored.executedNonIdempotentStepIds.contains("send"))
        assertTrue(engine.checkStepEligibility(mission.missionId, "m", step) is StepExecutionEligibility.ReplayPrevented)
        assertEquals(LongHorizonStatus.PAUSED, restored.status)
    }

    @Test
    fun `mission state and non-idempotent replay guard survive coordinator recreation`() {
        val step = MissionStep(
            stepId = "transfer_once",
            title = "Transfer",
            toolName = "transfer",
            isIdempotent = false
        )
        val mission = coordinator.createMission(
            "Persistent mission",
            listOf(MilestoneDefinition(id = "billing", title = "Billing", steps = listOf(step)))
        )
        coordinator.startOrResume(mission.missionId)
        coordinator.recordStepExecution(
            mission.missionId,
            "billing",
            step.stepId,
            isSuccess = true,
            isNonIdempotent = true,
            outputVariables = mapOf("receipt" to "R-100")
        )

        val recreatedCoordinator = LongHorizonMissionCoordinator(storage)
        val restored = recreatedCoordinator.getMission(mission.missionId)

        assertNotNull(restored)
        assertEquals("R-100", restored!!.stateVariables["receipt"])
        assertTrue(restored.executedNonIdempotentStepIds.contains(step.stepId))
        assertTrue(
            recreatedCoordinator.checkStepEligibility(mission.missionId, "billing", step)
                is StepExecutionEligibility.ReplayPrevented
        )
    }

    @Test
    fun `milestone dependency blocks execution until prerequisite milestone completes`() {
        val m1 = MilestoneDefinition(
            id = "m1",
            title = "Prepare Environment",
            steps = listOf(MissionStep(stepId = "s1", title = "Check Network", toolName = "network_check"))
        )
        val m2 = MilestoneDefinition(
            id = "m2",
            title = "Download Asset",
            dependsOnMilestoneIds = listOf("m1"),
            steps = listOf(MissionStep(stepId = "s2", title = "Fetch Archive", toolName = "fetch_data"))
        )

        val mission = coordinator.createMission("Setup and Download", listOf(m1, m2))
        coordinator.startOrResume(mission.missionId)

        // Step 2 in Milestone 2 should be blocked because m1 is not completed
        val eligibilityBefore = coordinator.checkStepEligibility(mission.missionId, "m2", m2.steps[0])
        assertTrue(eligibilityBefore is StepExecutionEligibility.BlockedByDependency)

        // Complete step 1 in Milestone 1
        coordinator.recordStepExecution(mission.missionId, "m1", "s1", isSuccess = true, isNonIdempotent = false)

        // Now step 2 in Milestone 2 should be allowed
        val eligibilityAfter = coordinator.checkStepEligibility(mission.missionId, "m2", m2.steps[0])
        assertEquals(StepExecutionEligibility.Allowed, eligibilityAfter)
    }

    @Test
    fun `non-idempotent action cannot be replayed after successful execution`() {
        val step = MissionStep(
            stepId = "send_email_step",
            title = "Send Confirmation Email",
            toolName = "send_email",
            isIdempotent = false
        )
        val step2 = MissionStep(stepId = "verify_receipt", title = "Verify Receipt", toolName = "verify")
        val m1 = MilestoneDefinition(id = "m1", title = "Send Phase", steps = listOf(step, step2))
        val mission = coordinator.createMission("Send Important Notification", listOf(m1))
        coordinator.startOrResume(mission.missionId)

        // Initial check: Allowed
        val initialEligibility = coordinator.checkStepEligibility(mission.missionId, "m1", step)
        assertEquals(StepExecutionEligibility.Allowed, initialEligibility)

        // Execute step successfully
        coordinator.recordStepExecution(mission.missionId, "m1", step.stepId, isSuccess = true, isNonIdempotent = true)

        // Recheck: must be ReplayPrevented
        val replayEligibility = coordinator.checkStepEligibility(mission.missionId, "m1", step)
        assertTrue(replayEligibility is StepExecutionEligibility.ReplayPrevented)
    }

    @Test
    fun `pause and resume flow creates checkpoints and updates status`() {
        val m1 = MilestoneDefinition(
            id = "m1",
            title = "M1",
            steps = listOf(MissionStep(stepId = "s1", title = "S1", toolName = "tool_1"))
        )
        val mission = coordinator.createMission("Pause-Resume Test", listOf(m1))
        coordinator.startOrResume(mission.missionId)
        assertEquals(LongHorizonStatus.EXECUTING, coordinator.getMission(mission.missionId)?.status)

        // Pause
        val paused = coordinator.pause(mission.missionId, "User requested sleep")
        assertEquals(LongHorizonStatus.PAUSED, paused.status)

        val latestCheckpoint = storage.getLatestCheckpoint(mission.missionId)
        assertNotNull(latestCheckpoint)
        assertTrue(latestCheckpoint!!.reason.contains("Paused: User requested sleep"))

        // Resume
        val resumed = coordinator.startOrResume(mission.missionId)
        assertEquals(LongHorizonStatus.EXECUTING, resumed.status)
    }

    @Test
    fun `restoring from checkpoint restores non-idempotent history and paused state`() {
        val step = MissionStep(stepId = "charge_card", title = "Charge Card", toolName = "billing", isIdempotent = false)
        val m1 = MilestoneDefinition(id = "m1", title = "Billing Milestone", steps = listOf(step))
        val mission = coordinator.createMission("Billing Mission", listOf(m1))
        coordinator.startOrResume(mission.missionId)

        coordinator.recordStepExecution(
            mission.missionId,
            "m1",
            "charge_card",
            isSuccess = true,
            isNonIdempotent = true,
            outputVariables = mapOf("tx_id" to "TX_998811")
        )

        // Simulate restore
        val restored = coordinator.restoreFromLatestCheckpoint(mission.missionId)
        assertEquals(LongHorizonStatus.PAUSED, restored.status)
        assertTrue(restored.executedNonIdempotentStepIds.contains("charge_card"))
        assertEquals("TX_998811", restored.stateVariables["tx_id"])
    }

    @Test
    fun `replanning updates milestones and respects maxReplans cap`() {
        val m1 = MilestoneDefinition(id = "m1", title = "M1", steps = emptyList())
        val mission = coordinator.createMission("Replan Test", listOf(m1), maxReplans = 2)

        val replan1 = coordinator.replan(mission.missionId, listOf(MilestoneDefinition(id = "m2", title = "M2")), "Obstacle A")
        assertEquals(1, replan1.replanCount)
        assertEquals(LongHorizonStatus.EXECUTING, replan1.status)

        val replan2 = coordinator.replan(mission.missionId, listOf(MilestoneDefinition(id = "m3", title = "M3")), "Obstacle B")
        assertEquals(2, replan2.replanCount)
        assertEquals(LongHorizonStatus.EXECUTING, replan2.status)

        // Third replan exceeds maxReplans (2)
        val replan3 = coordinator.replan(mission.missionId, listOf(MilestoneDefinition(id = "m4", title = "M4")), "Obstacle C")
        assertEquals(LongHorizonStatus.FAILED, replan3.status)
        assertTrue(replan3.failureReason!!.contains("Max replan limit exceeded"))
    }

    @Test
    fun `step count cap prevents runaway execution loops`() {
        val step = MissionStep(stepId = "poll_step", title = "Polling", toolName = "poll", isIdempotent = true)
        val step2 = MissionStep(stepId = "poll_step_2", title = "Polling 2", toolName = "poll", isIdempotent = true)
        val step3 = MissionStep(stepId = "poll_step_3", title = "Polling 3", toolName = "poll", isIdempotent = true)
        val m1 = MilestoneDefinition(id = "m1", title = "Poll Milestone", steps = listOf(step, step2, step3))
        val mission = coordinator.createMission("Poll Loop", listOf(m1), maxTotalSteps = 2)
        coordinator.startOrResume(mission.missionId)

        coordinator.recordStepExecution(mission.missionId, "m1", "poll_step", isSuccess = true, isNonIdempotent = false)
        coordinator.recordStepExecution(mission.missionId, "m1", "poll_step_2", isSuccess = true, isNonIdempotent = false)

        val eligibility = coordinator.checkStepEligibility(mission.missionId, "m1", step3)
        assertTrue(eligibility is StepExecutionEligibility.LimitExceeded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `detects circular milestone dependencies at creation`() {
        val m1 = MilestoneDefinition(id = "m1", title = "M1", dependsOnMilestoneIds = listOf("m2"))
        val m2 = MilestoneDefinition(id = "m2", title = "M2", dependsOnMilestoneIds = listOf("m1"))
        coordinator.createMission("Cyclic Mission", listOf(m1, m2))
    }
}
