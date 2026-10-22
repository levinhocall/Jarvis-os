package com.jarvis.assistant.data.personalization

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object AdaptivePersonalizationEngine {
    private const val TAG = "AdaptivePersonalization"

    private val preferences = ConcurrentHashMap<String, PersonalizedPreference>()

    /**
     * Sets an explicit user preference (Highest priority, 1.0 confidence, confirmed).
     */
    fun setExplicitPreference(
        key: String,
        value: String,
        category: PreferenceCategory
    ): PersonalizedPreference {
        val pref = PersonalizedPreference(
            key = key,
            value = value,
            category = category,
            source = PreferenceSource.EXPLICIT_USER,
            confidence = 1.0f,
            isConfirmedByUser = true,
            isEnabled = true,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        preferences[key] = pref
        Log.i(TAG, "Explicit preference stored: $key = $value")
        return pref
    }

    /**
     * Records an inferred behavioral pattern. Starts low confidence unless reinforced or confirmed.
     */
    fun recordInferredPreference(
        key: String,
        value: String,
        category: PreferenceCategory,
        initialConfidence: Float = 0.5f
    ): PersonalizedPreference {
        val existing = preferences[key]

        // Do not overwrite explicit user preference with inferred pattern!
        if (existing != null && existing.source == PreferenceSource.EXPLICIT_USER) {
            Log.d(TAG, "Ignored inferred preference for $key; explicit user setting takes precedence.")
            return existing
        }

        val clampedConfidence = initialConfidence.coerceIn(0.1f, 0.75f)
        val pref = existing?.copy(
            value = value,
            confidence = clampedConfidence,
            lastUpdatedTimestamp = System.currentTimeMillis()
        ) ?: PersonalizedPreference(
            key = key,
            value = value,
            category = category,
            source = PreferenceSource.INFERRED_PATTERN,
            confidence = clampedConfidence,
            isConfirmedByUser = false,
            isEnabled = true,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )

        preferences[key] = pref
        Log.i(TAG, "Inferred preference recorded: $key = $value (confidence: $clampedConfidence)")
        return pref
    }

    /**
     * Confirms an inferred preference with user approval, raising confidence to 1.0.
     */
    fun confirmPreference(key: String): Boolean {
        val existing = preferences[key] ?: return false
        preferences[key] = existing.copy(
            isConfirmedByUser = true,
            confidence = 1.0f,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        return true
    }

    /**
     * Records user feedback (positive, negative, or explicit correction).
     */
    fun recordFeedback(feedback: UserFeedback) {
        val existing = preferences[feedback.preferenceKey] ?: return

        if (feedback.userCorrection != null) {
            // User provided an explicit correction: convert to explicit user preference
            setExplicitPreference(
                key = feedback.preferenceKey,
                value = feedback.userCorrection,
                category = existing.category
            )
            return
        }

        if (feedback.isPositive) {
            val newCount = existing.positiveFeedbackCount + 1
            val newConfidence = (existing.confidence + 0.15f).coerceAtMost(1.0f)
            preferences[feedback.preferenceKey] = existing.copy(
                positiveFeedbackCount = newCount,
                confidence = newConfidence,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        } else {
            val newCount = existing.negativeFeedbackCount + 1
            val newConfidence = (existing.confidence - 0.25f).coerceAtLeast(0.0f)
            val shouldDisable = newCount >= 2 || newConfidence < 0.25f

            preferences[feedback.preferenceKey] = existing.copy(
                negativeFeedbackCount = newCount,
                confidence = newConfidence,
                isEnabled = !shouldDisable,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
            if (shouldDisable) {
                Log.w(TAG, "Preference '${feedback.preferenceKey}' disabled due to repeated negative feedback.")
            }
        }
    }

    /**
     * Resolves the effective preference.
     * CRITICAL RULE: Current explicit user command ALWAYS overrides stored or inferred preference!
     */
    fun resolveEffectivePreference(
        key: String,
        currentExplicitCommandOverride: String? = null
    ): String? {
        if (!currentExplicitCommandOverride.isNullOrBlank()) {
            // Explicit in-session command takes absolute precedence
            return currentExplicitCommandOverride
        }

        val pref = preferences[key] ?: return null
        return if (pref.isActionable) pref.value else null
    }

    fun getAllPreferences(): List<PersonalizedPreference> = preferences.values.toList()

    fun getPreference(key: String): PersonalizedPreference? = preferences[key]

    fun togglePreference(key: String, enabled: Boolean): Boolean {
        val existing = preferences[key] ?: return false
        preferences[key] = existing.copy(
            isEnabled = enabled,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        return true
    }

    fun deletePreference(key: String): Boolean {
        return preferences.remove(key) != null
    }

    fun clearAllInferred() {
        val keysToRemove = preferences.filter { it.value.source == PreferenceSource.INFERRED_PATTERN && !it.value.isConfirmedByUser }.keys
        keysToRemove.forEach { preferences.remove(it) }
        Log.i(TAG, "Cleared ${keysToRemove.size} unconfirmed inferred preferences.")
    }

    fun clearAllForTest() {
        preferences.clear()
    }
}
