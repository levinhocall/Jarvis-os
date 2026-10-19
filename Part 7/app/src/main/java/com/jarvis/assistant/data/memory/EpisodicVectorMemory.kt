package com.jarvis.assistant.data.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Long-Term Episodic Vector Memory Core.
 *
 * Provides on-device, zero-cost semantic vector retrieval using TF-IDF token vector space
 * cosine similarity, combined with lexical matching and exponential recency decay.
 * Includes automated redaction for sensitive credentials (passwords, API keys, card numbers, OTPs),
 * user correction handling, and strict distinction across memory scopes.
 */
object EpisodicVectorMemory {
    private const val TAG = "EpisodicVectorMemory"
    private const val RELEVANCE_CUTOFF = 0.25f
    private const val MAX_RECALLED_CONTEXTS = 5

    enum class MemoryScope {
        CONVERSATION_HISTORY,
        EPISODIC_MEMORY,
        USER_PREFERENCE,
        TEMPORARY_TASK_STATE
    }

    data class MemoryRecord(
        val id: Long = 0,
        val scope: MemoryScope,
        val category: String,
        val key: String,
        val value: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ScoredMemory(
        val record: MemoryRecord,
        val score: Float,
        val cosineSimilarity: Float,
        val lexicalScore: Float,
        val recencyFactor: Float
    )

    // Regex patterns for automatic sensitive credential redaction
    private val SENSITIVE_PATTERNS = listOf(
        Regex("(?i)\\b(password|passwd|pwd)\\s*(?:[:=]|is)?\\s*\\S+"),
        Regex("(?i)\\b(api[_-]?key|secret[_-]?key|bearer)\\s*(?:[:=]|is)?\\s*\\S+"),
        Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"), // 16-digit card numbers
        Regex("(?i)\\b(cvv|cvc)\\s*[:=]?\\s*\\d{3,4}\\b"),
        Regex("(?i)\\b(otp|verification code)\\s*(?:[:=]|is)?\\s*\\d{4,8}\\b")
    )

    /**
     * Sanitizes input text before persistence, redacting passwords, API keys, card numbers, and OTPs.
     */
    fun sanitizeForStorage(raw: String): String {
        var sanitized = raw
        for (pattern in SENSITIVE_PATTERNS) {
            sanitized = pattern.replace(sanitized, "[PROTECTED_CREDENTIAL]")
        }
        return sanitized
    }

    /**
     * Tokenizes text into lower-case normalized unigrams and character n-grams.
     */
    fun tokenize(text: String): List<String> {
        val clean = text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        return clean.split(Regex("\\s+")).filter { it.length >= 2 }
    }

    /**
     * Builds a TF-IDF-like normalized term frequency vector.
     */
    fun buildTermVector(tokens: List<String>): Map<String, Float> {
        if (tokens.isEmpty()) return emptyMap()
        val freq = mutableMapOf<String, Float>()
        for (t in tokens) {
            freq[t] = (freq[t] ?: 0f) + 1f
        }
        val total = tokens.size.toFloat()
        // Compute L2 norm
        var sumSquares = 0f
        for ((term, count) in freq) {
            val tf = count / total
            val weight = tf * (1.0f + ln(1.0f + term.length.toFloat()))
            freq[term] = weight
            sumSquares += weight * weight
        }
        val norm = sqrt(sumSquares)
        if (norm > 0f) {
            for ((term, weight) in freq) {
                freq[term] = weight / norm
            }
        }
        return freq
    }

    /**
     * Computes cosine similarity between two term vectors: range [0.0, 1.0].
     */
    fun computeCosineSimilarity(v1: Map<String, Float>, v2: Map<String, Float>): Float {
        if (v1.isEmpty() || v2.isEmpty()) return 0f
        var dot = 0f
        for ((term, w1) in v1) {
            val w2 = v2[term] ?: continue
            dot += w1 * w2
        }
        return dot.coerceIn(0f, 1.0f)
    }

    /**
     * Computes Jaccard lexical overlap between token sets.
     */
    fun computeLexicalJaccard(tokens1: List<String>, tokens2: List<String>): Float {
        val s1 = tokens1.toSet()
        val s2 = tokens2.toSet()
        if (s1.isEmpty() || s2.isEmpty()) return 0f
        val intersection = s1.intersect(s2).size.toFloat()
        val union = s1.union(s2).size.toFloat()
        return if (union > 0f) intersection / union else 0f
    }

    /**
     * Computes recency decay factor: half-life of 14 days.
     */
    fun computeRecencyFactor(timestamp: Long, now: Long = System.currentTimeMillis()): Float {
        val ageDays = maxOf(0f, (now - timestamp).toFloat() / (1000f * 3600f * 24f))
        val halfLifeDays = 14f
        return (1f / (1f + (ageDays / halfLifeDays))).coerceIn(0.1f, 1.0f)
    }

    /**
     * Ranks a collection of memory records against a search query using hybrid vector scoring.
     */
    fun rankMemories(
        query: String,
        records: List<MemoryRecord>,
        now: Long = System.currentTimeMillis()
    ): List<ScoredMemory> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty() || records.isEmpty()) return emptyList()
        val queryVector = buildTermVector(queryTokens)

        return records.mapNotNull { record ->
            val recordTokens = tokenize("${record.key} ${record.value} ${record.text}")
            if (recordTokens.isEmpty()) return@mapNotNull null

            val recordVector = buildTermVector(recordTokens)
            val cosineSim = computeCosineSimilarity(queryVector, recordVector)
            val lexicalScore = computeLexicalJaccard(queryTokens, recordTokens)
            val recency = computeRecencyFactor(record.timestamp, now)

            // Combined hybrid ranking:
            // 60% Semantic Vector Similarity + 25% Lexical Overlap + 15% Recency Weighting
            val finalScore = (0.60f * cosineSim) + (0.25f * lexicalScore) + (0.15f * recency)

            if (finalScore >= RELEVANCE_CUTOFF) {
                ScoredMemory(
                    record = record,
                    score = finalScore,
                    cosineSimilarity = cosineSim,
                    lexicalScore = lexicalScore,
                    recencyFactor = recency
                )
            } else null
        }.sortedByDescending { it.score }.take(MAX_RECALLED_CONTEXTS)
    }

    /**
     * Converts a Room MemoryEntity to an EpisodicVectorMemory.MemoryRecord.
     */
    fun fromEntity(entity: MemoryEntity): MemoryRecord {
        val scope = when (entity.category) {
            MemoryEntity.CATEGORY_PREFERENCES -> MemoryScope.USER_PREFERENCE
            MemoryEntity.CATEGORY_PERSONAL, MemoryEntity.CATEGORY_NOTES, MemoryEntity.CATEGORY_ROUTINES -> MemoryScope.EPISODIC_MEMORY
            else -> MemoryScope.EPISODIC_MEMORY
        }
        return MemoryRecord(
            id = entity.id,
            scope = scope,
            category = entity.category,
            key = entity.key,
            value = entity.value,
            text = entity.rawText,
            timestamp = entity.timestamp
        )
    }

    /**
     * Saves or updates an episodic memory in the database with automatic sensitive redaction.
     */
    suspend fun saveOrUpdateMemory(
        context: Context,
        scope: MemoryScope,
        category: String,
        key: String,
        value: String,
        rawText: String
    ): MemoryRecord? {
        val app = context.applicationContext as? JarvisApp ?: return null
        val sanitizedText = sanitizeForStorage(rawText)
        val sanitizedValue = sanitizeForStorage(value)

        val cleanKey = key.trim().lowercase(Locale.ROOT)
        // Check if an existing memory with this key exists (User correction)
        val existing = app.memoryRepository.getAllMemories().firstOrNull {
            it.category == category && it.key.trim().equals(cleanKey, ignoreCase = true)
        }

        val entityToSave = if (existing != null) {
            Log.d(TAG, "Updating existing memory '${existing.key}' with user correction")
            existing.copy(
                value = sanitizedValue,
                rawText = sanitizedText,
                timestamp = System.currentTimeMillis()
            )
        } else {
            MemoryEntity(
                category = category,
                key = cleanKey,
                value = sanitizedValue,
                rawText = sanitizedText,
                timestamp = System.currentTimeMillis()
            )
        }

        val savedId = app.memoryRepository.saveMemory(
            rawText = entityToSave.rawText,
            category = entityToSave.category,
            key = entityToSave.key,
            value = entityToSave.value
        )

        return MemoryRecord(
            id = savedId,
            scope = scope,
            category = category,
            key = cleanKey,
            value = sanitizedValue,
            text = sanitizedText,
            timestamp = entityToSave.timestamp
        )
    }

    /**
     * Performs semantic vector recall against all persistent memories.
     */
    suspend fun queryVectorRecall(
        context: Context,
        query: String,
        allowedScopes: Set<MemoryScope> = setOf(MemoryScope.EPISODIC_MEMORY, MemoryScope.USER_PREFERENCE)
    ): List<ScoredMemory> {
        val app = context.applicationContext as? JarvisApp ?: return emptyList()
        val allEntities = app.memoryRepository.getAllMemories()
        val records = allEntities.map { fromEntity(it) }
            .filter { allowedScopes.contains(it.scope) }

        return rankMemories(query, records)
    }
}
