package com.jarvis.assistant.automation.proactive

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.mission.MissionEngine
import com.jarvis.assistant.automation.mission.MissionOverallStatus
import com.jarvis.assistant.automation.skills.SkillForgeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ScheduledProactiveItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val triggerTimeMs: Long,
    val missionId: String? = null,
    val isDismissed: Boolean = false
)

object ProactiveAssistantCoordinator {
    private const val TAG = "ProactiveAssistant"
    private val scheduledItems = ConcurrentHashMap<String, ScheduledProactiveItem>()

    private val _proactiveAlerts = MutableStateFlow<List<ScheduledProactiveItem>>(emptyList())
    val proactiveAlerts: StateFlow<List<ScheduledProactiveItem>> = _proactiveAlerts.asStateFlow()

    /**
     * Schedules a user-approved reminder or proactive task alert.
     */
    fun scheduleReminder(
        context: Context,
        title: String,
        description: String,
        delayMs: Long,
        missionId: String? = null
    ): ScheduledProactiveItem? {
        val cleanTitle = title.trim()
        val cleanDescription = description.trim()
        if (cleanTitle.isBlank() || cleanDescription.isBlank() || delayMs < 0L) {
            Log.w(TAG, "Reminder rejected because its title, description, or delay is invalid.")
            return null
        }

        val app = context.applicationContext as? JarvisApp ?: run {
            Log.e(TAG, "Reminder could not be scheduled: application is not JarvisApp.")
            return null
        }
        val item = ScheduledProactiveItem(
            title = cleanTitle,
            description = cleanDescription,
            triggerTimeMs = System.currentTimeMillis() + delayMs,
            missionId = missionId
        )
        val trigger = ScheduledTrigger(
            triggerId = item.id,
            goalOrAction = "${cleanTitle}\n${cleanDescription}",
            triggerType = TriggerType.ONE_TIME_DELAYED,
            isUnattended = false,
            delayMs = delayMs
        )
        if (!app.triggerRepository.scheduleTrigger(trigger)) {
            Log.e(TAG, "Reminder '${item.id}' was not persisted/scheduled; no success is reported.")
            return null
        }

        scheduledItems[item.id] = item
        updateAlertsFlow()
        Log.i(TAG, "Persisted reminder '${item.title}' for ${item.triggerTimeMs}.")
        return item
    }

    /**
     * Checks for incomplete or blocked missions and generates an opt-in follow-up alert.
     */
    fun checkIncompleteMissions(): ScheduledProactiveItem? {
        val mission = MissionEngine.activeMission.value ?: return null
        if (mission.status == MissionOverallStatus.WAITING_FOR_APPROVAL) {
            val alert = ScheduledProactiveItem(
                id = "alert_approval_${mission.missionId}",
                title = "Approval Required",
                description = "Mission '${mission.goal}' is waiting for your consent to proceed.",
                triggerTimeMs = System.currentTimeMillis(),
                missionId = mission.missionId
            )
            scheduledItems[alert.id] = alert
            updateAlertsFlow()
            return alert
        }
        return null
    }

    /**
     * Evaluates a user utterance and suggests a matching saved skill if available.
     */
    fun findMatchingSkillSuggestion(utterance: String): String? {
        val clean = utterance.lowercase().trim()
        val skills = SkillForgeManager.listSkills()
        val matched = skills.firstOrNull { skill ->
            val sName = skill.name.lowercase()
            clean.contains(sName) || sName.contains(clean)
        }
        return matched?.let {
            "I found a saved workflow '${it.name}'. Would you like me to run it?"
        }
    }

    private val candidateMap = ConcurrentHashMap<String, ProactiveCandidate>()

    /**
     * Posts a candidate proactive suggestion through the InterruptionGovernor.
     * Enforces quiet hours, frequency capping, confidence thresholds, and adaptive learning.
     */
    fun postProactiveSuggestion(
        candidate: ProactiveCandidate,
        currentHourOverride: Int? = null
    ): GovernorDecision {
        val decision = InterruptionGovernor.evaluate(candidate, currentHourOverride)
        if (!decision.isAllowed) {
            Log.d(TAG, "Proactive suggestion '${candidate.title}' suppressed by Governor: ${decision.reason}")
            return decision
        }

        candidateMap[candidate.id] = candidate
        val item = ScheduledProactiveItem(
            id = candidate.id,
            title = candidate.title,
            description = candidate.description,
            triggerTimeMs = candidate.timestamp,
            missionId = null
        )
        scheduledItems[item.id] = item

        if (candidate.deliveryType == SuggestionDeliveryType.OVERT_NOTIFICATION) {
            InterruptionGovernor.recordOvertDelivered(candidate.timestamp)
        }

        updateAlertsFlow()
        Log.i(TAG, "Delivered proactive suggestion: '${candidate.title}' [${candidate.category}]")
        return decision
    }

    /**
     * Event-driven handler for low battery transitions without busy waiting.
     */
    fun onLowBatteryDetected(
        batteryPct: Int,
        isCharging: Boolean,
        currentHourOverride: Int? = null
    ): GovernorDecision? {
        if (isCharging || batteryPct > 20) return null
        val candidate = ProactiveCandidate(
            id = "alert_low_battery",
            title = "Low Battery Warning ($batteryPct%)",
            description = "Battery is at $batteryPct%. Would you like to enable power saver mode?",
            category = ProactiveCategory.BATTERY_HEALTH,
            confidence = 0.95f,
            deliveryType = SuggestionDeliveryType.OVERT_NOTIFICATION
        )
        return postProactiveSuggestion(candidate, currentHourOverride)
    }

    /**
     * Records user feedback (accepted / dismissed) on a suggestion to adapt proactive frequency.
     */
    fun recordFeedback(id: String, accepted: Boolean) {
        val candidate = candidateMap[id]
        if (candidate != null) {
            InterruptionGovernor.recordFeedback(candidate.category, accepted)
        }
        dismissAlert(id)
    }

    /**
     * Dismisses or cancels a scheduled proactive alert.
     */
    fun dismissAlert(id: String) {
        val item = scheduledItems[id]
        if (item != null) {
            scheduledItems[id] = item.copy(isDismissed = true)
            runCatching { JarvisApp.instance.triggerRepository.cancelTrigger(id) }
            updateAlertsFlow()
            Log.i(TAG, "Dismissed proactive alert: $id")
        } else {
            runCatching { JarvisApp.instance.triggerRepository.cancelTrigger(id) }
        }
    }

    fun listActiveAlerts(): List<ScheduledProactiveItem> {
        val now = System.currentTimeMillis()
        return scheduledItems.values.filter { !it.isDismissed && it.triggerTimeMs <= now }
    }

    private fun updateAlertsFlow() {
        val now = System.currentTimeMillis()
        _proactiveAlerts.value = scheduledItems.values.filter { !it.isDismissed && it.triggerTimeMs <= now }
    }
}
