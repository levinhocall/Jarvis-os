package com.jarvis.assistant.automation.skills

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.mission.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object SkillForgeManager {
    private const val TAG = "SkillForgeManager"
    private val skillRegistry = ConcurrentHashMap<String, SkillDefinition>()
    private val taskFrequencyCounter = ConcurrentHashMap<String, Int>()

    init {
        // Register default high-value built-in skills
        registerDefaultSkills()
    }

    private fun registerDefaultSkills() {
        val briefingSkill = SkillDefinition(
            id = "skill_morning_briefing",
            name = "Morning Status Briefing",
            purpose = "Reads battery, network, unread messages, and current time",
            steps = listOf(
                MissionStep(
                    title = "Collect system telemetry",
                    description = "Inspect battery and network status",
                    toolName = "device_control",
                    parameters = mapOf("raw" to "battery status"),
                    riskLevel = MissionRisk.LOW
                )
            ),
            riskClassification = MissionRisk.LOW,
            requiresApproval = false,
            isUserCreated = false
        )

        val codingSkill = SkillDefinition(
            id = "skill_coding_environment",
            name = "Coding Workspace Setup",
            purpose = "Sets volume to 40% and launches developer repository or Termux",
            steps = listOf(
                MissionStep(
                    title = "Set coding audio volume",
                    description = "Adjust audio volume for focused work",
                    toolName = "device_control",
                    parameters = mapOf("raw" to "volume 40 percent karo"),
                    riskLevel = MissionRisk.LOW
                ),
                MissionStep(
                    title = "Open developer repository",
                    description = "Launch browser to GitHub or Termux",
                    toolName = "search_web",
                    parameters = mapOf("query" to "https://github.com/levinhocall/Jarvis-os"),
                    riskLevel = MissionRisk.LOW
                )
            ),
            riskClassification = MissionRisk.LOW,
            requiresApproval = false,
            isUserCreated = false
        )

        skillRegistry[briefingSkill.id] = briefingSkill
        skillRegistry[codingSkill.id] = codingSkill
    }

    fun listSkills(): List<SkillDefinition> = skillRegistry.values.toList()

    fun getSkill(id: String): SkillDefinition? = skillRegistry[id]

    fun saveSkill(skill: SkillDefinition) {
        skillRegistry[skill.id] = skill
        Log.i(TAG, "Saved skill: '${skill.name}' (${skill.id})")
    }

    fun deleteSkill(id: String): Boolean {
        val removed = skillRegistry.remove(id) != null
        if (removed) Log.i(TAG, "Deleted skill: $id")
        return removed
    }

    /**
     * Tracks task occurrences and suggests a reusable skill when a task pattern repeats >= 3 times.
     */
    fun trackTaskAndSuggestSkill(taskPattern: String, sampleSteps: List<MissionStep>): SkillDefinition? {
        val clean = taskPattern.trim().lowercase(Locale.ROOT)
        val count = taskFrequencyCounter.compute(clean) { _, v -> (v ?: 0) + 1 } ?: 1

        if (count == 3) {
            val suggested = SkillDefinition(
                name = taskPattern.replaceFirstChar { it.uppercase() },
                purpose = "Automated reusable workflow suggested from 3 repeated runs",
                steps = sampleSteps,
                riskClassification = if (sampleSteps.any { it.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL }) MissionRisk.HIGH_CONSEQUENTIAL else MissionRisk.LOW,
                requiresApproval = sampleSteps.any { it.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL },
                isUserCreated = true
            )
            saveSkill(suggested)
            Log.i(TAG, "Promoted repeated task '$clean' into new Skill: '${suggested.name}'")
            return suggested
        }
        return null
    }

    /**
     * Executes a skill through the central MissionEngine, validating parameters and permissions.
     */
    suspend fun executeSkill(
        context: Context? = null,
        skillId: String,
        arguments: Map<String, String> = emptyMap()
    ): SkillExecutionResult = withContext(Dispatchers.Default) {
        val skill = skillRegistry[skillId]
            ?: return@withContext SkillExecutionResult(false, "Skill not found: $skillId", skillName = "Unknown")

        // Validate required parameters
        for (param in skill.parameters) {
            if (param.isRequired && !arguments.containsKey(param.name) && param.defaultValue == null) {
                return@withContext SkillExecutionResult(
                    success = false,
                    message = "Missing required parameter: '${param.name}' (${param.description})",
                    skillName = skill.name
                )
            }
        }

        Log.i(TAG, "Executing skill '${skill.name}' via MissionEngine")

        // Dispatch through MissionEngine
        val missionId = if (context != null) {
            MissionEngine.startMission(context, skill.name, listOf("Skill: ${skill.name}")).missionId
        } else {
            MissionEngine.planMission(skill.name, listOf("Skill: ${skill.name}")).missionId
        }

        // Update skill telemetry
        val updated = skill.copy(
            executionCount = skill.executionCount + 1,
            lastExecutedAt = System.currentTimeMillis()
        )
        skillRegistry[skill.id] = updated

        SkillExecutionResult(
            success = true,
            message = "Skill '${skill.name}' dispatched to MissionEngine. Mission ID: $missionId",
            skillName = skill.name
        )
    }
}
