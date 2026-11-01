package com.jarvis.assistant.automation.mission

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R

object MissionApprovalNotifier {
    private const val CHANNEL_ID = "jarvis_mission_approval"
    private const val CHANNEL_NAME = "Mission approval"

    fun show(context: Context, mission: MissionDefinition): Boolean {
        if (mission.status != MissionOverallStatus.WAITING_FOR_APPROVAL) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Requests explicit approval before consequential JARVIS actions."
                }
            )
        }

        val requestBase = mission.missionId.hashCode()
        val approveIntent = Intent(appContext, MissionApprovalReceiver::class.java)
            .setAction(MissionApprovalReceiver.ACTION_APPROVE)
            .putExtra(MissionApprovalReceiver.EXTRA_MISSION_ID, mission.missionId)
            .putExtra(MissionApprovalReceiver.EXTRA_APPROVED, true)
        val rejectIntent = Intent(appContext, MissionApprovalReceiver::class.java)
            .setAction(MissionApprovalReceiver.ACTION_REJECT)
            .putExtra(MissionApprovalReceiver.EXTRA_MISSION_ID, mission.missionId)
            .putExtra(MissionApprovalReceiver.EXTRA_APPROVED, false)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val approvePending = PendingIntent.getBroadcast(appContext, requestBase, approveIntent, flags)
        val rejectPending = PendingIntent.getBroadcast(appContext, requestBase + 1, rejectIntent, flags)
        val message = mission.approvalPrompt ?: "A consequential mission action is waiting for your approval."

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("JARVIS needs your approval")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_send, "Approve", approvePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPending)
            .build()

        return runCatching {
            NotificationManagerCompat.from(appContext).notify(requestBase, notification)
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context?, missionId: String) {
        if (context == null) return
        NotificationManagerCompat.from(context.applicationContext).cancel(missionId.hashCode())
    }
}
