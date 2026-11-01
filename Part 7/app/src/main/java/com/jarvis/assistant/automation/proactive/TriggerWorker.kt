package com.jarvis.assistant.automation.proactive

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.mission.MissionApprovalNotifier
import com.jarvis.assistant.automation.mission.MissionEngine
import com.jarvis.assistant.automation.mission.MissionOverallStatus

class TriggerWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TriggerWorker"
        private const val MISSION_TIMEOUT_MS = 120_000L
    }

    override suspend fun doWork(): Result {
        val goal = inputData.getString("goal")?.trim().orEmpty()
        val triggerId = inputData.getString("triggerId").orEmpty()
        val triggerType = inputData.getString("triggerType").orEmpty()
        val isUnattended = inputData.getBoolean("isUnattended", false)
        if (goal.isBlank()) return Result.failure()

        // Scheduled work must not silently become autonomous execution. The creator must
        // explicitly opt into unattended execution; otherwise no mission is started.
        if (!isUnattended) {
            Log.i(TAG, "Trigger $triggerId requires the user to start its mission manually; no action was executed.")
            return Result.failure()
        }

        val app = applicationContext as? JarvisApp
            ?: return Result.failure()

        return try {
            // Restore durable state before starting another mission.
            MissionEngine.resumeNonTerminalMissions(applicationContext)
            val started = MissionEngine.startMission(applicationContext, goal)
            val outcome = if (started.status in setOf(
                    MissionOverallStatus.WAITING_FOR_APPROVAL,
                    MissionOverallStatus.CLARIFICATION_REQUIRED,
                    MissionOverallStatus.COMPLETED,
                    MissionOverallStatus.PARTIALLY_COMPLETED,
                    MissionOverallStatus.FAILED,
                    MissionOverallStatus.CANCELLED,
                    MissionOverallStatus.UNVERIFIED
                )
            ) {
                started
            } else {
                MissionEngine.awaitMissionOutcome(started.missionId, MISSION_TIMEOUT_MS)
            }

            if (outcome == null) {
                Log.w(TAG, "Mission execution did not reach a verifiable outcome before timeout.")
                return Result.failure()
            }

            when (outcome.status) {
                MissionOverallStatus.COMPLETED -> {
                    if (triggerType == TriggerType.ONE_TIME_DELAYED.name && triggerId.isNotBlank()) {
                        app.triggerRepository.cancelTrigger(triggerId)
                    }
                    Result.success()
                }
                MissionOverallStatus.WAITING_FOR_APPROVAL -> {
                    MissionApprovalNotifier.show(applicationContext, outcome)
                    // Keep the mission persisted; never execute it again until approval is received.
                    Result.success()
                }
                MissionOverallStatus.CLARIFICATION_REQUIRED -> {
                    Log.w(TAG, "Mission requires clarification; it was not reported as completed.")
                    Result.failure()
                }
                MissionOverallStatus.UNVERIFIED,
                MissionOverallStatus.PARTIALLY_COMPLETED,
                MissionOverallStatus.FAILED -> {
                    Log.w(TAG, "Mission ended with status ${outcome.status}; no success was reported.")
                    Result.failure()
                }
                MissionOverallStatus.CANCELLED -> Result.success()
                else -> Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Trigger mission failed (${e.javaClass.simpleName}).")
            Result.failure()
        }
    }
}
