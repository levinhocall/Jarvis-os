package com.jarvis.assistant.automation.routing

enum class TaskTaxonomy {
    DEVICE_CONTROL,
    SCREEN_INTERACTION,
    QUICK_FACTUAL,
    CODING_DEV,
    LONG_REASONING,
    AMBIENT_CONVERSATION
}

enum class PrivacyTier {
    STRICT_ON_DEVICE,
    PSEUDONYMIZED_CLOUD,
    PUBLIC_CLOUD_ALLOWED
}

enum class ModelTier {
    LOCAL_REFLEX,
    FAST_CLOUD,
    FRONTIER_REASONING
}

enum class CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

data class ProviderHealth(
    val providerId: String,
    val isHealthy: Boolean = true,
    val consecutiveErrors: Int = 0,
    val averageLatencyMs: Long = 250L,
    val lastErrorTimestamp: Long? = null,
    val circuitState: CircuitBreakerState = CircuitBreakerState.CLOSED,
    val successCount: Int = 0,
    val totalRequests: Int = 0,
    val successRate: Float = 1.0f
)

data class RoutingDecision(
    val taskType: TaskTaxonomy,
    val privacyTier: PrivacyTier,
    val selectedTier: ModelTier,
    val providerId: String,
    val specialistTarget: String,
    val reason: String,
    val fallbackTier: ModelTier? = null
)
