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
        val inputTriggerType = inputData.getString("triggerType").orEmpty()
        val isUnattended = inputData.getBoolean("isUnattended", false)
        if (goal.isBlank()) return Result.failure()

        val app = applicationContext as? JarvisApp
            ?: return Result.failure()
        val triggerType = inputTriggerType.ifBlank {
            app.triggerRepository.getTrigger(triggerId)?.triggerType?.name.orEmpty()
        }

        // Scheduled work must not silently become autonomous execution. A non-unattended
        // trigger becomes a private reminder; the user opens JARVIS and starts it manually.
        if (!isUnattended) {
            val notified = TriggerReminderNotifier.show(applicationContext, triggerId, goal)
            Log.i(TAG, "Trigger $triggerId requires manual start; reminderShown=$notified. No action was executed.")
            if (triggerType == TriggerType.ONE_TIME_DELAYED.name && triggerId.isNotBlank()) {
                app.triggerRepository.cancelTrigger(triggerId)
            }
            return Result.success()
        }

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
                Log.w(TAG, "Mission is still running or did not reach a verifiable outcome before timeout.")
                if (triggerType == TriggerType.ONE_TIME_DELAYED.name && triggerId.isNotBlank()) {
                    app.triggerRepository.cancelTrigger(triggerId)
                }
                return Result.success()
            }

            // A one-time trigger has fired even when its mission is awaiting approval,
            // clarification, or a truthful failure state. The durable mission remains separate.
            if (triggerType == TriggerType.ONE_TIME_DELAYED.name && triggerId.isNotBlank()) {
                app.triggerRepository.cancelTrigger(triggerId)
            }

            when (outcome.status) {
                MissionOverallStatus.COMPLETED -> Result.success()
                MissionOverallStatus.WAITING_FOR_APPROVAL -> {
                    MissionApprovalNotifier.show(applicationContext, outcome)
                    // Keep the mission persisted; never execute it again until approval is received.
                    Result.success()
                }
                MissionOverallStatus.CLARIFICATION_REQUIRED -> {
                    Log.w(TAG, "Mission requires clarification; it was not reported as completed.")
                    Result.success()
                }
                MissionOverallStatus.UNVERIFIED,
                MissionOverallStatus.PARTIALLY_COMPLETED,
                MissionOverallStatus.FAILED -> {
                    Log.w(TAG, "Mission ended with status ${outcome.status}; no success was reported.")
                    Result.success()
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
