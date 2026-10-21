package com.jarvis.assistant.automation.proactive

import com.jarvis.assistant.automation.mission.MissionRisk
import com.jarvis.assistant.automation.mission.MissionStep
import java.util.UUID

enum class ProactivityLevel {
    OFF,
    CONSERVATIVE, // Only critical alerts (e.g. battery < 15%)
    BALANCED,     // Useful routine suggestions and follow-ups
    AGGRESSIVE    // Broad context-aware recommendations
}

enum class ProactiveCategory {
    BATTERY_HEALTH,
    SYSTEM_TELEMETRY,
    COMMUNICATION_FOLLOWUP,
    WORKFLOW_SUGGESTION,
    SCHEDULED_REMINDER
}

enum class SuggestionDeliveryType {
    SILENT_PREPARATION, // Preloads cache, warms models, no user sound or vibration
    OVERT_NOTIFICATION  // Notification or voice prompt
}

enum class SuggestionStatus {
    PENDING,
    ACCEPTED,
    DISMISSED,
    SUPPRESSED_BY_GOVERNOR
}

data class ProactiveConfig(
    val level: ProactivityLevel = ProactivityLevel.BALANCED,
    val quietHoursStartHour: Int = 22, // 10 PM
    val quietHoursEndHour: Int = 7,    // 7 AM
    val maxHourlySuggestions: Int = 3,
    val minConfidenceThreshold: Float = 0.70f,
    val enabledCategories: Set<ProactiveCategory> = ProactiveCategory.values().toSet(),
    val minIntervalBetweenOvertMs: Long = 10 * 60 * 1000L // 10 minutes
)

data class ProactiveCandidate(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val category: ProactiveCategory,
    val confidence: Float,
    val deliveryType: SuggestionDeliveryType = SuggestionDeliveryType.OVERT_NOTIFICATION,
    val suggestedStep: MissionStep? = null,
    val requiresApproval: Boolean = suggestedStep?.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL,
    val timestamp: Long = System.currentTimeMillis()
)

data class GovernorDecision(
    val isAllowed: Boolean,
    val reason: String,
    val isQuietHours: Boolean = false,
    val isThrottled: Boolean = false,
    val isLowConfidence: Boolean = false,
    val isUserSuppressed: Boolean = false
)
