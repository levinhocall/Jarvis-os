package com.jarvis.assistant.automation.routing

import java.util.concurrent.ConcurrentHashMap

class CognitiveRouter {

    private val providerHealthMap = ConcurrentHashMap<String, ProviderHealth>()

    init {
        // Initialize default providers
        providerHealthMap["local_reflex"] = ProviderHealth("local_reflex", isHealthy = true, averageLatencyMs = 10L)
        providerHealthMap["gemini_flash"] = ProviderHealth("gemini_flash", isHealthy = true, averageLatencyMs = 280L)
        providerHealthMap["gemini_pro"] = ProviderHealth("gemini_pro", isHealthy = true, averageLatencyMs = 850L)
    }

    /**
     * Classifies a user query into TaskTaxonomy and PrivacyTier.
     */
    fun classifyTask(query: String): Pair<TaskTaxonomy, PrivacyTier> {
        val lower = query.lowercase().trim()

        // Privacy check
        val isPrivate = lower.contains("contact") ||
                lower.contains("pin") ||
                lower.contains("password") ||
                lower.contains("credential") ||
                lower.contains("phone number") ||
                lower.contains("my address")

        val privacyTier = if (isPrivate) PrivacyTier.STRICT_ON_DEVICE else PrivacyTier.PUBLIC_CLOUD_ALLOWED

        // Task Taxonomy classification
        val taxonomy = when {
            lower.contains("flashlight") ||
            lower.contains("bluetooth") ||
            lower.contains("wifi") ||
            lower.contains("volume") ||
            lower.contains("open app") ||
            lower.contains("turn on") ||
            lower.contains("turn off") -> TaskTaxonomy.DEVICE_CONTROL

            lower.contains("click") ||
            lower.contains("tap") ||
            lower.contains("scroll") ||
            lower.contains("screen") -> TaskTaxonomy.SCREEN_INTERACTION

            lower.contains("git") ||
            lower.contains("patch") ||
            lower.contains("compile") ||
            lower.contains("symbol") ||
            lower.contains("code") ||
            lower.contains("refactor") -> TaskTaxonomy.CODING_DEV

            lower.contains("plan") ||
            lower.contains("milestone") ||
            lower.contains("strategy") ||
            lower.contains("step-by-step") ||
            lower.contains("orchestrate") -> TaskTaxonomy.LONG_REASONING

            lower.startsWith("what is") ||
            lower.startsWith("when did") ||
            lower.startsWith("capital of") ||
            lower.startsWith("how many") -> TaskTaxonomy.QUICK_FACTUAL

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

        // 3. Provider Health Check
        val flashHealthy = isProviderHealthy("gemini_flash")
        val proHealthy = isProviderHealthy("gemini_pro")

        return when (taxonomy) {
            TaskTaxonomy.DEVICE_CONTROL -> {
                RoutingDecision(
                    taskType = taxonomy,
                    privacyTier = privacyTier,
                    selectedTier = ModelTier.LOCAL_REFLEX,
                    providerId = "local_reflex",
                    specialistTarget = "DeviceController",
                    reason = "Deterministic on-device action executes with zero latency."
                )
            }
            TaskTaxonomy.SCREEN_INTERACTION -> {
                RoutingDecision(
                    taskType = taxonomy,
                    privacyTier = privacyTier,
                    selectedTier = if (flashHealthy) ModelTier.FAST_CLOUD else ModelTier.LOCAL_REFLEX,
                    providerId = if (flashHealthy) "gemini_flash" else "local_reflex",
                    specialistTarget = "ScreenGroundingEngine",
                    reason = "Visual grounding routed to ScreenGroundingEngine.",
                    fallbackTier = ModelTier.LOCAL_REFLEX
                )
            }
            TaskTaxonomy.CODING_DEV -> {
                RoutingDecision(
                    taskType = taxonomy,
                    privacyTier = privacyTier,
                    selectedTier = if (proHealthy) ModelTier.FRONTIER_REASONING else ModelTier.FAST_CLOUD,
                    providerId = if (proHealthy) "gemini_pro" else "gemini_flash",
                    specialistTarget = "SoftwareEngineeringAgent",
                    reason = "Software engineering task requires high reasoning capacity."
                )
            }
            TaskTaxonomy.LONG_REASONING -> {
                if (proHealthy) {
                    RoutingDecision(
                        taskType = taxonomy,
                        privacyTier = privacyTier,
                        selectedTier = ModelTier.FRONTIER_REASONING,
                        providerId = "gemini_pro",
                        specialistTarget = "MissionEngine",
                        reason = "Multi-step mission planning dispatched to frontier reasoning model.",
                        fallbackTier = ModelTier.FAST_CLOUD
                    )
                } else if (flashHealthy) {
                    RoutingDecision(
                        taskType = taxonomy,
                        privacyTier = privacyTier,
                        selectedTier = ModelTier.FAST_CLOUD,
                        providerId = "gemini_flash",
                        specialistTarget = "MissionEngine",
                        reason = "Frontier model degraded: failed over to fast cloud model.",
                        fallbackTier = ModelTier.LOCAL_REFLEX
                    )
                } else {
                    RoutingDecision(
                        taskType = taxonomy,
                        privacyTier = privacyTier,
                        selectedTier = ModelTier.LOCAL_REFLEX,
                        providerId = "local_reflex",
                        specialistTarget = "OfflineFallbackHandler",
                        reason = "All cloud providers degraded: degraded to local heuristic planner."
                    )
                }
            }
            TaskTaxonomy.QUICK_FACTUAL -> {
                if (flashHealthy) {
                    RoutingDecision(
                        taskType = taxonomy,
                        privacyTier = privacyTier,
                        selectedTier = ModelTier.FAST_CLOUD,
                        providerId = "gemini_flash",
                        specialistTarget = "QuickFactEngine",
                        reason = "Fast cloud model selected for low-latency factual response."
                    )
                } else {
                    RoutingDecision(
                        taskType = taxonomy,
                        privacyTier = privacyTier,
                        selectedTier = ModelTier.LOCAL_REFLEX,
                        providerId = "local_reflex",
                        specialistTarget = "OfflineCacheEngine",
                        reason = "Cloud degraded: reading from local fact cache."
                    )
                }
            }
            TaskTaxonomy.AMBIENT_CONVERSATION -> {
                RoutingDecision(
                    taskType = taxonomy,
                    privacyTier = privacyTier,
                    selectedTier = if (flashHealthy) ModelTier.FAST_CLOUD else ModelTier.LOCAL_REFLEX,
                    providerId = if (flashHealthy) "gemini_flash" else "local_reflex",
                    specialistTarget = "GeminiLiveCoordinator",
                    reason = "Real-time bidirectional audio stream selected."
                )
            }
        }
    }

    /**
     * Updates provider health based on execution feedback.
     */
    @Synchronized
    fun recordProviderResult(providerId: String, latencyMs: Long, isSuccess: Boolean) {
        val current = providerHealthMap[providerId] ?: ProviderHealth(providerId)
        if (isSuccess) {
            val updatedAvg = (current.averageLatencyMs * 4 + latencyMs) / 5
            providerHealthMap[providerId] = current.copy(
                isHealthy = true,
                consecutiveErrors = 0,
                averageLatencyMs = updatedAvg
            )
        } else {
            val errors = current.consecutiveErrors + 1
            val isHealthy = errors < 3 // Trip circuit if 3 consecutive failures
            providerHealthMap[providerId] = current.copy(
                isHealthy = isHealthy,
                consecutiveErrors = errors,
                lastErrorTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun isProviderHealthy(providerId: String): Boolean {
        return providerHealthMap[providerId]?.isHealthy ?: true
    }

    fun getProviderHealth(providerId: String): ProviderHealth? {
        return providerHealthMap[providerId]
    }
}
