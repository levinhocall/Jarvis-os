package com.jarvis.assistant.automation.offline

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.reflex.OfflineReflexExecutor
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
    suspend fun handleCommandOffline(input: String, context: Context? = null): OfflineExecutionResult {
        val lower = input.lowercase().trim()

        // 1. Local Device Control — only report success after a real platform action.
        if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("volume") ||
            lower.contains("brightness")) {
            val reflex = context?.let { OfflineReflexExecutor.tryExecuteReflex(input, it, null) }
            if (reflex?.handled == true && reflex.succeeded) {
                return OfflineExecutionResult.ExecutedLocally(
                    actionType = "DEVICE_HARDWARE",
                    userFeedback = reflex.spokenResponse,
                    outputData = mapOf("action" to reflex.actionName, "verified" to true)
                )
            }
            return OfflineExecutionResult.DegradedExplanation(
                unsupportedFeature = "Device hardware control",
                userFeedback = "The device action was not executed because the Android context or a matching hardware handler is unavailable.",
                availableOfflineAlternatives = listOf("Open JARVIS and retry the device-control command.")
            )
        }

        // 2. Local Notes and Memory
        if (lower.startsWith("remember") || lower.startsWith("note") || lower.contains("yaad rakhna")) {
            val noteText = input.replace(Regex("^(remember|note|yaad rakhna)\\s*", RegexOption.IGNORE_CASE), "").trim()
            val noteKey = "note_${System.currentTimeMillis()}"
            cacheFact(noteKey, noteText)
            val app = context?.applicationContext as? JarvisApp
            if (app != null) {
                val saved = app.memoryManager.rememberFact(input)
                return OfflineExecutionResult.ExecutedLocally(
                    actionType = "LOCAL_NOTE",
                    userFeedback = saved,
                    outputData = mapOf("key" to noteKey, "content" to noteText, "persistent" to true)
                )
            }
            return OfflineExecutionResult.ExecutedLocally(
                actionType = "LOCAL_NOTE",
                userFeedback = "Note is available in this process's temporary offline cache only; it has not been saved to persistent storage.",
                outputData = mapOf("key" to noteKey, "content" to noteText, "persistent" to false)
            )
        }

        // 3. Timers and alarms are not reported as scheduled until a real scheduler is connected.
        if (lower.contains("timer") || lower.contains("alarm")) {
            return OfflineExecutionResult.DegradedExplanation(
                unsupportedFeature = "Offline timer scheduling",
                userFeedback = "No timer or alarm was scheduled. The offline timer scheduler is not connected yet.",
                availableOfflineAlternatives = listOf("Use the device Clock app to set a timer or alarm.")
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
                userFeedback = "Your consequential request is held in this process's temporary queue and has not been executed. It will be lost if the app process stops; fresh approval is required before any later execution.",
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
                userFeedback = "Search request is held in this process's temporary queue only. It has not been executed and automatic sync is not currently connected.",
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
    fun processSyncQueue(
        isOnline: Boolean,
        executor: ((SyncQueueItem) -> Boolean)? = null
    ): SyncResultSummary {
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
                val updated = item.copy(syncStatus = SyncStatus.AWAITING_CONFIRMATION)
                remaining.add(updated)
                pendingConfirmation++
                details.add("Action '${item.actionType}' requires fresh user confirmation before execution.")
                continue
            }

            val syncHandler = executor
            if (syncHandler == null) {
                remaining.add(item)
                details.add("'${item.actionType}' remains pending: no real sync executor is registered; nothing was fetched or executed.")
                continue
            }

            val didSync = try {
                syncHandler(item)
            } catch (_: Exception) {
                false
            }
            if (didSync) {
                synced++
                details.add("'${item.actionType}' was processed by the registered sync executor.")
            } else {
                failed++
                remaining.add(item.copy(syncStatus = SyncStatus.FAILED))
                details.add("'${item.actionType}' sync failed and remains queued for retry.")
            }
        }

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
            "Timers & Alarms" to false,
            "Offline Rules & Heuristics" to true,
            "Web Search & Scraping" to false,
            "Cloud LLM Reasoning" to false,
            "Real-Time Live Streaming Audio" to false
        )
    }
}
