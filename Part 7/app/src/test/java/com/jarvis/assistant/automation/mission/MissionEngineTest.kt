package com.jarvis.assistant.automation.mission

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MissionEngineTest {
    private val storedMissions = mutableMapOf<String, com.jarvis.assistant.data.mission.MissionEntity>()
    private val testMissionDao = object : com.jarvis.assistant.data.mission.MissionDao {
        override suspend fun insertOrUpdate(mission: com.jarvis.assistant.data.mission.MissionEntity) {
            storedMissions[mission.id] = mission
        }
        override suspend fun getMissionById(id: String) = storedMissions[id]
        override suspend fun getNonTerminalMissions() = storedMissions.values.filter {
            it.status !in setOf("COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED")
        }.sortedByDescending { it.updatedAt }
        override suspend fun getAllMissions() = storedMissions.values.sortedByDescending { it.updatedAt }
        override suspend fun delete(mission: com.jarvis.assistant.data.mission.MissionEntity) {
            storedMissions.remove(mission.id)
        }
    }

    @Before
    fun setUp() {
        storedMissions.clear()
        MissionEngine.missionDao = testMissionDao
        runBlocking {
            MissionEngine.toolExecutor = null
            MissionEngine.llmPlanner = null
            MissionEngine.activeMission.value?.let { current ->
                when (current.status) {
                    MissionOverallStatus.UNVERIFIED -> MissionEngine.acknowledgeUnverifiedMission(current.missionId)
                    else -> if (!current.isFinished) {
                        MissionEngine.cancelMission(current.missionId, "Test cleanup")
                        kotlinx.coroutines.withTimeout(3_000L) {
                            while (MissionEngine.activeMission.value?.status !in setOf(
                                    MissionOverallStatus.CANCELLED,
                                    MissionOverallStatus.COMPLETED,
                                    MissionOverallStatus.FAILED,
                                    MissionOverallStatus.UNVERIFIED
                                )
                            ) {
                                kotlinx.coroutines.delay(20L)
                            }
                        }
                    }
                }
            }
        }
    }
    @Test
    fun testMissionDoesNotExecuteWhenDurableCheckpointIsUnavailable() = runBlocking {
        MissionEngine.missionDao = null
        var executed = false
        MissionEngine.toolExecutor = { _, _ ->
            executed = true
            true to MissionVerificationEvidence(EvidenceType.TOOL_RESULT_VERIFIED, true, "Test action returned")
        }

        try {
            MissionEngine.startMission(null, "Turn flashlight on")
            kotlinx.coroutines.withTimeout(3_000L) {
                while (MissionEngine.activeMission.value?.status != MissionOverallStatus.FAILED) {
                    kotlinx.coroutines.delay(20L)
                }
            }
            assertFalse(executed)
            assertEquals(MissionOverallStatus.FAILED, MissionEngine.activeMission.value?.status)
            assertTrue(MissionEngine.activeMission.value?.outcomeSummary?.contains("not attempted", ignoreCase = true) == true)
        } finally {
            MissionEngine.missionDao = testMissionDao
            MissionEngine.toolExecutor = null
        }
    }
    @Test
    fun testPlanMission_unambiguousRequest_createsBoundedPlan() {
        val mission = MissionEngine.planMission("Search information for quantum computing")
        assertEquals(MissionOverallStatus.PLANNING, mission.status)
        assertEquals(1, mission.steps.size)
        assertEquals("search_web", mission.steps.first().toolName)
        assertEquals("quantum computing", mission.steps.first().parameters["query"])
    }

    @Test
    fun testPlanMission_deviceControlIsNotAutomaticallyReplaySafe() {
        val mission = MissionEngine.planMission("Turn flashlight on")
        assertEquals("device_control", mission.steps.single().toolName)
        assertFalse(mission.steps.single().isIdempotent)
    }

    @Test
    fun testPlanMission_ambiguousWhatsApp_requiresClarification() {
        val mission = MissionEngine.planMission("Send WhatsApp message")
        assertEquals(MissionOverallStatus.CLARIFICATION_REQUIRED, mission.status)
        assertNotNull(mission.clarificationRequest)
        assertEquals("contact", mission.clarificationRequest?.missingField)
    }

    @Test
    fun testPlanMission_ambiguousCall_requiresClarification() {
        val mission = MissionEngine.planMission("call")
        assertEquals(MissionOverallStatus.CLARIFICATION_REQUIRED, mission.status)
        assertNotNull(mission.clarificationRequest)
        assertEquals("contact_name", mission.clarificationRequest?.missingField)
    }

    @Test
    fun testPlanMission_userDirectedMessage_runsAutonomously() {
        val mission = MissionEngine.planMission("Send WhatsApp to Rahul saying I will be late")
        assertEquals(MissionApprovalState.NOT_REQUIRED, mission.approvalState)
        assertTrue(mission.status != MissionOverallStatus.WAITING_FOR_APPROVAL)
        assertEquals(1, mission.steps.size)
        assertEquals("whatsapp_send", mission.steps[0].toolName)
        assertEquals(MissionRisk.MEDIUM, mission.steps[0].riskLevel)
    }

    @Test
    fun testToolRegistry_validatesMissingParameters() {
        val result = ToolRegistry.validate("search_web", emptyMap())
        assertTrue(result is ToolValidationResult.MissingParameters)
        val missing = (result as ToolValidationResult.MissingParameters).missingParams
        assertTrue(missing.contains("query"))
    }

    @Test
    fun testToolRegistry_validatesUnknownTool() {
        val result = ToolRegistry.validate("non_existent_tool_xyz", emptyMap())
        assertTrue(result is ToolValidationResult.UnknownTool)
    }

    @Test
    fun testMissionExecution_truthfulReporting_verifiedSuccess() = runBlocking {
        // Mock tool executor returning verified evidence
        MissionEngine.toolExecutor = { _, _ ->
            Pair(true, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, "Verified successfully"))
        }

        val mission = MissionEngine.startMission(null, "search for Kotlin coroutines")
        // Wait for execution to finalize
        kotlinx.coroutines.delay(500L)

        val active = MissionEngine.activeMission.value
        assertNotNull(active)
        assertEquals(MissionOverallStatus.COMPLETED, active?.status)
        assertTrue(active?.outcomeSummary?.contains("fully verified", ignoreCase = true) == true)
        assertEquals(0, active?.unverifiedDisclaimers?.size)
    }

    @Test
    fun testMissionExecution_truthfulReporting_unverifiedActionIsNotClaimedAsCompleted() = runBlocking {
        // Mock tool executor returning unverified evidence (isVerified = false)
        MissionEngine.toolExecutor = { _, _ ->
            Pair(true, MissionVerificationEvidence(EvidenceType.UNVERIFIED, false, "Physical confirmation missing"))
        }

        val mission = MissionEngine.startMission(null, "search for Android 15 release")
        kotlinx.coroutines.delay(500L)

        val active = MissionEngine.activeMission.value
        assertNotNull(active)
        // Must NEVER claim verified COMPLETED when evidence is unverified!
        assertEquals(MissionOverallStatus.UNVERIFIED, active?.status)
        assertTrue(active?.unverifiedDisclaimers?.isNotEmpty() == true)
    }

    @Test
    @org.junit.Ignore("WhatsApp send is autonomous now; rewrite this approval flow against a still-gated tool (payments/delete).")
    fun testMissionExecution_rejectionCancelsMission() = runBlocking {
        val mission = MissionEngine.startMission(null, "Send WhatsApp to Tony saying Suit ready")
        assertEquals(MissionOverallStatus.WAITING_FOR_APPROVAL, mission.status)

        // User rejects approval
        MissionEngine.resolveApproval(mission.missionId, approved = false, context = null)

        val active = MissionEngine.activeMission.value
        assertEquals(MissionOverallStatus.CANCELLED, active?.status)
        assertEquals(MissionApprovalState.REJECTED, active?.approvalState)
        assertTrue(active?.outcomeSummary?.contains("declined", ignoreCase = true) == true)
    }

    @Test
    @org.junit.Ignore("Depended on the old whatsapp_prepare + approval two-step flow; rewrite with a multi-step mission.")
    fun testMissionPauseAndResume_occursBetweenVerifiedSteps() = runBlocking {
        val firstStepStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        MissionEngine.toolExecutor = { step, _ ->
            if (step.toolName == "whatsapp_prepare") {
                firstStepStarted.complete(Unit)
                kotlinx.coroutines.delay(180L)
            }
            Pair(true, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, "Verified ${step.toolName}"))
        }

        val mission = MissionEngine.startMission(
            null,
            "Send WhatsApp to Tony saying Suit ready"
        )
        assertEquals(MissionOverallStatus.WAITING_FOR_APPROVAL, mission.status)

        MissionEngine.resolveApproval(mission.missionId, approved = true, context = null)
        firstStepStarted.await()
        assertTrue(MissionEngine.pauseMission(mission.missionId))

        kotlinx.coroutines.withTimeout(3_000L) {
            while (MissionEngine.activeMission.value?.status != MissionOverallStatus.PAUSED) {
                kotlinx.coroutines.delay(20L)
            }
        }
        val paused = MissionEngine.activeMission.value!!
        assertEquals(1, paused.steps.count { it.status == MissionStepStatus.COMPLETED })
        assertTrue(paused.steps.any { it.toolName == "whatsapp_send" && it.status == MissionStepStatus.PENDING })

        assertTrue(MissionEngine.resumeMission(null, mission.missionId))
        kotlinx.coroutines.withTimeout(3_000L) {
            while (MissionEngine.activeMission.value?.isFinished != true) {
                kotlinx.coroutines.delay(20L)
            }
        }
        // The prepared step must not repeat; the pending send cannot execute without Accessibility.
        val resumed = MissionEngine.activeMission.value!!
        assertEquals(MissionOverallStatus.PARTIALLY_COMPLETED, resumed.status)
        assertEquals(1, resumed.steps.count { it.status == MissionStepStatus.COMPLETED })
        assertEquals(MissionStepStatus.BLOCKED, resumed.steps.single { it.toolName == "whatsapp_send" }.status)
    }

    @Test
    @org.junit.Ignore("Depended on the old whatsapp_prepare + approval two-step flow; rewrite with a multi-step mission.")
    fun testMissionCancellation_inFlight() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val executedTools = mutableListOf<String>()
        MissionEngine.toolExecutor = { step, _ ->
            executedTools.add(step.toolName)
            started.complete(Unit)
            kotlinx.coroutines.delay(500L)
            Pair(true, MissionVerificationEvidence(EvidenceType.TEXT_MATCH, true, "Prepared"))
        }
        val mission = MissionEngine.startMission(null, "Send WhatsApp to Tony saying Cancel test")
        MissionEngine.resolveApproval(mission.missionId, approved = true, context = null)
        kotlinx.coroutines.withTimeout(3_000L) { started.await() }
        MissionEngine.cancelMission(mission.missionId, "User said stop")
        kotlinx.coroutines.withTimeout(3_000L) {
            while (MissionEngine.activeMission.value?.status != MissionOverallStatus.CANCELLED) {
                kotlinx.coroutines.delay(20L)
            }
        }
        assertEquals(listOf("whatsapp_prepare"), executedTools)
        assertEquals(MissionOverallStatus.CANCELLED, MissionEngine.activeMission.value?.status)
    }

    @Test
    fun testPlanMission_whatsappVideoCallRunsAutonomouslyAndKeepsProfileChoice() {
        val mission = MissionEngine.planMission(
            "WhatsApp video call to Rahul",
            userConstraints = listOf("app_number=2")
        )

        assertEquals(MissionApprovalState.NOT_REQUIRED, mission.approvalState)
        assertTrue(mission.status != MissionOverallStatus.WAITING_FOR_APPROVAL)
        assertEquals("whatsapp_call", mission.steps.single().toolName)
        assertEquals("video", mission.steps.single().parameters["call_type"])
        assertEquals(2, mission.steps.single().parameters["app_number"])
        assertEquals(MissionRisk.MEDIUM, mission.steps.single().riskLevel)
    }

    @Test
    fun testPlanMission_phoneCallKeepsExplicitContactIndex() {
        val mission = MissionEngine.planMission(
            "call Rahul",
            userConstraints = listOf("contact_index=2")
        )

        assertTrue(mission.status != MissionOverallStatus.WAITING_FOR_APPROVAL)
        assertEquals(2, mission.steps.single().parameters["contact_index"])
    }

    @Test
    fun testPlanMission_phoneCall_runsAutonomously() {
        val mission = MissionEngine.planMission("call Rahul")

        assertEquals(MissionApprovalState.NOT_REQUIRED, mission.approvalState)
        assertTrue(mission.status != MissionOverallStatus.WAITING_FOR_APPROVAL)
        assertEquals(MissionRisk.MEDIUM, mission.steps.single().riskLevel)
    }
}
