package com.jarvis.assistant.automation.skills

import com.jarvis.assistant.automation.mission.MissionRisk
import com.jarvis.assistant.automation.mission.MissionStep
import java.util.UUID

data class SkillParameter(
    val name: String,
    val type: String = "string",
    val isRequired: Boolean = true,
    val defaultValue: String? = null,
    val description: String = ""
)

data class SkillDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val purpose: String,
    val parameters: List<SkillParameter> = emptyList(),
    val preconditions: List<String> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val steps: List<MissionStep> = emptyList(),
    val successCriteria: String = "",
    val riskClassification: MissionRisk = MissionRisk.LOW,
    val requiresApproval: Boolean = false,
    val timeoutSeconds: Int = 60,
    val executionCount: Int = 0,
    val lastExecutedAt: Long? = null,
    val isUserCreated: Boolean = true
)

data class SkillExecutionResult(
    val success: Boolean,
    val message: String,
    val skillName: String,
    val stepResults: List<String> = emptyList(),
    val failureStep: String? = null
)
