package com.jarvis.assistant.automation.proactive

import android.util.Log
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Interruption Governor for JARVIS OS.
 * Governs all proactive notifications and suggestions to protect user focus and minimize battery drain.
 * Enforces quiet hours, hourly frequency caps, minimum confidence scoring, and adaptive dismissal learning.
 */
object InterruptionGovernor {
    private const val TAG = "InterruptionGovernor"

    @Volatile
    var config: ProactiveConfig = ProactiveConfig()

    private val overtNotificationTimestamps = CopyOnWriteArrayList<Long>()
    private val consecutiveDismissalsByCategory = ConcurrentHashMap<ProactiveCategory, Int>()
    private val categoryAcceptanceCount = ConcurrentHashMap<ProactiveCategory, Int>()
    private val categoryDismissalCount = ConcurrentHashMap<ProactiveCategory, Int>()

    /**
     * Evaluates whether a candidate proactive suggestion is permitted to interrupt the user.
     */
    fun evaluate(candidate: ProactiveCandidate, currentHourOverride: Int? = null): GovernorDecision {
        // 1. Silent preparations bypass overt interruption checks
        if (candidate.deliveryType == SuggestionDeliveryType.SILENT_PREPARATION) {
            return GovernorDecision(
                isAllowed = true,
                reason = "Silent background preparation allowed without user interruption."
            )
        }

        // 2. Proactivity Global State
        if (config.level == ProactivityLevel.OFF) {
            return GovernorDecision(
                isAllowed = false,
                reason = "Proactive suggestions are globally disabled by user."
            )
        }

        // 3. Category Enablement
        if (!config.enabledCategories.contains(candidate.category)) {
            return GovernorDecision(
                isAllowed = false,
                reason = "Category '${candidate.category}' is disabled in user preferences.",
                isUserSuppressed = true
            )
        }

        // 4. Quiet Hours Check
        val currentHour = currentHourOverride ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (isWithinQuietHours(currentHour, config.quietHoursStartHour, config.quietHoursEndHour)) {
            return GovernorDecision(
                isAllowed = false,
                reason = "Current time ($currentHour:00) falls within quiet hours (${config.quietHoursStartHour}:00 - ${config.quietHoursEndHour}:00).",
                isQuietHours = true
            )
        }

        // 5. Confidence Thresholding
        val effectiveThreshold = when (config.level) {
            ProactivityLevel.CONSERVATIVE -> maxOf(config.minConfidenceThreshold, 0.85f)
            ProactivityLevel.BALANCED -> config.minConfidenceThreshold
            ProactivityLevel.AGGRESSIVE -> (config.minConfidenceThreshold - 0.15f).coerceAtLeast(0.50f)
            ProactivityLevel.OFF -> 1.0f
        }
        if (candidate.confidence < effectiveThreshold) {
            return GovernorDecision(
                isAllowed = false,
                reason = "Candidate confidence (${candidate.confidence}) is below threshold ($effectiveThreshold).",
                isLowConfidence = true
            )
        }

        // 6. Adaptive Dismissal Learning: Suppress categories repeatedly dismissed
        val consecutiveDismissals = consecutiveDismissalsByCategory[candidate.category] ?: 0
        if (consecutiveDismissals >= 3 && candidate.confidence < 0.95f) {
            return GovernorDecision(
                isAllowed = false,
                reason = "Category '${candidate.category}' suppressed due to $consecutiveDismissals consecutive dismissals.",
                isUserSuppressed = true
            )
        }

        // 7. Frequency & Throttling
        val now = System.currentTimeMillis()
        pruneOldTimestamps(now)

        if (overtNotificationTimestamps.size >= config.maxHourlySuggestions) {
            return GovernorDecision(
                isAllowed = false,
                reason = "Hourly overt suggestion limit (${config.maxHourlySuggestions}/hr) reached.",
                isThrottled = true
            )
        }

        val lastOvert = overtNotificationTimestamps.lastOrNull()
        if (lastOvert != null && (now - lastOvert) < config.minIntervalBetweenOvertMs) {
            val cooldownRemSec = (config.minIntervalBetweenOvertMs - (now - lastOvert)) / 1000
            return GovernorDecision(
                isAllowed = false,
                reason = "Inter-suggestion cooldown active ($cooldownRemSec seconds remaining).",
                isThrottled = true
            )
        }

        return GovernorDecision(
            isAllowed = true,
            reason = "Interruption governor approved suggestion."
        )
    }

    /**
     * Records that an overt interruption was delivered.
     */
    fun recordOvertDelivered(now: Long = System.currentTimeMillis()) {
        overtNotificationTimestamps.add(now)
        Log.d(TAG, "Recorded overt proactive notification delivery at $now")
    }

    /**
     * Records user feedback on a suggestion to adapt proactive frequency.
     */
    fun recordFeedback(category: ProactiveCategory, accepted: Boolean) {
        if (accepted) {
            categoryAcceptanceCount.compute(category) { _, v -> (v ?: 0) + 1 }
            consecutiveDismissalsByCategory[category] = 0
            Log.i(TAG, "Feedback accepted for category $category")
        } else {
            categoryDismissalCount.compute(category) { _, v -> (v ?: 0) + 1 }
            consecutiveDismissalsByCategory.compute(category) { _, v -> (v ?: 0) + 1 }
            Log.i(TAG, "Feedback dismissed for category $category (consecutive: ${consecutiveDismissalsByCategory[category]})")
        }
    }

    fun isWithinQuietHours(hour: Int, startHour: Int, endHour: Int): Boolean {
        return if (startHour > endHour) {
            // e.g. 22:00 to 07:00 (crosses midnight)
            hour >= startHour || hour < endHour
        } else {
            // e.g. 01:00 to 06:00
            hour in startHour until endHour
        }
    }

    private fun pruneOldTimestamps(now: Long) {
        val oneHourAgo = now - 3600_000L
        overtNotificationTimestamps.removeIf { it < oneHourAgo }
    }

    fun resetState() {
        overtNotificationTimestamps.clear()
        consecutiveDismissalsByCategory.clear()
        categoryAcceptanceCount.clear()
        categoryDismissalCount.clear()
        config = ProactiveConfig()
    }
}
