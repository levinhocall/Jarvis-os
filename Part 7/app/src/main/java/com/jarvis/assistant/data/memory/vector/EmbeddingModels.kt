package com.jarvis.assistant.data.memory.vector

import com.jarvis.assistant.data.memory.MemoryEntity

enum class MatchType {
    SEMANTIC_VECTOR,
    KEYWORD_EXACT,
    HYBRID,
    KEYWORD_FALLBACK
}

enum class SearchMode {
    VECTOR_HYBRID,
    KEYWORD_ONLY
}

data class ScoredMemory(
    val memory: MemoryEntity,
    val score: Double,
    val matchType: MatchType,
    val cosineSimilarity: Double = 0.0,
    val keywordScore: Double = 0.0
)

data class EmbeddingVector(
    val dimension: Int,
    val values: FloatArray,
    val modelName: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingVector) return false
        return dimension == other.dimension && values.contentEquals(other.values) && modelName == other.modelName
    }

    override fun hashCode(): Int {
        var result = dimension
        result = 31 * result + values.contentHashCode()
        result = 31 * result + modelName.hashCode()
        return result
    }
}
