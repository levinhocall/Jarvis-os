package com.jarvis.assistant.core.world

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

sealed class ScreenVerificationResult {
    object Matches : ScreenVerificationResult()
    data class Stale(val reason: String) : ScreenVerificationResult()
    data class Mismatch(val reason: String) : ScreenVerificationResult()
}

object WorldModelCoordinator {
    private const val TAG = "WorldModelCoordinator"
    private const val SCREEN_TTL_MS = 10000L // 10s freshness for screen layout

    private var activeGoal: ActiveGoal? = null
    private var currentScreenContext: StateObservation<ScreenContext>? = null
    private val generalObservations = ConcurrentHashMap<String, StateObservation<*>>()

    fun setActiveGoal(goal: ActiveGoal?) {
        activeGoal = goal
        Log.i(TAG, "Active goal set: ${goal?.description}")
    }

    fun getActiveGoal(): ActiveGoal? = activeGoal

    /**
     * Records a freshly observed screen snapshot directly from Accessibility node tree.
     */
    fun updateScreenObservation(
        foregroundPackage: String,
        visibleLabels: List<String>,
        containsPasswordFields: Boolean = false
    ) {
        val sanitizedLabels = if (containsPasswordFields) {
            visibleLabels.filterNot { it.contains("password", ignoreCase = true) || it.contains("pin", ignoreCase = true) }
        } else {
            visibleLabels
        }

        val context = ScreenContext(
            foregroundPackage = foregroundPackage,
            visibleElementLabels = sanitizedLabels,
            containsPasswordFields = containsPasswordFields
        )

        currentScreenContext = StateObservation(
            key = "screen_context",
            value = context,
            nature = ObservationNature.VERIFIED_FACT,
            source = "AccessibilityNodeInfo",
            ttlMs = SCREEN_TTL_MS
        )
        Log.d(TAG, "Screen context updated for package: $foregroundPackage (${sanitizedLabels.size} elements)")
    }

    fun getScreenContext(): ScreenContext? {
        val obs = currentScreenContext ?: return null
        return if (obs.isStale) null else obs.value
    }

    /**
     * Navigation immediately invalidates screen observations and all unconfirmed hypotheses.
     */
    fun onNavigation(newPackage: String) {
        Log.i(TAG, "Navigation detected to '$newPackage'. Invalidating stale screen observation.")
        currentScreenContext = null

        // Invalidate inferred hypotheses
        val hypothesesKeys = generalObservations.filter { it.value.nature == ObservationNature.INFERRED_HYPOTHESIS }.keys
        hypothesesKeys.forEach { generalObservations.remove(it) }
    }

    /**
     * Records an inferred hypothesis from LLM reasoning (Must NOT be treated as authoritative).
     */
    fun recordHypothesis(key: String, value: Any, source: String, ttlMs: Long = 20000L) {
        generalObservations[key] = StateObservation(
            key = key,
            value = value,
            nature = ObservationNature.INFERRED_HYPOTHESIS,
            source = source,
            ttlMs = ttlMs
        )
    }

    /**
     * Records a ground-truth verified fact from device system services.
     */
    fun recordFact(key: String, value: Any, source: String, ttlMs: Long = 60000L) {
        generalObservations[key] = StateObservation(
            key = key,
            value = value,
            nature = ObservationNature.VERIFIED_FACT,
            source = source,
            ttlMs = ttlMs
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getObservation(key: String): StateObservation<T>? {
        val obs = generalObservations[key] ?: return null
        if (obs.isStale) {
            generalObservations.remove(key)
            return null
        }
        return obs as StateObservation<T>
    }

    /**
     * Closed-loop verification of expected screen state against actual live observation.
     */
    fun verifyScreenExpectation(
        expectedPackage: String,
        requiredElementSubstrings: List<String> = emptyList()
    ): ScreenVerificationResult {
        val obs = currentScreenContext
        if (obs == null || obs.isStale) {
            return ScreenVerificationResult.Stale("Screen observation is missing or stale (>10s old). Re-observation required.")
        }

        val screen = obs.value
        if (!screen.foregroundPackage.equals(expectedPackage, ignoreCase = true)) {
            return ScreenVerificationResult.Mismatch(
                "Expected foreground package '$expectedPackage', but observed '${screen.foregroundPackage}'"
            )
        }

        val missingElements = requiredElementSubstrings.filter { required ->
            screen.visibleElementLabels.none { it.contains(required, ignoreCase = true) }
        }

        if (missingElements.isNotEmpty()) {
            return ScreenVerificationResult.Mismatch(
                "Missing expected UI elements on screen: ${missingElements.joinToString()}"
            )
        }

        return ScreenVerificationResult.Matches
    }

    fun purgeExpiredObservations() {
        val expired = generalObservations.filter { it.value.isStale }.keys
        expired.forEach { generalObservations.remove(it) }
        if (currentScreenContext?.isStale == true) {
            currentScreenContext = null
        }
    }

    fun resetForTest() {
        activeGoal = null
        currentScreenContext = null
        generalObservations.clear()
    }
}
