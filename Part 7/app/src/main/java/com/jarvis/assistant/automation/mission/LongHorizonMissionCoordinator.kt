package com.jarvis.assistant.automation.mission

import java.util.concurrent.ConcurrentHashMap

class LongHorizonMissionCoordinator(
    private val checkpointStorage: CheckpointStorage = InMemoryCheckpointStorage()
) {
    private val missions = ConcurrentHashMap<String, LongHorizonMissionState>()

    @Synchronized
    fun createMission(
        goal: String,
        milestones: List<MilestoneDefinition>,
        maxReplans: Int = 3,
        maxTotalSteps: Int = 50
    ): LongHorizonMissionState {
        validateNoCycles(milestones)

        val mission = LongHorizonMissionState(
            goal = goal,
            milestones = milestones,
            status = LongHorizonStatus.IDLE,
            maxReplans = maxReplans,
            maxTotalSteps = maxTotalSteps,
            currentMilestoneId = milestones.firstOrNull()?.id
        )
        missions[mission.missionId] = mission
        log(mission.missionId, "INFO", message = "Mission created: '$goal' with ${milestones.size} milestones.")
        return mission
    }

    @Synchronized
    fun getMission(missionId: String): LongHorizonMissionState? {
        return missions[missionId]
    }

    @Synchronized
    fun startOrResume(missionId: String): LongHorizonMissionState {
        val current = missions[missionId] ?: throw IllegalArgumentException("Mission not found: $missionId")
        if (current.isFinished) {
            return current
        }

        val nextMilestone = findNextExecutableMilestone(current)
        val updated = current.copy(
            status = LongHorizonStatus.EXECUTING,
            currentMilestoneId = nextMilestone?.id ?: current.currentMilestoneId,
            updatedAt = System.currentTimeMillis()
        )
        missions[missionId] = updated
        createCheckpoint(missionId, "Resumed execution at milestone ${nextMilestone?.title ?: "none"}")
        log(missionId, "INFO", milestoneId = nextMilestone?.id, message = "Mission resumed. Current milestone: ${nextMilestone?.title}")
        return updated
    }

    @Synchronized
    fun pause(missionId: String, reason: String): LongHorizonMissionState {
        val current = missions[missionId] ?: throw IllegalArgumentException("Mission not found: $missionId")
        if (current.isFinished) return current

        val updated = current.copy(
            status = LongHorizonStatus.PAUSED,
            updatedAt = System.currentTimeMillis()
        )
        missions[missionId] = updated
        createCheckpoint(missionId, "Paused: $reason")
        log(missionId, "WARN", message = "Mission paused: $reason")
        return updated
    }

    @Synchronized
    fun cancel(missionId: String, reason: String): LongHorizonMissionState {
        val current = missions[missionId] ?: throw IllegalArgumentException("Mission not found: $missionId")
        val updated = current.copy(
            status = LongHorizonStatus.CANCELLED,
            failureReason = reason,
            updatedAt = System.currentTimeMillis()
        )
        missions[missionId] = updated
        createCheckpoint(missionId, "Cancelled: $reason")
        log(missionId, "WARN", message = "Mission cancelled: $reason")
        return updated
    }

    @Synchronized
    fun replan(
        missionId: String,
        newMilestones: List<MilestoneDefinition>,
        reason: String
    ): LongHorizonMissionState {
        val current = missions[missionId] ?: throw IllegalArgumentException("Mission not found: $missionId")
        if (current.isFinished) return current

        if (current.replanCount >= current.maxReplans) {
            val failed = current.copy(
                status = LongHorizonStatus.FAILED,
                failureReason = "Max replan limit exceeded (${current.maxReplans}). Aborting to avoid drifting loop.",
                updatedAt = System.currentTimeMillis()
            )
            missions[missionId] = failed
            log(missionId, "ERROR", message = "Replanning rejected: max replans reached.")
            return failed
        }

        validateNoCycles(newMilestones)

        // Retain milestones that are already COMPLETED
        val completedMilestones = current.milestones.filter { it.status == MilestoneStatus.COMPLETED }
        val mergedMilestones = completedMilestones + newMilestones.filter { newM ->
            completedMilestones.none { it.id == newM.id }
        }

        val updated = current.copy(
            milestones = mergedMilestones,
            replanCount = current.replanCount + 1,
            status = LongHorizonStatus.EXECUTING,
            currentMilestoneId = mergedMilestones.firstOrNull { it.status == MilestoneStatus.PENDING }?.id,
            updatedAt = System.currentTimeMillis()
        )
        missions[missionId] = updated
        createCheckpoint(missionId, "Replanned (attempt ${updated.replanCount}): $reason")
        log(missionId, "INFO", message = "Mission replanned: $reason. New milestone count: ${mergedMilestones.size}")
        return updated
    }

    @Synchronized
    fun checkStepEligibility(
        missionId: String,
        milestoneId: String,
        step: MissionStep
    ): StepExecutionEligibility {
        val mission = missions[missionId] ?: return StepExecutionEligibility.MissionNotActive(LongHorizonStatus.FAILED)

        // 1. Check non-idempotent replay guard
        if (!step.isIdempotent && mission.executedNonIdempotentStepIds.contains(step.stepId)) {
            return StepExecutionEligibility.ReplayPrevented(
                "Step '${step.stepId}' (${step.toolName}) is non-idempotent and was already executed. Replay prevented."
            )
        }

        // 2. Check total step cap
        if (mission.totalExecutedSteps >= mission.maxTotalSteps) {
            return StepExecutionEligibility.LimitExceeded("Total step cap exceeded (${mission.maxTotalSteps})")
        }

        // 3. Check mission status
        if (mission.status != LongHorizonStatus.EXECUTING) {
            return StepExecutionEligibility.MissionNotActive(mission.status)
        }

        val milestone = mission.milestones.find { it.id == milestoneId }
            ?: return StepExecutionEligibility.LimitExceeded("Milestone not found: $milestoneId")

        // 4. Check milestone dependencies
        val completedMilestoneIds = mission.milestones
            .filter { it.status == MilestoneStatus.COMPLETED }
            .map { it.id }
            .toSet()

        val unmetDependencies = milestone.dependsOnMilestoneIds.filter { it !in completedMilestoneIds }
        if (unmetDependencies.isNotEmpty()) {
            return StepExecutionEligibility.BlockedByDependency(unmetDependencies)
        }

        return StepExecutionEligibility.Allowed
    }

    @Synchronized
    fun recordStepExecution(
        missionId: String,
        milestoneId: String,
        stepId: String,
        isSuccess: Boolean,
        isNonIdempotent: Boolean,
        outputVariables: Map<String, String> = emptyMap(),
        failureReason: String? = null
    ): LongHorizonMissionState {
        val current = missions[missionId] ?: throw IllegalArgumentException("Mission not found: $missionId")

        val newExecutedNonIdempotent = if (isSuccess && isNonIdempotent) {
            current.executedNonIdempotentStepIds + stepId
        } else {
            current.executedNonIdempotentStepIds
        }

        val newVariables = current.stateVariables + outputVariables

        // Update step status in milestone
        val updatedMilestones = current.milestones.map { milestone ->
            if (milestone.id == milestoneId) {
                val updatedSteps = milestone.steps.map { step ->
                    if (step.stepId == stepId) {
                        step.copy(
                            status = if (isSuccess) MissionStepStatus.COMPLETED else MissionStepStatus.FAILED,
                            failureReason = failureReason,
                            completedAt = System.currentTimeMillis()
                        )
                    } else step
                }
                val allStepsCompleted = updatedSteps.all { it.status == MissionStepStatus.COMPLETED }
                val anyFailed = updatedSteps.any { it.status == MissionStepStatus.FAILED }
                val milestoneStatus = when {
                    allStepsCompleted -> MilestoneStatus.COMPLETED
                    anyFailed -> MilestoneStatus.FAILED
                    else -> MilestoneStatus.IN_PROGRESS
                }
                milestone.copy(steps = updatedSteps, status = milestoneStatus)
            } else milestone
        }

        val allMilestonesCompleted = updatedMilestones.all { it.status == MilestoneStatus.COMPLETED }
        val overallStatus = when {
            allMilestonesCompleted -> LongHorizonStatus.COMPLETED
            !isSuccess && isNonIdempotent -> LongHorizonStatus.PAUSED // Pause on non-idempotent failure to avoid repeating
            else -> current.status
        }

        val nextMilestone = updatedMilestones.firstOrNull { it.status == MilestoneStatus.PENDING || it.status == MilestoneStatus.IN_PROGRESS }

        val updatedMission = current.copy(
            milestones = updatedMilestones,
            totalExecutedSteps = current.totalExecutedSteps + 1,
            executedNonIdempotentStepIds = newExecutedNonIdempotent,
            stateVariables = newVariables,
            status = overallStatus,
            currentMilestoneId = nextMilestone?.id,
            updatedAt = System.currentTimeMillis()
        )
        missions[missionId] = updatedMission

        log(
            missionId,
            if (isSuccess) "ACTION" else "ERROR",
            milestoneId = milestoneId,
            stepId = stepId,
            message = "Step $stepId execution ${if (isSuccess) "succeeded" else "failed: $failureReason"}"
        )

        createCheckpoint(missionId, "Post-step $stepId (${if (isSuccess) "success" else "failure"})")
        return updatedMission
    }

    @Synchronized
    fun createCheckpoint(missionId: String, reason: String): MissionCheckpoint {
        val mission = missions[missionId] ?: throw IllegalArgumentException("Mission not found: $missionId")
        val completedMilestones = mission.milestones.filter { it.status == MilestoneStatus.COMPLETED }.map { it.id }.toSet()
        val completedSteps = mission.milestones.flatMap { it.steps }
            .filter { it.status == MissionStepStatus.COMPLETED }
            .map { it.stepId }
            .toSet()

        val checkpoint = MissionCheckpoint(
            missionId = missionId,
            timestamp = System.currentTimeMillis(),
            currentMilestoneId = mission.currentMilestoneId,
            completedMilestoneIds = completedMilestones,
            completedStepIds = completedSteps,
            executedNonIdempotentStepIds = mission.executedNonIdempotentStepIds,
            stateVariables = mission.stateVariables,
            reason = reason
        )
        checkpointStorage.saveCheckpoint(checkpoint)

        val updatedMission = mission.copy(
            checkpointHistory = mission.checkpointHistory + checkpoint,
            updatedAt = System.currentTimeMillis()
        )
        missions[missionId] = updatedMission
        return checkpoint
    }

    @Synchronized
    fun restoreFromLatestCheckpoint(missionId: String): LongHorizonMissionState {
        val checkpoint = checkpointStorage.getLatestCheckpoint(missionId)
            ?: throw IllegalStateException("No checkpoint found for mission: $missionId")
        val current = missions[missionId] ?: throw IllegalArgumentException("Mission not found: $missionId")

        // Reconstruct milestone statuses according to completed milestone IDs
        val restoredMilestones = current.milestones.map { milestone ->
            if (checkpoint.completedMilestoneIds.contains(milestone.id)) {
                milestone.copy(
                    status = MilestoneStatus.COMPLETED,
                    steps = milestone.steps.map { it.copy(status = MissionStepStatus.COMPLETED) }
                )
            } else {
                milestone.copy(
                    steps = milestone.steps.map { step ->
                        if (checkpoint.completedStepIds.contains(step.stepId)) {
                            step.copy(status = MissionStepStatus.COMPLETED)
                        } else {
                            step.copy(status = MissionStepStatus.PENDING)
                        }
                    }
                )
            }
        }

        val restored = current.copy(
            milestones = restoredMilestones,
            currentMilestoneId = checkpoint.currentMilestoneId,
            executedNonIdempotentStepIds = checkpoint.executedNonIdempotentStepIds,
            stateVariables = checkpoint.stateVariables,
            status = LongHorizonStatus.PAUSED, // Restores safely in PAUSED state
            updatedAt = System.currentTimeMillis()
        )
        missions[missionId] = restored
        log(missionId, "INFO", message = "Restored safely from checkpoint ${checkpoint.checkpointId} in PAUSED state")
        return restored
    }

    private fun findNextExecutableMilestone(mission: LongHorizonMissionState): MilestoneDefinition? {
        val completedIds = mission.milestones.filter { it.status == MilestoneStatus.COMPLETED }.map { it.id }.toSet()
        return mission.milestones.firstOrNull { milestone ->
            milestone.status != MilestoneStatus.COMPLETED &&
            milestone.status != MilestoneStatus.CANCELLED &&
            milestone.dependsOnMilestoneIds.all { it in completedIds }
        }
    }

    private fun validateNoCycles(milestones: List<MilestoneDefinition>) {
        val map = milestones.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()

        fun dfs(id: String) {
            visited.add(id)
            recursionStack.add(id)

            val m = map[id]
            m?.dependsOnMilestoneIds?.forEach { depId ->
                if (depId !in visited) {
                    dfs(depId)
                } else if (depId in recursionStack) {
                    throw IllegalArgumentException("Circular milestone dependency detected involving milestone $depId")
                }
            }
            recursionStack.remove(id)
        }

        milestones.forEach { m ->
            if (m.id !in visited) {
                dfs(m.id)
            }
        }
    }

    private fun log(missionId: String, level: String, milestoneId: String? = null, stepId: String? = null, message: String) {
        val current = missions[missionId] ?: return
        val entry = MissionLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            milestoneId = milestoneId,
            stepId = stepId,
            message = message
        )
        missions[missionId] = current.copy(logs = current.logs + entry)
    }
}
