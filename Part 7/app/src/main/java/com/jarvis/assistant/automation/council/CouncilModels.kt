package com.jarvis.assistant.automation.council

import com.jarvis.assistant.automation.mission.EvidenceType
import com.jarvis.assistant.automation.mission.MissionRisk
import com.jarvis.assistant.automation.mission.MissionStep
import com.jarvis.assistant.automation.mission.MissionVerificationEvidence
import java.util.UUID

enum class AgentRole {
    LEAD_SYNTHESIZER,
    PLANNER,
    RESEARCHER,
    EXECUTOR,
    VERIFIER
}

data class AgentHandoff(
    val handoffId: String = UUID.randomUUID().toString(),
    val fromRole: AgentRole,
    val toRole: AgentRole,
    val taskDescription: String,
    val inputPayload: Map<String, Any?> = emptyMap(),
    val outputPayload: Map<String, Any?> = emptyMap(),
    val delegationDepth: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
)

data class IndependentVerificationReport(
    val isVerified: Boolean,
    val verifiedEvidence: MissionVerificationEvidence,
    val verifierNotes: String,
    val verifierTimestamp: Long = System.currentTimeMillis()
)

data class CouncilExecutionOutcome(
    val isSuccess: Boolean,
    val goal: String,
    val leadResponse: String,
    val handoffHistory: List<AgentHandoff>,
    val executionStepsCount: Int,
    val isVerifiedIndependently: Boolean,
    val failureReason: String? = null
)
