package com.jarvis.assistant.automation.skills

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.mission.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
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
                    parameters = mapOf("command" to "battery status"),
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
                    parameters = mapOf("command" to "volume 40 percent karo"),
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

    fun listSkills(includeDisabled: Boolean = true): List<SkillDefinition> {
        return if (includeDisabled) {
            skillRegistry.values.toList()
        } else {
            skillRegistry.values.filter { it.isEnabled }.toList()
        }
    }

    fun getSkill(id: String): SkillDefinition? = skillRegistry[id]

    fun saveSkill(skill: SkillDefinition) {
        skillRegistry[skill.id] = skill
        Log.i(TAG, "Saved skill: '${skill.name}' (${skill.id})")
    }

    fun setSkillEnabled(id: String, enabled: Boolean): Boolean {
        val skill = skillRegistry[id] ?: return false
        skillRegistry[id] = skill.copy(isEnabled = enabled)
        Log.i(TAG, "Skill $id enabled state updated to: $enabled")
        return true
    }

    fun deleteSkill(id: String): Boolean {
        val removed = skillRegistry.remove(id) != null
        if (removed) Log.i(TAG, "Deleted skill: $id")
        return removed
    }

    /**
     * Validates a skill definition against the ToolRegistry before execution.
     * Prevents skills from executing undeclared or unsafe operations.
     */
    fun validateSkill(skill: SkillDefinition, context: Context? = null): SkillValidationResult {
        val errors = mutableListOf<String>()

        if (skill.name.isBlank()) {
            errors.add("Skill name cannot be blank")
        }
        if (skill.steps.isEmpty()) {
            errors.add("Skill '${skill.name}' must contain at least one step")
        }

        for ((index, step) in skill.steps.withIndex()) {
            val tool = ToolRegistry.getTool(step.toolName)
            if (tool == null) {
                errors.add("Step ${index + 1} ('${step.title}') references undeclared tool '${step.toolName}'")
            } else {
                if (step.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL && skill.riskClassification != MissionRisk.HIGH_CONSEQUENTIAL) {
                    errors.add("Step ${index + 1} has HIGH_CONSEQUENTIAL risk, but skill risk classification is ${skill.riskClassification}")
                }
            }
        }

        return if (errors.isEmpty()) SkillValidationResult.Valid else SkillValidationResult.Invalid(errors)
    }

    /**
     * Binds arguments to step parameters, titles, and descriptions, resolving ${param_name} placeholders.
     */
    fun bindParameters(step: MissionStep, arguments: Map<String, String>): MissionStep {
        val boundParams = step.parameters.mapValues { (_, value) ->
            var strVal = value.toString()
            for ((k, v) in arguments) {
                strVal = strVal.replace("\${$k}", v)
            }
            strVal
        }
        var boundTitle = step.title
        var boundDesc = step.description
        for ((k, v) in arguments) {
            boundTitle = boundTitle.replace("\${$k}", v)
            boundDesc = boundDesc.replace("\${$k}", v)
        }
        return step.copy(
            title = boundTitle,
            description = boundDesc,
            parameters = boundParams
        )
    }

    /**
     * Performs a non-mutating dry run of a skill, validating structure and resolving parameters.
     */
    fun dryRunSkill(
        skillId: String,
        arguments: Map<String, String> = emptyMap()
    ): SkillDryRunResult {
        val skill = skillRegistry[skillId]
            ?: return SkillDryRunResult(
                isValid = false,
                skillName = "Unknown",
                resolvedSteps = emptyList(),
                riskClassification = MissionRisk.LOW,
                requiresApproval = false,
                validationError = "Skill not found: $skillId"
            )

        // Resolve arguments with defaults
        val resolvedArgs = mutableMapOf<String, String>()
        for (param in skill.parameters) {
            val provided = arguments[param.name] ?: param.defaultValue
            if (provided != null) {
                resolvedArgs[param.name] = provided
            } else if (param.isRequired) {
                return SkillDryRunResult(
                    isValid = false,
                    skillName = skill.name,
                    resolvedSteps = emptyList(),
                    riskClassification = skill.riskClassification,
                    requiresApproval = skill.requiresApproval,
                    validationError = "Missing required parameter: '${param.name}' (${param.description})"
                )
            }
        }

        // Validate skill structure and tools
        val validation = validateSkill(skill)
        if (validation is SkillValidationResult.Invalid) {
            return SkillDryRunResult(
                isValid = false,
                skillName = skill.name,
                resolvedSteps = emptyList(),
                riskClassification = skill.riskClassification,
                requiresApproval = skill.requiresApproval,
                validationError = validation.reasons.joinToString("; ")
            )
        }

        // Bind parameters across all steps
        val resolvedSteps = skill.steps.map { step ->
            bindParameters(step, resolvedArgs)
        }

        val hasConsequential = resolvedSteps.any { it.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL }
        val effectiveRisk = if (hasConsequential) MissionRisk.HIGH_CONSEQUENTIAL else skill.riskClassification
        val requiresApproval = skill.requiresApproval || hasConsequential

        return SkillDryRunResult(
            isValid = true,
            skillName = skill.name,
            resolvedSteps = resolvedSteps,
            riskClassification = effectiveRisk,
            requiresApproval = requiresApproval,
            warnings = if (!skill.isEnabled) listOf("Skill is currently marked as disabled") else emptyList()
        )
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

        if (!skill.isEnabled) {
            return@withContext SkillExecutionResult(
                success = false,
                message = "Skill '${skill.name}' is currently disabled.",
                skillName = skill.name
            )
        }

        val dryRun = dryRunSkill(skillId, arguments)
        if (!dryRun.isValid) {
            return@withContext SkillExecutionResult(
                success = false,
                message = dryRun.validationError ?: "Skill validation failed",
                skillName = skill.name
            )
        }

        if (context == null && MissionEngine.toolExecutor == null) {
            return@withContext SkillExecutionResult(
                success = false,
                message = "Android execution context is unavailable; the skill was not started.",
                skillName = skill.name
            )
        }

        val mission = MissionEngine.startPreparedMission(
            context = context,
            goal = skill.name,
            userConstraints = listOf("Skill: ${skill.name}"),
            steps = dryRun.resolvedSteps,
            requiresApproval = dryRun.requiresApproval || dryRun.riskClassification == MissionRisk.HIGH_CONSEQUENTIAL,
            approvalPrompt = if (dryRun.requiresApproval) {
                "Skill '${skill.name}' contains consequential actions. Do you want to proceed?"
            } else null
        )
        if (mission.status == MissionOverallStatus.FAILED) {
            return@withContext SkillExecutionResult(
                success = false,
                message = mission.outcomeSummary ?: "Mission could not be started.",
                skillName = skill.name
            )
        }

        // Count only accepted mission submissions; completion is reported by MissionEngine's
        // persisted mission status and verification evidence, never by this enqueue response.
        skillRegistry[skill.id] = skill.copy(
            executionCount = skill.executionCount + 1,
            lastExecutedAt = System.currentTimeMillis()
        )

        val message = if (mission.status == MissionOverallStatus.WAITING_FOR_APPROVAL) {
            "Skill '${skill.name}' is awaiting explicit approval. No actions have been executed. Mission ID: ${mission.missionId}"
        } else {
            "Skill '${skill.name}' was submitted to MissionEngine. Execution is in progress; completion is not yet verified. Mission ID: ${mission.missionId}"
        }
        SkillExecutionResult(
            success = true,
            message = message,
            skillName = skill.name,
            stepResults = dryRun.resolvedSteps.map { it.title }
        )
    }

    private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

    fun exportSkillsAsJson(): String {
        return gson.toJson(skillRegistry.values.toList())
    }

    fun importSkillsFromJson(jsonStr: String): Int {
        var count = 0
        try {
            val type = object : com.google.gson.reflect.TypeToken<List<SkillDefinition>>() {}.type
            val importedList: List<SkillDefinition> = gson.fromJson(jsonStr, type) ?: emptyList()
            for (skill in importedList) {
                if (skill.name.isNotBlank()) {
                    saveSkill(skill.copy(isUserCreated = true))
                    count++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error importing skills from JSON: ${e.message}")
        }
        return count
    }
}
