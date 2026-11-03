package com.jarvis.assistant.data.memory.vector

import kotlin.math.abs
import kotlin.math.sqrt

interface EmbeddingProvider {
    val modelName: String
    val dimension: Int
    fun isAvailable(): Boolean
    suspend fun generateEmbedding(text: String): FloatArray?
    suspend fun generateEmbeddings(texts: List<String>): List<FloatArray?> {
        return texts.map { generateEmbedding(it) }
    }
}

/**
 * Deterministic on-device heuristic vectors; this is not a pretrained neural model.
 * Generates unit-normalized 128-dimensional dense vectors using subword hashing,
 * semantic word clusters, and L2 normalization without requiring cloud network.
 */
class DeterministicLocalEmbeddingProvider(
    override val dimension: Int = 128
) : EmbeddingProvider {

    override val modelName: String = "local-deterministic-l2-$dimension"

    override fun isAvailable(): Boolean = true

    override suspend fun generateEmbedding(text: String): FloatArray? {
        val clean = text.lowercase().trim()
        if (clean.isBlank()) return null

        val vector = FloatArray(dimension)
        val tokens = clean.split(Regex("[^a-z0-9_]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        for (token in tokens) {
            // 1. Semantic stem / cluster mapping
            val clusterHash = computeSemanticClusterHash(token)
            val primaryBucket = (clusterHash % dimension + dimension) % dimension
            vector[primaryBucket] += 2.0f

            // 2. Character n-gram hashing (captures morphological similarity like prefer/preference)
            for (n in 3..4) {
                if (token.length >= n) {
                    for (i in 0..token.length - n) {
                        val ngram = token.substring(i, i + n)
                        val ngramHash = abs(ngram.hashCode())
                        val bucket = (ngramHash % dimension + dimension) % dimension
                        vector[bucket] += 1.0f
                    }
                }
            }
        }

        // L2 Normalization so dotProduct equals cosine similarity
        var sumSquares = 0.0
        for (v in vector) {
            sumSquares += (v * v)
        }
        val norm = sqrt(sumSquares).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }

        return vector
    }

    private fun computeSemanticClusterHash(token: String): Int {
        // Map common synonyms and word roots to consistent cluster seeds
        return when {
            token.startsWith("prefer") || token.contains("like") || token.contains("love") || token.contains("fav") -> 10101
            token.contains("dark") || token.contains("night") || token.contains("black") -> 20202
            token.contains("light") || token.contains("day") || token.contains("white") -> 30303
            token.startsWith("call") || token.startsWith("dial") || token.contains("contact") || token.contains("ring") -> 40404
            token.startsWith("notif") || token.contains("alert") || token.contains("alarm") || token.contains("timer") -> 50505
            token.startsWith("work") || token.contains("project") || token.contains("task") || token.contains("job") -> 60606
            token.startsWith("home") || token.contains("house") || token.contains("family") || token.contains("sister") || token.contains("brother") -> 70707
            token.startsWith("device") || token.contains("phone") || token.contains("smart") || token.contains("mobile") || token.contains("vivo") || token.contains("samsung") || token.contains("android") -> 80808
            else -> abs(token.hashCode())
        }
    }
}

/** Compatibility adapter: cloud embeddings are disabled by the phone-only memory policy. */
class GeminiEmbeddingProvider(
    @Suppress("UNUSED_PARAMETER") apiKeyProvider: () -> String?,
    override val dimension: Int = 768
) : EmbeddingProvider {
    private val local = DeterministicLocalEmbeddingProvider(dimension)
    override val modelName: String get() = local.modelName
    override fun isAvailable(): Boolean = true
    override suspend fun generateEmbedding(text: String): FloatArray? = local.generateEmbedding(text)
}

/** Compatibility adapter for legacy callers; never sends text to the former backend. */
class RemoteNeuralEmbeddingProvider(
    @Suppress("UNUSED_PARAMETER") baseEndpoint: String,
    @Suppress("UNUSED_PARAMETER") fallback: EmbeddingProvider = DeterministicLocalEmbeddingProvider(384)
) : EmbeddingProvider {
    private val local = DeterministicLocalEmbeddingProvider(384)
    override val modelName: String get() = local.modelName
    override val dimension: Int = 384
    override fun isAvailable(): Boolean = true
    override suspend fun generateEmbedding(text: String): FloatArray? = local.generateEmbedding(text)
}
