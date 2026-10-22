package com.jarvis.assistant.data.personalization

enum class PreferenceSource {
    EXPLICIT_USER,
    INFERRED_PATTERN,
    FEEDBACK_LEARNED
}

enum class PreferenceCategory {
    COMMUNICATION, // preferred app (WhatsApp vs SMS vs Call)
    MEDIA,         // preferred player, genre, volume
    PRODUCTIVITY,  // preferred note app, browser
    RESPONSE_STYLE // concise, formal, Tony Stark witty
}

data class PersonalizedPreference(
    val key: String,
    val value: String,
    val category: PreferenceCategory,
    val source: PreferenceSource,
    val confidence: Float = 1.0f,
    val isConfirmedByUser: Boolean = false,
    val isEnabled: Boolean = true,
    val positiveFeedbackCount: Int = 0,
    val negativeFeedbackCount: Int = 0,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    /**
     * Inferred preferences are low-confidence unless explicitly confirmed or reinforced by repeated positive feedback.
     */
    val isActionable: Boolean
        get() = isEnabled && (source == PreferenceSource.EXPLICIT_USER || isConfirmedByUser || (confidence >= 0.8f && positiveFeedbackCount >= 3))
}

data class UserFeedback(
    val preferenceKey: String,
    val isPositive: Boolean,
    val userCorrection: String? = null
)
