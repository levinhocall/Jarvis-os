package com.jarvis.assistant.core.world

enum class ObservationNature {
    VERIFIED_FACT,      // Directly observed via Accessibility or System Service
    INFERRED_HYPOTHESIS // Deduced by LLM reasoning, not yet verified on-device
}

data class StateObservation<T>(
    val key: String,
    val value: T,
    val nature: ObservationNature,
    val source: String,
    val observedTimestamp: Long = System.currentTimeMillis(),
    val ttlMs: Long = 15000L // Default 15s freshness window
) {
    val isStale: Boolean
        get() = (System.currentTimeMillis() - observedTimestamp) > ttlMs
}

/**
 * Coarse, privacy-filtered summary of what is on screen, kept by the world model (package, visible
 * labels, whether a password field is present). Not the accessibility-tree snapshot used for
 * grounding and clicking: see `automation.grounding.ScreenContext` for that richer model.
 */
data class WorldScreenSummary(
    val foregroundPackage: String,
    val visibleElementLabels: List<String>,
    val containsPasswordFields: Boolean = false,
    val observationTimestamp: Long = System.currentTimeMillis()
)

data class ActiveGoal(
    val goalId: String,
    val description: String,
    val constraints: List<String> = emptyList(),
    val expectedOutcome: String? = null
)
