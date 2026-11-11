package com.jarvis.assistant.ui.aura

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.jarvis.assistant.data.memory.MemoryDatabase
import com.jarvis.assistant.data.directives.AutonomyDecisionEntity
import kotlinx.coroutines.CancellationException
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Opt-in, bounded telemetry review. No UI automation or outbound messages while unattended. */
class AutonomyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AuraOptions.enabled("autonomy")) return Result.success()
        val dao = try {
            MemoryDatabase.getReadyInstance(applicationContext).directiveDao()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            com.jarvis.assistant.core.diagnostics.StorageHealth.record(error)
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
        suspend fun record(reason: String, action: String) { dao.addDecision(AutonomyDecisionEntity(reason=reason,action=action)); dao.trimDecisions() }
        return try {
            if (AutonomyPolicy.quietHour(LocalTime.now().hour)) { record("Quiet hours: 23:00–07:00 local time. No cloud request, speech or action.","QUIET"); return Result.success() }
            val battery=applicationContext.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level=battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val notifications=com.jarvis.assistant.automation.notification.JarvisNotificationListenerService.getRecentNotifications()
            val connected=com.jarvis.assistant.automation.notification.JarvisNotificationListenerService.isConnected()
            val missed=notifications.count { (it.sender+" "+it.message).contains("missed call",true) }
            val counts="batteryPercent=$level, notificationAccess=$connected, cachedNotificationCount=${notifications.size}, cachedMissedCallNotifications=$missed. Counts are a bounded notification snapshot, not a complete inbox or call log."
            val (action,reason)=AuraCloudClient.decide(counts)
            if (!AuraOptions.enabled("autonomy") || AutonomyPolicy.quietHour(LocalTime.now().hour) || isStopped) return Result.success()
            if (action == "notify") {
                val allowed=android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(applicationContext,android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
                if (AutonomyPolicy.canNotify(AuraOptions.enabled("autonomy"),LocalTime.now().hour,allowed)) {
                    val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.createNotificationChannel(NotificationChannel("jarvis_decisions","Jarvis autonomous suggestions",NotificationManager.IMPORTANCE_DEFAULT))
                    val intent=android.app.PendingIntent.getActivity(applicationContext,71,android.content.Intent(applicationContext,DecisionLogsActivity::class.java),android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    manager.notify(7101,NotificationCompat.Builder(applicationContext,"jarvis_decisions").setSmallIcon(com.jarvis.assistant.R.drawable.ic_settings_apps).setContentTitle("JARVIS suggestion").setContentText(reason).setStyle(NotificationCompat.BigTextStyle().bigText(reason)).setContentIntent(intent).setAutoCancel(true).build())
                    record(reason,"NOTIFIED")
                    if (AuraOptions.enabled("autonomySpeak") && com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value) com.jarvis.assistant.audio.VoiceSynthesisRouter.speakPrivate(reason)
                } else record("$reason · Notification permission unavailable.","NOT_SHOWN")
            } else record(reason,"NO_ACTION")
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            runCatching { record("Review unavailable (${error.javaClass.simpleName}). No external action performed.","FAILED") }
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
    companion object {
        fun schedule(context: Context) {
            val manager=WorkManager.getInstance(context)
            if (!AuraOptions.enabled("autonomy")) { manager.cancelUniqueWork("jarvis-autonomy"); return }
            manager.enqueueUniquePeriodicWork("jarvis-autonomy",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutonomyWorker>(30,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
