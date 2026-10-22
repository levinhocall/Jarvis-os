package com.jarvis.assistant.data.memory

import com.jarvis.assistant.data.memory.vector.DeterministicLocalEmbeddingProvider
import com.jarvis.assistant.data.memory.vector.EmbeddingProvider
import com.jarvis.assistant.data.memory.vector.ScoredMemory
import com.jarvis.assistant.data.memory.vector.VectorSearchEngine
import kotlinx.coroutines.flow.Flow
import java.util.Locale

class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val embeddingProvider: EmbeddingProvider = DeterministicLocalEmbeddingProvider(),
    private val vectorSearchEngine: VectorSearchEngine = VectorSearchEngine()
) {
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()
    fun getActiveMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getActiveMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> = memoryDao.getAllMemories()

    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> {
        return if (category == "All") {
            memoryDao.getAllMemoriesFlow()
        } else {
            memoryDao.getMemoriesByCategoryFlow(category)
        }
    }

    fun getMemoriesByTypeFlow(type: String): Flow<List<MemoryEntity>> =
        memoryDao.getMemoriesByTypeFlow(type)

    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>> =
        memoryDao.searchMemoriesFlow(query)

    suspend fun searchMemories(query: String): List<MemoryEntity> =
        memoryDao.searchMemories(query)

    /**
     * Semantic vector search combined with keyword retrieval.
     */
    suspend fun searchMemoriesSemantic(query: String, limit: Int = 10): List<ScoredMemory> {
        val active = memoryDao.getAllMemories()
        return vectorSearchEngine.search(query, active, embeddingProvider, limit)
    }

    /**
     * Explicit command to record a memory with secret scrubbing, embedding generation,
     * and contradiction/deduplication handling.
     */
    suspend fun remember(
        content: String,
        type: String = MemoryEntity.TYPE_PERSONAL_FACT,
        key: String = "",
        importance: Float = 0.5f,
        confidence: Float = 0.8f,
        sourceConversationId: String? = null,
        sourceMessageId: String? = null,
        category: String = MemoryEntity.CATEGORY_NOTES,
        expiryTimeMs: Long? = null
    ): Long {
        val cleanContent = EpisodicVectorMemory.sanitizeForStorage(content)
        val calculatedKey = key.ifBlank {
            cleanContent.take(30).trim()
        }.lowercase(Locale.ROOT)

        // 1. Contradiction & Deduplication check
        val existing = memoryDao.getActiveMemoryByKey(calculatedKey)
        if (existing != null) {
            // Supersede older memory if new information arrives
            memoryDao.updateMemoryStatus(existing.id, MemoryEntity.STATUS_SUPERSEDED)
        }

        // 2. Generate real embedding
        val vector = embeddingProvider.generateEmbedding("$calculatedKey $cleanContent")
        val embeddingJson = vector?.let { vectorSearchEngine.serializeEmbedding(it) }

        val entity = MemoryEntity(
            category = category,
            key = calculatedKey,
            value = cleanContent,
            rawText = cleanContent,
            provenance = "EXPLICIT_USER_COMMAND",
            sensitivity = "PREFERENCE",
            expiryTimeMs = expiryTimeMs,
            memoryType = type,
            status = MemoryEntity.STATUS_ACTIVE,
            importance = importance,
            confidence = confidence,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            embeddingJson = embeddingJson,
            embeddingModel = embeddingProvider.modelName,
            embeddingDimension = embeddingProvider.dimension,
            pinned = false
        )

        return memoryDao.insertMemory(entity)
    }

    /**
     * Backward-compatible saveMemory method.
     */
    suspend fun saveMemory(
        rawText: String,
        category: String = MemoryEntity.CATEGORY_NOTES,
        key: String = "",
        value: String = "",
        provenance: String = "EXPLICIT_USER_STATEMENT",
        sensitivity: String = "PREFERENCE",
        expiryTimeMs: Long? = null
    ): Long {
        return remember(
            content = value.ifBlank { rawText },
            type = if (category == MemoryEntity.CATEGORY_PREFERENCES) MemoryEntity.TYPE_PREFERENCE else MemoryEntity.TYPE_PERSONAL_FACT,
            key = key,
            category = category,
            expiryTimeMs = expiryTimeMs
        )
    }

    /**
     * Corrects a memory and updates embedding.
     */
    suspend fun correct(memoryId: Long, newContent: String): Boolean {
        val all = memoryDao.getAllMemories()
        val target = all.firstOrNull { it.id == memoryId } ?: return false
        val sanitized = EpisodicVectorMemory.sanitizeForStorage(newContent)

        val vector = embeddingProvider.generateEmbedding("${target.key} $sanitized")
        val embeddingJson = vector?.let { vectorSearchEngine.serializeEmbedding(it) }

        val updated = target.copy(
            value = sanitized,
            rawText = sanitized,
            timestamp = System.currentTimeMillis(),
            embeddingJson = embeddingJson
        )
        memoryDao.updateMemory(updated)
        return true
    }

    /**
     * Forgets a memory (soft-delete or permanent deletion).
     */
    suspend fun forget(memoryId: Long, permanent: Boolean = false) {
        if (permanent) {
            memoryDao.deleteMemoryById(memoryId)
        } else {
            memoryDao.softDeleteMemory(memoryId)
        }
    }

    suspend fun setPinned(memoryId: Long, pinned: Boolean) {
        memoryDao.setPinned(memoryId, pinned)
    }

    /**
     * Conservative automatic extraction of stable personal facts and ongoing project context.
     * Never stores passwords, tokens, or guesses.
     */
    suspend fun autoExtractFacts(
        userMessage: String,
        sourceConversationId: String? = null,
        sourceMessageId: String? = null
    ): List<Long> {
        val text = userMessage.trim()
        val lower = text.lowercase(Locale.ROOT)

        // Security check: Ignore anything with secrets
        if (lower.contains("password") || lower.contains("secret") || lower.contains("api key") || lower.contains("token")) {
            return emptyList()
        }

        val extractedIds = mutableListOf<Long>()

        // 1. Preferences
        val prefRegex = Regex("""(?:i prefer|i like|i love|my favorite is|my favorite)\s+([a-zA-Z0-9_\s]{3,50})""", RegexOption.IGNORE_CASE)
        prefRegex.find(text)?.let { match ->
            val pref = match.groupValues[1].trim()
            if (pref.isNotBlank()) {
                val id = remember(
                    content = "User prefers $pref",
                    type = MemoryEntity.TYPE_PREFERENCE,
                    key = "preference_${pref.take(15)}",
                    importance = 0.7f,
                    sourceConversationId = sourceConversationId,
                    sourceMessageId = sourceMessageId
                )
                extractedIds.add(id)
            }
        }

        // 2. Ongoing Projects
        val projectRegex = Regex("""(?:i am working on|working on project|current project is)\s+([a-zA-Z0-9_\s]{3,50})""", RegexOption.IGNORE_CASE)
        projectRegex.find(text)?.let { match ->
            val project = match.groupValues[1].trim()
            if (project.isNotBlank()) {
                val id = remember(
                    content = "User is working on project: $project",
                    type = MemoryEntity.TYPE_PROJECT,
                    key = "project_${project.take(15)}",
                    importance = 0.8f,
                    sourceConversationId = sourceConversationId,
                    sourceMessageId = sourceMessageId
                )
                extractedIds.add(id)
            }
        }

        // 3. Decisions
        val decisionRegex = Regex("""(?:we decided to|i decided to|decision was to)\s+([a-zA-Z0-9_\s]{3,60})""", RegexOption.IGNORE_CASE)
        decisionRegex.find(text)?.let { match ->
            val decision = match.groupValues[1].trim()
            if (decision.isNotBlank()) {
                val id = remember(
                    content = "Decision: $decision",
                    type = MemoryEntity.TYPE_DECISION,
                    key = "decision_${decision.take(15)}",
                    importance = 0.6f,
                    sourceConversationId = sourceConversationId,
                    sourceMessageId = sourceMessageId
                )
                extractedIds.add(id)
            }
        }

        return extractedIds
    }

    suspend fun cleanupExpiredMemories(): Int {
        return memoryDao.deleteExpiredMemories(System.currentTimeMillis())
    }

    private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

    suspend fun exportMemoriesAsJson(): String {
        val list = memoryDao.getAllMemories()
        return gson.toJson(list)
    }

    suspend fun importMemoriesFromJson(jsonStr: String): Int {
        var count = 0
        try {
            val type = object : com.google.gson.reflect.TypeToken<List<MemoryEntity>>() {}.type
            val list: List<MemoryEntity> = gson.fromJson(jsonStr, type) ?: emptyList()
            for (item in list) {
                if (item.key.isNotBlank()) {
                    remember(
                        content = item.value.ifBlank { item.rawText },
                        type = item.memoryType,
                        key = item.key,
                        importance = item.importance,
                        confidence = item.confidence,
                        sourceConversationId = item.sourceConversationId,
                        sourceMessageId = item.sourceMessageId,
                        category = item.category,
                        expiryTimeMs = item.expiryTimeMs
                    )
                    count++
                }
            }
        } catch (_: Exception) {}
        return count
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        memoryDao.updateMemory(memory)
    }

    suspend fun deleteMemoryById(id: Long) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun deleteMemoriesByKeyword(keyword: String): Int {
        return memoryDao.deleteMemoriesByKeyword(keyword)
    }

    suspend fun clearAll() {
        memoryDao.clearAllMemories()
    }
}
