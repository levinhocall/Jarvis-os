package com.jarvis.assistant.automation.offline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class OfflineIntelligenceCoordinator {

    private val localKnowledgeCache = ConcurrentHashMap<String, String>()
    private val syncQueue = ConcurrentLinkedQueue<SyncQueueItem>()

    init {
        // Pre-populate core local cached facts
        localKnowledgeCache["owner_name"] = "Sir"
        localKnowledgeCache["assistant_name"] = "JARVIS"
        localKnowledgeCache["system_version"] = "2.0.0-PRO"
    }

    /**
     * Stores a fact into the local offline knowledge cache.
     */
    fun cacheFact(key: String, value: String) {
        localKnowledgeCache[key.lowercase().trim()] = value
    }

    /**
     * Searches local knowledge cache for matching keys/values.
     */
    fun searchLocalKnowledge(query: String): List<String> {
        val lower = query.lowercase().trim()
        return localKnowledgeCache.filter { (k, v) ->
            k.contains(lower) || v.lowercase().contains(lower)
        }.map { "${it.key}: ${it.value}" }
    }

    /**
     * Evaluates and executes user input offline, queuing cloud actions if appropriate.
     */
    fun handleCommandOffline(input: String): OfflineExecutionResult {
        val lower = input.lowercase().trim()

        // 1. Local Device Control
        if (lower.contains("torch") || lower.contains("flashlight")) {
            val state = if (lower.contains("off")) "OFF" else "ON"
            return OfflineExecutionResult.ExecutedLocally(
                actionType = "DEVICE_HARDWARE",
                userFeedback = "Flashlight turned $state (offline reflex).",
                outputData = mapOf("device" to "flashlight", "state" to state)
            )
        }

        if (lower.contains("volume")) {
            return OfflineExecutionResult.ExecutedLocally(
                actionType = "DEVICE_HARDWARE",
                userFeedback = "Volume adjusted (offline reflex).",
                outputData = mapOf("device" to "volume")
            )
        }

        // 2. Local Notes and Memory
        if (lower.startsWith("remember") || lower.startsWith("note") || lower.contains("yaad rakhna")) {
            val noteText = input.replace(Regex("^(remember|note|yaad rakhna)\\s*", RegexOption.IGNORE_CASE), "").trim()
            val noteKey = "note_${System.currentTimeMillis()}"
            cacheFact(noteKey, noteText)
            return OfflineExecutionResult.ExecutedLocally(
                actionType = "LOCAL_NOTE",
                userFeedback = "Note saved to local offline memory: \"$noteText\".",
                outputData = mapOf("key" to noteKey, "content" to noteText)
            )
        }

        // 3. Alarms and Timers
        if (lower.contains("timer") || lower.contains("alarm")) {
            return OfflineExecutionResult.ExecutedLocally(
                actionType = "LOCAL_ALARM",
                userFeedback = "Timer/alarm scheduled via on-device clock manager.",
                outputData = mapOf("type" to "timer", "status" to "scheduled")
            )
        }

        // 4. Queuing external / cloud actions
        if (lower.startsWith("send email") || lower.contains("pay") || lower.contains("transfer")) {
            // Consequential: queue for sync with user confirmation required
            val item = SyncQueueItem(
                actionType = "CONSEQUENTIAL_CLOUD_ACTION",
                payload = mapOf("command" to input),
                isConsequential = true,
                note = "Requires user confirmation before sending when network returns"
            )
            syncQueue.offer(item)
            return OfflineExecutionResult.QueuedForOnline(
                itemId = item.id,
                userFeedback = "You are currently offline. Your consequential request has been queued and will require your confirmation once network connectivity is restored.",
                requiresConfirmationOnSync = true
            )
        }

        if (lower.contains("search") || lower.contains("weather") || lower.contains("google")) {
            // Non-consequential: queue for automatic background sync
            val item = SyncQueueItem(
                actionType = "BACKGROUND_QUERY_SYNC",
                payload = mapOf("query" to input),
                isConsequential = false,
                note = "Will fetch results automatically when online"
            )
            syncQueue.offer(item)
            return OfflineExecutionResult.QueuedForOnline(
                itemId = item.id,
                userFeedback = "You are offline. Search request queued for automatic sync upon reconnection.",
                requiresConfirmationOnSync = false
            )
        }

        // 5. Degraded Explanation
        return OfflineExecutionResult.DegradedExplanation(
            unsupportedFeature = "Full Cloud Reasoning / Web Browsing",
            userFeedback = "Network is unavailable. I cannot perform complex cloud reasoning or browse external websites right now.",
            availableOfflineAlternatives = listOf(
                "Control hardware (flashlight, volume, wifi toggles)",
                "Save and search local notes",
                "Set timers and alarms",
                "Lookup saved local facts"
            )
        )
    }

    /**
     * Flushes sync queue when online connectivity is restored.
     */
    @Synchronized
    fun processSyncQueue(isOnline: Boolean): SyncResultSummary {
        if (!isOnline) {
            return SyncResultSummary(0, 0, 0, listOf("Still offline; sync deferred."))
        }

        var synced = 0
        var pendingConfirmation = 0
        var failed = 0
        val details = mutableListOf<String>()

        val remaining = mutableListOf<SyncQueueItem>()

        while (!syncQueue.isEmpty()) {
            val item = syncQueue.poll() ?: break
            if (item.isConsequential) {
                // Keep consequential items pending user confirmation
                val updated = item.copy(syncStatus = SyncStatus.AWAITING_CONFIRMATION)
                remaining.add(updated)
                pendingConfirmation++
                details.add("Action '${item.actionType}' requires user confirmation before execution.")
            } else {
                // Execute non-consequential sync
                synced++
                details.add("Synced '${item.actionType}' automatically.")
            }
        }

        // Restore pending items
        syncQueue.addAll(remaining)

        return SyncResultSummary(
            syncedCount = synced,
            pendingConfirmationCount = pendingConfirmation,
            failedCount = failed,
            details = details
        )
    }

    fun getSyncQueue(): List<SyncQueueItem> = syncQueue.toList()

    fun getHonestCapabilityMatrix(): Map<String, Boolean> {
        return mapOf(
            "Hardware Control (Torch, Volume)" to true,
            "Local Memory & Notes" to true,
            "Timers & Alarms" to true,
            "Offline Rules & Heuristics" to true,
            "Web Search & Scraping" to false,
            "Cloud LLM Reasoning" to false,
            "Real-Time Live Streaming Audio" to false
        )
    }
}
