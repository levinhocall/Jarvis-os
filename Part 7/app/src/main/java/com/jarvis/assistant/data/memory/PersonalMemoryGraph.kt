package com.jarvis.assistant.data.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class MemorySensitivity {
    PUBLIC,
    PREFERENCE,
    WORKFLOW_REFERENCE,
    CONFIDENTIAL
}

enum class MemoryProvenance {
    EXPLICIT_USER_STATEMENT,
    CONVERSATION_INFERENCE,
    MISSION_OUTCOME,
    DOCUMENT_EXTRACTION,
    REUSABLE_SKILL
}

data class GraphMemoryNode(
    val id: String = java.util.UUID.randomUUID().toString(),
    val key: String,
    val value: String,
    val category: String,
    val provenance: MemoryProvenance,
    val sensitivity: MemorySensitivity,
    val confidence: Float = 1.0f,
    val isConfirmedFact: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val expiryTimeMs: Long? = null,
    val accessCount: Int = 0
) {
    val isExpired: Boolean
        get() = expiryTimeMs != null && System.currentTimeMillis() > expiryTimeMs
}

data class ContradictionReport(
    val hasContradiction: Boolean,
    val existingNode: GraphMemoryNode? = null,
    val proposedValue: String? = null,
    val explanation: String? = null
)

object PersonalMemoryGraph {
    private const val TAG = "PersonalMemoryGraph"
    private val inMemoryNodes = ConcurrentHashMap<String, GraphMemoryNode>()

    /**
     * Stores or updates a memory node in the graph, checking for contradictions and sanitizing credentials.
     */
    suspend fun recordMemory(
        context: Context? = null,
        key: String,
        value: String,
        category: String = "general",
        provenance: MemoryProvenance = MemoryProvenance.EXPLICIT_USER_STATEMENT,
        sensitivity: MemorySensitivity = MemorySensitivity.PREFERENCE,
        confidence: Float = 1.0f,
        isConfirmedFact: Boolean = true,
        ttlDays: Int? = null
    ): Pair<GraphMemoryNode, ContradictionReport> = withContext(Dispatchers.IO) {
        val sanitizedValue = EpisodicVectorMemory.sanitizeForStorage(value.trim())
        val cleanKey = key.trim().lowercase(Locale.ROOT)

        val expiry = if (ttlDays != null && ttlDays > 0) {
            System.currentTimeMillis() + (ttlDays.toLong() * 24 * 3600 * 1000)
        } else null

        // Check for contradiction with existing node
        val existing = inMemoryNodes[cleanKey]
        val contradiction = if (existing != null && !existing.value.equals(sanitizedValue, ignoreCase = true)) {
            Log.w(TAG, "Contradiction detected for key '$cleanKey': old='${existing.value}', new='$sanitizedValue'")
            ContradictionReport(
                hasContradiction = true,
                existingNode = existing,
                proposedValue = sanitizedValue,
                explanation = "Memory for '$cleanKey' updated from '${existing.value}' to '$sanitizedValue'"
            )
        } else {
            ContradictionReport(hasContradiction = false)
        }

        val node = GraphMemoryNode(
            key = cleanKey,
            value = sanitizedValue,
            category = category,
            provenance = provenance,
            sensitivity = sensitivity,
            confidence = confidence.coerceIn(0.1f, 1.0f),
            isConfirmedFact = isConfirmedFact,
            expiryTimeMs = expiry
        )

        inMemoryNodes[cleanKey] = node

        // Persist to underlying Room database
        val app = context?.applicationContext as? JarvisApp
        if (app != null) {
            try {
                app.memoryRepository.saveMemory(
                    rawText = "$cleanKey: $sanitizedValue",
                    category = category,
                    key = cleanKey,
                    value = sanitizedValue
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist to Room: ${e.message}")
            }
        }

        Pair(node, contradiction)
    }

    /**
     * Retrieves relevant memories matching a query, filtering out expired items.
     */
    suspend fun queryRelevantMemories(
        context: Context? = null,
        query: String,
        limit: Int = 5
    ): List<GraphMemoryNode> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val activeNodes = inMemoryNodes.values.filter { !it.isExpired }

        if (activeNodes.isEmpty() && context != null) {
            // Lazy load from Room
            val app = context.applicationContext as? JarvisApp
            if (app != null) {
                val dbEntities = app.memoryRepository.searchMemories(query)
                for (ent in dbEntities) {
                    val node = GraphMemoryNode(
                        key = ent.key.lowercase(Locale.ROOT),
                        value = ent.value,
                        category = ent.category,
                        provenance = MemoryProvenance.EXPLICIT_USER_STATEMENT,
                        sensitivity = MemorySensitivity.PREFERENCE
                    )
                    inMemoryNodes[node.key] = node
                }
            }
        }

        val qTokens = EpisodicVectorMemory.tokenize(query)
        if (qTokens.isEmpty()) return@withContext emptyList()
        val qVec = EpisodicVectorMemory.buildTermVector(qTokens)

        val scored = inMemoryNodes.values.filter { !it.isExpired }.map { node ->
            val nodeTokens = EpisodicVectorMemory.tokenize("${node.key} ${node.value}")
            val nodeVec = EpisodicVectorMemory.buildTermVector(nodeTokens)
            val sim = EpisodicVectorMemory.computeCosineSimilarity(qVec, nodeVec)
            val lexical = EpisodicVectorMemory.computeLexicalJaccard(qTokens, nodeTokens)
            val score = (0.6f * sim) + (0.4f * lexical)
            Pair(node, score)
        }.filter { it.second >= 0.15f }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

        scored
    }

    /**
     * Deletes a memory node by key.
     */
    suspend fun deleteMemory(context: Context? = null, key: String): Boolean = withContext(Dispatchers.IO) {
        val cleanKey = key.trim().lowercase(Locale.ROOT)
        val removed = inMemoryNodes.remove(cleanKey) != null
        val app = context?.applicationContext as? JarvisApp
        if (app != null) {
            try {
                app.memoryRepository.deleteMemoriesByKeyword(cleanKey)
            } catch (_: Exception) {}
        }
        removed
    }

    /**
     * Clears all in-memory nodes.
     */
    fun clearAll() {
        inMemoryNodes.clear()
    }

    /**
     * Returns all active non-expired memory nodes for user inspection.
     */
    fun getAllActiveMemories(): List<GraphMemoryNode> {
        return inMemoryNodes.values.filter { !it.isExpired }.toList()
    }
}
