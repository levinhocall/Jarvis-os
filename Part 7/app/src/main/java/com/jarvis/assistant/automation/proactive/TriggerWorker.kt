package com.jarvis.assistant.automation.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis.assistant.automation.mission.MissionEngine
import com.jarvis.assistant.automation.mission.MissionOverallStatus

/**
 * Executes scheduled proactive missions and reminders in the background.
 * During unattended runs, consequential actions are strictly halted and surfaced for confirmation.
 */
class TriggerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TriggerWorker"
        private const val CHANNEL_ID = "jarvis_proactive_channel"
        private const val NOTIFICATION_ID = 4099
    }

    override suspend fun doWork(): Result {
        val triggerId = inputData.getString("triggerId") ?: return Result.failure()
        val goal = inputData.getString("goal") ?: return Result.failure()
        val isUnattended = inputData.getBoolean("isUnattended", true)

        Log.i(TAG, "TriggerWorker executing trigger '$triggerId' for goal: '$goal' (unattended=$isUnattended)")

        return try {
            val mission = MissionEngine.startMission(applicationContext, goal)

            // For unattended runs: if the mission requires approval or involves consequential actions,
            // notify user rather than attempting blind execution.
            if (isUnattended && (mission.status == MissionOverallStatus.WAITING_FOR_APPROVAL ||
                        mission.status == MissionOverallStatus.CLARIFICATION_REQUIRED)) {
                postApprovalNotification(
                    title = "JARVIS Proactive Task Needs Approval",
                    message = "Task '$goal' requires confirmation before proceeding."
                )
                return Result.success()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "TriggerWorker failed for '$triggerId': ${e.message}", e)
            Result.retry()
        }
    }

    private fun postApprovalNotification(title: String, message: String) {
        try {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "JARVIS Proactive Triggers",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for unattended missions requiring user authorization"
                }
                nm.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification: ${e.message}", e)
        }
    }
}
