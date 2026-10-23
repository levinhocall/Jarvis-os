package com.jarvis.assistant.automation.routing

import java.util.concurrent.ConcurrentHashMap

class CognitiveRouter {

    companion object {
        const val CIRCUIT_BREAKER_THRESHOLD = 3
        const val CIRCUIT_COOLDOWN_MS = 30_000L
        const val EMA_ALPHA = 0.20
    }

    private val providerHealthMap = ConcurrentHashMap<String, ProviderHealth>()

    init {
        providerHealthMap["local_reflex"] = ProviderHealth("local_reflex", isHealthy = true, averageLatencyMs = 10L)
        providerHealthMap["gemini_flash"] = ProviderHealth("gemini_flash", isHealthy = true, averageLatencyMs = 280L)
        providerHealthMap["gemini_pro"] = ProviderHealth("gemini_pro", isHealthy = true, averageLatencyMs = 850L)
        providerHealthMap["openai_compatible"] = ProviderHealth("openai_compatible", isHealthy = true, averageLatencyMs = 350L)
    }

    /**
     * Classifies a user query into TaskTaxonomy and PrivacyTier using word boundaries.
     */
    fun classifyTask(query: String): Pair<TaskTaxonomy, PrivacyTier> {
        val lower = query.lowercase().trim()

        // Privacy check using word boundaries
        val privacyRegex = Regex("\\b(contact|contacts|pin|password|credential|credentials|phone number|my address|secret)\\b", RegexOption.IGNORE_CASE)
        val isPrivate = privacyRegex.containsMatchIn(lower)
        val privacyTier = if (isPrivate) PrivacyTier.STRICT_ON_DEVICE else PrivacyTier.PUBLIC_CLOUD_ALLOWED

        // Task Taxonomy classification with word boundaries and Hinglish support
        val devRegex = Regex("\\b(git|patch|compile|symbol|code|refactor|apk|termux|build|repo|repository|branch|commit|diff)\\b", RegexOption.IGNORE_CASE)
        val deviceRegex = Regex("\\b(flashlight|torch|bluetooth|wifi|volume|open app|turn on|turn off|band karo|chalao|jalao)\\b", RegexOption.IGNORE_CASE)
        val screenRegex = Regex("\\b(click|tap|scroll|screen|swipe|dabaao)\\b", RegexOption.IGNORE_CASE)
        val reasoningRegex = Regex("\\b(plan|milestone|strategy|step-by-step|orchestrate|analyze)\\b", RegexOption.IGNORE_CASE)

        val taxonomy = when {
            deviceRegex.containsMatchIn(lower) -> TaskTaxonomy.DEVICE_CONTROL
            screenRegex.containsMatchIn(lower) -> TaskTaxonomy.SCREEN_INTERACTION
            devRegex.containsMatchIn(lower) -> TaskTaxonomy.CODING_DEV
            reasoningRegex.containsMatchIn(lower) -> TaskTaxonomy.LONG_REASONING
            lower.startsWith("what is") || lower.startsWith("when did") ||
            lower.startsWith("capital of") || lower.startsWith("how many") ||
            lower.contains("kya hai") || lower.contains("batao") -> TaskTaxonomy.QUICK_FACTUAL
            else -> TaskTaxonomy.AMBIENT_CONVERSATION
        }

        return Pair(taxonomy, privacyTier)
    }

    /**
     * Routes the query based on classification, privacy, network state, and provider health.
     */
    fun route(query: String, isNetworkAvailable: Boolean = true): RoutingDecision {
        val (taxonomy, privacyTier) = classifyTask(query)

        // 1. Strict Privacy Guard: Privacy-sensitive queries stay strictly on-device!
        if (privacyTier == PrivacyTier.STRICT_ON_DEVICE) {
            return RoutingDecision(
                taskType = taxonomy,
                privacyTier = privacyTier,
                selectedTier = ModelTier.LOCAL_REFLEX,
                providerId = "local_reflex",
                specialistTarget = "OnDevicePrivacyGuard",
                reason = "Privacy constraint: Personal or sensitive credentials must be processed on-device."
            )
        }

        // 2. Offline Guard: If network is offline, route to local reflex
        if (!isNetworkAvailable) {
            return RoutingDecision(
                taskType = taxonomy,
                privacyTier = privacyTier,
                selectedTier = ModelTier.LOCAL_REFLEX,
                providerId = "local_reflex",
                specialistTarget = "OfflineFallbackHandler",
                reason = "Network unavailable: routed to local on-device capabilities."
            )
        }

        // 3. Fallback chains per taxonomy
        val fallbackChain = when (taxonomy) {
            TaskTaxonomy.DEVICE_CONTROL -> listOf("local_reflex")
            TaskTaxonomy.SCREEN_INTERACTION -> listOf("gemini_flash", "local_reflex")
            TaskTaxonomy.CODING_DEV -> listOf("gemini_pro", "openai_compatible", "gemini_flash", "local_reflex")
            TaskTaxonomy.LONG_REASONING -> listOf("gemini_pro", "gemini_flash", "local_reflex")
            TaskTaxonomy.QUICK_FACTUAL -> listOf("gemini_flash", "openai_compatible", "local_reflex")
            TaskTaxonomy.AMBIENT_CONVERSATION -> listOf("gemini_flash", "openai_compatible", "local_reflex")
        }

        val chosenProvider = fallbackChain.firstOrNull { isProviderHealthy(it) } ?: "local_reflex"

        val selectedTier = when (chosenProvider) {
            "gemini_pro" -> ModelTier.FRONTIER_REASONING
            "gemini_flash", "openai_compatible" -> ModelTier.FAST_CLOUD
            else -> ModelTier.LOCAL_REFLEX
        }

        val target = when (taxonomy) {
            TaskTaxonomy.DEVICE_CONTROL -> "DeviceController"
            TaskTaxonomy.SCREEN_INTERACTION -> "ScreenGroundingEngine"
            TaskTaxonomy.CODING_DEV -> "SoftwareEngineeringAgent"
            TaskTaxonomy.LONG_REASONING -> "MissionEngine"
            TaskTaxonomy.QUICK_FACTUAL -> if (selectedTier == ModelTier.LOCAL_REFLEX) "OfflineCacheEngine" else "QuickFactEngine"
            TaskTaxonomy.AMBIENT_CONVERSATION -> if (selectedTier == ModelTier.LOCAL_REFLEX) "OfflineFallbackHandler" else "GeminiLiveCoordinator"
        }

        val reason = when {
            chosenProvider != fallbackChain.first() && fallbackChain.first() == "gemini_pro" ->
                "Frontier model degraded: failed over to $chosenProvider."
            chosenProvider != fallbackChain.first() ->
                "Primary provider degraded: failed over to $chosenProvider."
            else ->
                "Selected '$chosenProvider' via fallback chain with measured health."
        }

        return RoutingDecision(
            taskType = taxonomy,
            privacyTier = privacyTier,
            selectedTier = selectedTier,
            providerId = chosenProvider,
            specialistTarget = target,
            reason = reason,
            fallbackTier = if (selectedTier != ModelTier.LOCAL_REFLEX) ModelTier.LOCAL_REFLEX else null
        )
    }

    /**
     * Updates provider health, calculates Exponential Moving Average (EMA) latency,
     * tracks success rate, and manages circuit breaker state transitions.
     */
    @Synchronized
    fun recordProviderResult(providerId: String, latencyMs: Long, isSuccess: Boolean) {
        val current = providerHealthMap[providerId] ?: ProviderHealth(providerId)
        val total = current.totalRequests + 1

        if (isSuccess) {
            val updatedEma = (EMA_ALPHA * latencyMs + (1.0 - EMA_ALPHA) * current.averageLatencyMs).toLong()
            val successes = current.successCount + 1
            providerHealthMap[providerId] = current.copy(
                isHealthy = true,
                consecutiveErrors = 0,
                averageLatencyMs = updatedEma,
                circuitState = CircuitBreakerState.CLOSED,
                successCount = successes,
                totalRequests = total,
                successRate = successes.toFloat() / total
            )
        } else {
            val errors = current.consecutiveErrors + 1
            val now = System.currentTimeMillis()
            val tripped = errors >= CIRCUIT_BREAKER_THRESHOLD || current.circuitState == CircuitBreakerState.HALF_OPEN
            val newState = if (tripped) CircuitBreakerState.OPEN else CircuitBreakerState.CLOSED
            providerHealthMap[providerId] = current.copy(
                isHealthy = !tripped,
                consecutiveErrors = errors,
                lastErrorTimestamp = now,
                circuitState = newState,
                totalRequests = total,
                successRate = current.successCount.toFloat() / total
            )
        }
    }

    /**
     * Checks if provider is healthy with circuit breaker half-open probe support.
     */
    @Synchronized
    fun isProviderHealthy(providerId: String): Boolean {
        if (providerId == "local_reflex") return true
        val health = providerHealthMap[providerId] ?: return true

        when (health.circuitState) {
            CircuitBreakerState.CLOSED -> return health.isHealthy
            CircuitBreakerState.OPEN -> {
                val lastErr = health.lastErrorTimestamp ?: 0L
                val elapsed = System.currentTimeMillis() - lastErr
                if (elapsed > CIRCUIT_COOLDOWN_MS) {
                    // Transition to HALF_OPEN to allow trial probe
                    providerHealthMap[providerId] = health.copy(
                        circuitState = CircuitBreakerState.HALF_OPEN,
                        isHealthy = true
                    )
                    return true
                }
                return false
            }
            CircuitBreakerState.HALF_OPEN -> return true
        }
    }

    fun getProviderHealth(providerId: String): ProviderHealth? {
        return providerHealthMap[providerId]
    }
}
