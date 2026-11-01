package com.jarvis.assistant.automation.mission

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MissionEngineTest {

    @Before
    fun setUp() = runBlocking {
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

    @Test
    fun testPlanMission_unambiguousRequest_createsBoundedPlan() {
        val mission = MissionEngine.planMission("Search information for quantum computing")
        assertEquals(MissionOverallStatus.PLANNING, mission.status)
        assertEquals(1, mission.steps.size)
        assertEquals("search_web", mission.steps.first().toolName)
        assertEquals("quantum computing", mission.steps.first().parameters["query"])
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
    fun testPlanMission_consequentialAction_requiresApproval() {
        val mission = MissionEngine.planMission("Send WhatsApp to Rahul saying I will be late")
        assertEquals(MissionOverallStatus.WAITING_FOR_APPROVAL, mission.status)
        assertEquals(MissionApprovalState.PENDING_APPROVAL, mission.approvalState)
        assertEquals(2, mission.steps.size)
        assertEquals("whatsapp_prepare", mission.steps[0].toolName)
        assertEquals("whatsapp_send", mission.steps[1].toolName)
        assertEquals(MissionRisk.HIGH_CONSEQUENTIAL, mission.steps[1].riskLevel)
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
            while (MissionEngine.activeMission.value?.status != MissionOverallStatus.COMPLETED) {
                kotlinx.coroutines.delay(20L)
            }
        }
        assertEquals(MissionOverallStatus.COMPLETED, MissionEngine.activeMission.value?.status)
    }
    @Test
    fun testMissionCancellation_inFlight() = runBlocking {
        // Mock step that simulates latency
        MissionEngine.toolExecutor = { _, _ ->
            kotlinx.coroutines.delay(500L)
            Pair(true, MissionVerificationEvidence(EvidenceType.EXTERNAL_API_ACKNOWLEDGED, true, "OK"))
        }

        val mission = MissionEngine.startMission(null, "play AC/DC Back in Black")
        MissionEngine.cancelMission(mission.missionId, "User said stop")

        kotlinx.coroutines.withTimeout(3_000L) {
            while (MissionEngine.activeMission.value?.status != MissionOverallStatus.CANCELLED) {
                kotlinx.coroutines.delay(20L)
            }
        }
        val active = MissionEngine.activeMission.value
        assertEquals(MissionOverallStatus.CANCELLED, active?.status)
        assertTrue(active?.steps?.any { it.verificationEvidence?.isVerified == true } == true ||
            active?.steps?.any { it.status == MissionStepStatus.CANCELLED } == true)
    }

    @Test
    fun testPlanMission_whatsappVideoCallRequiresApprovalAndKeepsProfileChoice() {
        val mission = MissionEngine.planMission(
            "WhatsApp video call to Rahul",
            userConstraints = listOf("app_number=2")
        )

        assertEquals(MissionOverallStatus.WAITING_FOR_APPROVAL, mission.status)
        assertEquals(MissionApprovalState.PENDING_APPROVAL, mission.approvalState)
        assertEquals("whatsapp_call", mission.steps.single().toolName)
        assertEquals("video", mission.steps.single().parameters["call_type"])
        assertEquals(2, mission.steps.single().parameters["app_number"])
        assertEquals(MissionRisk.HIGH_CONSEQUENTIAL, mission.steps.single().riskLevel)
    }

    @Test
    fun testPlanMission_phoneCallKeepsExplicitContactIndex() {
        val mission = MissionEngine.planMission(
            "call Rahul",
            userConstraints = listOf("contact_index=2")
        )

        assertEquals(MissionOverallStatus.WAITING_FOR_APPROVAL, mission.status)
        assertEquals(2, mission.steps.single().parameters["contact_index"])
    }

    @Test
    fun testPlanMission_phoneCall_requiresExplicitApproval() {
        val mission = MissionEngine.planMission("call Rahul")

        assertEquals(MissionOverallStatus.WAITING_FOR_APPROVAL, mission.status)
        assertEquals(MissionApprovalState.PENDING_APPROVAL, mission.approvalState)
        assertEquals(MissionRisk.HIGH_CONSEQUENTIAL, mission.steps.single().riskLevel)
    }
}
