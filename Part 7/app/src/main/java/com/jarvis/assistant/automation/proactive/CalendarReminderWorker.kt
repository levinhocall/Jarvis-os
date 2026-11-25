package com.jarvis.assistant.automation.proactive

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CalendarReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repository = (applicationContext as? JarvisApp)?.triggerRepository
            ?: return@withContext Result.failure()
        val id = inputData.getString("triggerId") ?: return@withContext Result.failure()
        val dueMs = inputData.getLong("dueMs", -1L)
        if (dueMs < 0L) return@withContext Result.failure()
        val trigger = repository.getTrigger(id) ?: return@withContext Result.success()
        if (trigger.triggerType != TriggerType.CALENDAR_REMINDER) return@withContext Result.failure()

        val now = System.currentTimeMillis()
        if (now < dueMs) return@withContext Result.retry()
        try {
            repository.enqueueCalendarOccurrence(trigger, now)
        } catch (error: Exception) {
            Log.w("CalendarReminder", "Could not register the next occurrence.", error)
            return@withContext Result.retry()
        }

        val graceMs = TimeUnit.MINUTES.toMillis(
            minOf(120, trigger.calendarSchedule?.intervalMinutes ?: 120).toLong()
        )
        if (now - dueMs < graceMs && repository.getTrigger(id) != null) {
            if (!TriggerReminderNotifier.show(applicationContext, id, trigger.goalOrAction)) {
                return@withContext Result.retry()
            }
        }
        Result.success()
    }
}
