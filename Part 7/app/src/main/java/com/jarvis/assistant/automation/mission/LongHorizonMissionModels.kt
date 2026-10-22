package com.jarvis.assistant.automation.mission

import java.util.UUID

enum class LongHorizonStatus {
    IDLE,
    PLANNING,
    EXECUTING,
    PAUSED,
    WAITING_APPROVAL,
    REPLANNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class MilestoneStatus {
    PENDING,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED
}

data class MilestoneDefinition(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val dependsOnMilestoneIds: List<String> = emptyList(),
    val steps: List<MissionStep> = emptyList(),
    val status: MilestoneStatus = MilestoneStatus.PENDING,
    val isCheckpointRequiredBeforeExecution: Boolean = false
)

data class MissionCheckpoint(
    val checkpointId: String = UUID.randomUUID().toString(),
    val missionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val currentMilestoneId: String?,
    val completedMilestoneIds: Set<String> = emptySet(),
    val completedStepIds: Set<String> = emptySet(),
    val executedNonIdempotentStepIds: Set<String> = emptySet(),
    val stateVariables: Map<String, String> = emptyMap(),
    val reason: String = "Periodic checkpoint"
)

data class MissionLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String, // INFO, WARN, ERROR, ACTION
    val milestoneId: String? = null,
    val stepId: String? = null,
    val message: String
)

sealed class StepExecutionEligibility {
    object Allowed : StepExecutionEligibility()
    data class BlockedByDependency(val unmetMilestoneIds: List<String>) : StepExecutionEligibility()
    data class ReplayPrevented(val reason: String) : StepExecutionEligibility()
    data class LimitExceeded(val reason: String) : StepExecutionEligibility()
    data class MissionNotActive(val currentStatus: LongHorizonStatus) : StepExecutionEligibility()
}

data class LongHorizonMissionState(
    val missionId: String = UUID.randomUUID().toString(),
    val goal: String,
    val milestones: List<MilestoneDefinition> = emptyList(),
    val currentMilestoneId: String? = null,
    val status: LongHorizonStatus = LongHorizonStatus.IDLE,
    val checkpointHistory: List<MissionCheckpoint> = emptyList(),
    val logs: List<MissionLogEntry> = emptyList(),
    val replanCount: Int = 0,
    val maxReplans: Int = 3,
    val totalExecutedSteps: Int = 0,
    val maxTotalSteps: Int = 50,
    val stateVariables: Map<String, String> = emptyMap(),
    val executedNonIdempotentStepIds: Set<String> = emptySet(),
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isFinished: Boolean
        get() = status in setOf(
            LongHorizonStatus.COMPLETED,
            LongHorizonStatus.FAILED,
            LongHorizonStatus.CANCELLED
        )
}

interface CheckpointStorage {
    fun saveCheckpoint(checkpoint: MissionCheckpoint)
    fun getLatestCheckpoint(missionId: String): MissionCheckpoint?
    fun getAllCheckpoints(missionId: String): List<MissionCheckpoint>
    fun clear(missionId: String)
}

class InMemoryCheckpointStorage : CheckpointStorage {
    private val checkpoints = mutableMapOf<String, MutableList<MissionCheckpoint>>()

    @Synchronized
    override fun saveCheckpoint(checkpoint: MissionCheckpoint) {
        val list = checkpoints.getOrPut(checkpoint.missionId) { mutableListOf() }
        list.add(checkpoint)
    }

    @Synchronized
    override fun getLatestCheckpoint(missionId: String): MissionCheckpoint? {
        return checkpoints[missionId]?.lastOrNull()
    }

    @Synchronized
    override fun getAllCheckpoints(missionId: String): List<MissionCheckpoint> {
        return checkpoints[missionId]?.toList() ?: emptyList()
    }

    @Synchronized
    override fun clear(missionId: String) {
        checkpoints.remove(missionId)
    }
}
