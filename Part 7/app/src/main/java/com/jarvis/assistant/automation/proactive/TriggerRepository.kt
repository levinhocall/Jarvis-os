package com.jarvis.assistant.automation.proactive

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class TriggerType {
    ONE_TIME_DELAYED,
    RECURRING_DAILY,
    NOTIFICATION_KEYWORD
}

data class ScheduledTrigger(
    val triggerId: String = UUID.randomUUID().toString(),
    val goalOrAction: String,
    val triggerType: TriggerType,
    val delayMinutes: Long = 0L,
    val dailyHour: Int = 8,
    val dailyMinute: Int = 0,
    val keywordFilter: String? = null,
    val isUnattended: Boolean = true,
    val isEnabled: Boolean = true
)

/**
 * Repository for registering and scheduling proactive task triggers.
 */
class TriggerRepository(
    private val context: Context? = null,
    private val workManagerProvider: (() -> WorkManager?)? = null
) {
    companion object {
        private const val TAG = "TriggerRepository"
        private const val PREFS_NAME = "jarvis_triggers"
        private const val KEY_TRIGGERS = "triggers_json"
    }

    private val triggers = ConcurrentHashMap<String, ScheduledTrigger>()
    private val gson = Gson()

    init {
        loadTriggers()
    }

    private fun getWorkManager(): WorkManager? {
        return workManagerProvider?.invoke() ?: run {
            context?.let {
                try { WorkManager.getInstance(it) } catch (e: Exception) { null }
            }
        }
    }

    private fun loadTriggers() {
        if (context == null) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_TRIGGERS, null)
            if (!json.isNullOrBlank()) {
                val type = object : TypeToken<List<ScheduledTrigger>>() {}.type
                val list: List<ScheduledTrigger> = gson.fromJson(json, type) ?: emptyList()
                list.forEach { triggers[it.triggerId] = it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load triggers: ${e.message}", e)
        }
    }

    private fun saveTriggers() {
        if (context == null) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(triggers.values.toList())
            prefs.edit().putString(KEY_TRIGGERS, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save triggers: ${e.message}", e)
        }
    }

    fun getAllTriggers(): List<ScheduledTrigger> = triggers.values.toList()

    fun getTrigger(triggerId: String): ScheduledTrigger? = triggers[triggerId]

    /**
     * Schedules a delayed or recurring trigger via WorkManager.
     */
    fun scheduleTrigger(trigger: ScheduledTrigger): Boolean {
        triggers[trigger.triggerId] = trigger
        saveTriggers()

        val wm = getWorkManager() ?: return false

        val inputData = workDataOf(
            "triggerId" to trigger.triggerId,
            "goal" to trigger.goalOrAction,
            "isUnattended" to trigger.isUnattended
        )

        return try {
            when (trigger.triggerType) {
                TriggerType.ONE_TIME_DELAYED -> {
                    val workRequest = OneTimeWorkRequestBuilder<TriggerWorker>()
                        .setInitialDelay(trigger.delayMinutes.coerceAtLeast(1L), TimeUnit.MINUTES)
                        .setInputData(inputData)
                        .addTag(trigger.triggerId)
                        .build()
                    wm.enqueueUniqueWork(trigger.triggerId, ExistingWorkPolicy.REPLACE, workRequest)
                    true
                }
                TriggerType.RECURRING_DAILY -> {
                    val workRequest = PeriodicWorkRequestBuilder<TriggerWorker>(24, TimeUnit.HOURS)
                        .setInputData(inputData)
                        .addTag(trigger.triggerId)
                        .build()
                    wm.enqueueUniquePeriodicWork(trigger.triggerId, ExistingPeriodicWorkPolicy.UPDATE, workRequest)
                    true
                }
                TriggerType.NOTIFICATION_KEYWORD -> {
                    // Handled event-driven by NotificationListenerService
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager scheduling failed: ${e.message}", e)
            false
        }
    }

    fun cancelTrigger(triggerId: String): Boolean {
        triggers.remove(triggerId)
        saveTriggers()
        val wm = getWorkManager()
        wm?.cancelUniqueWork(triggerId)
        return true
    }
}
