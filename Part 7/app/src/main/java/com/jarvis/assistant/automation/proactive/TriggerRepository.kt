package com.jarvis.assistant.automation.proactive

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar
import java.util.TimeZone
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
 * Encrypted repository for proactive triggers. A trigger is considered scheduled only after
 * encrypted persistence succeeds and its corresponding WorkManager/event registration succeeds.
 */
class TriggerRepository(
    context: Context? = null,
    private val workManagerProvider: (() -> WorkManager?)? = null
) {
    companion object {
        private const val TAG = "TriggerRepository"
        private const val LEGACY_PREFS_NAME = "jarvis_triggers"
        private const val PREFS_NAME = "jarvis_secure_triggers"
        private const val KEY_TRIGGERS = "triggers_json"

        internal fun nextDailyDelayMs(
            nowMs: Long,
            hour: Int,
            minute: Int,
            timeZone: TimeZone = TimeZone.getDefault()
        ): Long {
            val next = Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
            }
            return (next.timeInMillis - nowMs).coerceAtLeast(0L)
        }
    }

    private val appContext = context?.applicationContext
    private val triggers = ConcurrentHashMap<String, ScheduledTrigger>()
    private val gson = Gson()
    private val encryptedPrefs: SharedPreferences? = appContext?.let { app ->
        runCatching {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure { Log.e(TAG, "Encrypted trigger storage unavailable; scheduling is disabled.", it) }
            .getOrNull()
    }

    init {
        migrateLegacyTriggers()
        loadTriggers()
    }

    private fun getWorkManager(): WorkManager? =
        workManagerProvider?.invoke() ?: appContext?.let {
            runCatching { WorkManager.getInstance(it) }.getOrNull()
        }

    private fun migrateLegacyTriggers() {
        val app = appContext ?: return
        val secure = encryptedPrefs ?: return
        val legacy = app.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyJson = legacy.getString(KEY_TRIGGERS, null)?.takeIf { it.isNotBlank() } ?: return
        try {
            if (secure.getString(KEY_TRIGGERS, null).isNullOrBlank()) {
                check(secure.edit().putString(KEY_TRIGGERS, legacyJson).commit()) {
                    "Encrypted trigger migration write failed."
                }
            }
            check(secure.getString(KEY_TRIGGERS, null).isNotBlank()) {
                "Encrypted trigger migration verification failed."
            }
            check(legacy.edit().remove(KEY_TRIGGERS).commit()) {
                "Legacy plaintext trigger cleanup failed."
            }
        } catch (error: Exception) {
            Log.e(TAG, "Trigger migration failed; plaintext source data was preserved for retry.", error)
        }
    }

    private fun loadTriggers() {
        val secure = encryptedPrefs ?: return
        try {
            val json = secure.getString(KEY_TRIGGERS, null)
            if (!json.isNullOrBlank()) {
                val type = object : TypeToken<List<ScheduledTrigger>>() {}.type
                val list: List<ScheduledTrigger> = gson.fromJson(json, type) ?: emptyList()
                list.filter { it.isEnabled }.forEach { triggers[it.triggerId] = it }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load encrypted triggers.", error)
        }
    }

    @Synchronized
    private fun saveTriggers(): Boolean {
        val secure = encryptedPrefs ?: return false
        return try {
            val json = gson.toJson(triggers.values.toList())
            secure.edit().putString(KEY_TRIGGERS, json).commit() &&
                secure.getString(KEY_TRIGGERS, null) == json
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist encrypted triggers.", error)
            false
        }
    }

    fun getAllTriggers(): List<ScheduledTrigger> = triggers.values.toList()

    fun getTrigger(triggerId: String): ScheduledTrigger? = triggers[triggerId]

    @Synchronized
    fun scheduleTrigger(trigger: ScheduledTrigger): Boolean {
        if (trigger.goalOrAction.isBlank() || !trigger.isEnabled) return false
        if (trigger.triggerType == TriggerType.NOTIFICATION_KEYWORD && trigger.keywordFilter.isNullOrBlank()) {
            return false
        }
        val secure = encryptedPrefs ?: return false
        val previous = triggers.put(trigger.triggerId, trigger)
        if (!saveTriggers()) {
            if (previous == null) triggers.remove(trigger.triggerId) else triggers[trigger.triggerId] = previous
            return false
        }

        return try {
            when (trigger.triggerType) {
                TriggerType.ONE_TIME_DELAYED -> {
                    val wm = getWorkManager() ?: throw IllegalStateException("WorkManager is unavailable.")
                    val request = OneTimeWorkRequestBuilder<TriggerWorker>()
                        .setInitialDelay(trigger.delayMinutes.coerceAtLeast(1L), TimeUnit.MINUTES)
                        .setInputData(triggerInputData(trigger))
                        .addTag(trigger.triggerId)
                        .build()
                    wm.enqueueUniqueWork(trigger.triggerId, ExistingWorkPolicy.REPLACE, request)
                }
                TriggerType.RECURRING_DAILY -> {
                    val wm = getWorkManager() ?: throw IllegalStateException("WorkManager is unavailable.")
                    val delay = nextDailyDelayMs(
                        System.currentTimeMillis(),
                        trigger.dailyHour,
                        trigger.dailyMinute
                    )
                    val request = PeriodicWorkRequestBuilder<TriggerWorker>(24, TimeUnit.HOURS)
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(triggerInputData(trigger))
                        .addTag(trigger.triggerId)
                        .build()
                    wm.enqueueUniquePeriodicWork(trigger.triggerId, ExistingPeriodicWorkPolicy.UPDATE, request)
                }
                TriggerType.NOTIFICATION_KEYWORD -> {
                    // The NotificationListenerService forwards matching events to this repository.
                }
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "Trigger scheduling failed; rolling back persisted trigger.", error)
            if (previous == null) triggers.remove(trigger.triggerId) else triggers[trigger.triggerId] = previous
            saveTriggers()
            false
        }
    }

    private fun triggerInputData(trigger: ScheduledTrigger): Data = workDataOf(
        "triggerId" to trigger.triggerId,
        "goal" to trigger.goalOrAction,
        "isUnattended" to trigger.isUnattended
    )

    /**
     * Called in-memory from NotificationListenerService. Notification content is never persisted.
     * A matching action is enqueued only after the trigger was explicitly saved by the user.
     */
    fun onNotificationPosted(packageName: String, title: String?, text: String?): Int {
        val wm = getWorkManager() ?: return 0
        val haystack = listOfNotNull(packageName, title, text).joinToString(" ").lowercase()
        var enqueued = 0
        triggers.values.filter {
            it.isEnabled &&
                it.triggerType == TriggerType.NOTIFICATION_KEYWORD &&
                !it.keywordFilter.isNullOrBlank() &&
                haystack.contains(it.keywordFilter!!.lowercase())
        }.forEach { trigger ->
            try {
                val request = OneTimeWorkRequestBuilder<TriggerWorker>()
                    .setInputData(triggerInputData(trigger))
                    .addTag(trigger.triggerId)
                    .build()
                wm.enqueueUniqueWork(
                    "notification_${trigger.triggerId}",
                    ExistingWorkPolicy.KEEP,
                    request
                )
                enqueued++
            } catch (error: Exception) {
                Log.e(TAG, "Could not enqueue notification trigger.", error)
            }
        }
        return enqueued
    }

    @Synchronized
    fun cancelTrigger(triggerId: String): Boolean {
        val existing = triggers.remove(triggerId) ?: return false
        if (!saveTriggers()) {
            triggers[triggerId] = existing
            return false
        }
        runCatching { getWorkManager()?.cancelUniqueWork(triggerId) }
        runCatching { getWorkManager()?.cancelUniqueWork("notification_$triggerId") }
        return true
    }
}
