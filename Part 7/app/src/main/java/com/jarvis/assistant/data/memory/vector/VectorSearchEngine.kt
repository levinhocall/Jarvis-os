package com.jarvis.assistant.data.memory.vector

import com.google.gson.Gson
import com.jarvis.assistant.data.memory.MemoryEntity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class VectorSearchEngine {

    private val gson = Gson()

    /**
     * Computes genuine cosine similarity between two float vectors.
     * Enforces dimension validation.
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Double {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) {
            return 0.0
        }

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in v1.indices) {
            dotProduct += (v1[i] * v2[i])
            normA += (v1[i] * v1[i])
            normB += (v2[i] * v2[i])
        }

        if (normA <= 0.0 || normB <= 0.0) return 0.0

        val sim = dotProduct / (sqrt(normA) * sqrt(normB))
        return max(-1.0, min(1.0, sim))
    }

    /**
     * Hybrid search combining semantic vector similarity with keyword/full-text token matching.
     */
    suspend fun search(
        query: String,
        memories: List<MemoryEntity>,
        embeddingProvider: EmbeddingProvider,
        limit: Int = 10,
        minScoreThreshold: Double = 0.20
    ): List<ScoredMemory> {
        val activeMemories = memories.filter { it.status == MemoryEntity.STATUS_ACTIVE && !it.isExpired }
        if (activeMemories.isEmpty()) return emptyList()

        val queryEmbedding = embeddingProvider.generateEmbedding(query)
        val queryTokens = extractTokens(query)
        val now = System.currentTimeMillis()

        val scoredList = mutableListOf<ScoredMemory>()

        for (memory in activeMemories) {
            val memoryText = "${memory.key} ${memory.value} ${memory.rawText}"
            val memoryTokens = extractTokens(memoryText)
            val keywordScore = computeKeywordScore(queryTokens, memoryTokens)

            val memoryEmbedding = parseEmbedding(memory.embeddingJson)

            if (queryEmbedding != null && memoryEmbedding != null && queryEmbedding.size == memoryEmbedding.size) {
                // Vector semantic search available
                val cosineSim = cosineSimilarity(queryEmbedding, memoryEmbedding)
                val recencyScore = computeRecencyScore(memory.timestamp, now)

                val combinedScore = (0.55 * max(0.0, cosineSim)) +
                        (0.20 * keywordScore) +
                        (0.15 * memory.importance) +
                        (0.10 * recencyScore)

                val matchType = when {
                    cosineSim > 0.60 && keywordScore > 0.3 -> MatchType.HYBRID
                    cosineSim >= 0.30 -> MatchType.SEMANTIC_VECTOR
                    keywordScore > 0.0 -> MatchType.KEYWORD_EXACT
                    else -> MatchType.SEMANTIC_VECTOR
                }

                if (combinedScore >= minScoreThreshold) {
                    scoredList.add(
                        ScoredMemory(
                            memory = memory,
                            score = combinedScore,
                            matchType = matchType,
                            cosineSimilarity = cosineSim,
                            keywordScore = keywordScore
                        )
                    )
                }
            } else {
                // Honest keyword fallback: never claims to be vector search
                val recencyScore = computeRecencyScore(memory.timestamp, now)
                val combinedScore = (0.70 * keywordScore) +
                        (0.20 * memory.importance) +
                        (0.10 * recencyScore)

                if (combinedScore >= minScoreThreshold && keywordScore > 0.0) {
                    scoredList.add(
                        ScoredMemory(
                            memory = memory,
                            score = combinedScore,
                            matchType = MatchType.KEYWORD_FALLBACK,
                            cosineSimilarity = 0.0,
                            keywordScore = keywordScore
                        )
                    )
                }
            }
        }

        return scoredList.sortedByDescending { it.score }.take(limit)
    }

    fun parseEmbedding(json: String?): FloatArray? {
        if (json.isNullOrBlank()) return null
        return try {
            val list = gson.fromJson(json, Array<Float>::class.java)
            if (list != null) {
                FloatArray(list.size) { list[it] }
            } else null
        } catch (_: Exception) {
            try {
                val dList = gson.fromJson(json, Array<Double>::class.java)
                if (dList != null) {
                    FloatArray(dList.size) { dList[it].toFloat() }
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    fun serializeEmbedding(vector: FloatArray): String {
        return gson.toJson(vector)
    }

    private fun extractTokens(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length > 1 }
            .toSet()
    }

    private fun computeKeywordScore(queryTokens: Set<String>, memoryTokens: Set<String>): Double {
        if (queryTokens.isEmpty() || memoryTokens.isEmpty()) return 0.0
        val intersection = queryTokens.intersect(memoryTokens).size
        val jaccard = intersection.toDouble() / queryTokens.size.toDouble()
        return min(1.0, jaccard)
    }

    private fun computeRecencyScore(timestamp: Long, now: Long): Double {
        val ageMs = max(0L, now - timestamp)
        val days = ageMs / (1000.0 * 60 * 60 * 24)
        return 1.0 / (1.0 + (days / 30.0)) // Smooth decay over months
    }
}
