package com.jarvis.assistant.automation.offline

import java.util.UUID

enum class SyncStatus {
    PENDING,
    SYNCED,
    AWAITING_CONFIRMATION,
    FAILED
}

data class SyncQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val actionType: String,
    val payload: Map<String, String> = emptyMap(),
    val isConsequential: Boolean = false,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val note: String = ""
)

sealed class OfflineExecutionResult {
    data class ExecutedLocally(
        val actionType: String,
        val userFeedback: String,
        val outputData: Map<String, Any?> = emptyMap()
    ) : OfflineExecutionResult()

    data class QueuedForOnline(
        val itemId: String,
        val userFeedback: String,
        val requiresConfirmationOnSync: Boolean
    ) : OfflineExecutionResult()

    data class DegradedExplanation(
        val unsupportedFeature: String,
        val userFeedback: String,
        val availableOfflineAlternatives: List<String>
    ) : OfflineExecutionResult()
}

data class SyncResultSummary(
    val syncedCount: Int,
    val pendingConfirmationCount: Int,
    val failedCount: Int,
    val details: List<String>
)
