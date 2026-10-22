package com.jarvis.assistant.data.memory.vector

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.URL
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
 * Genuine, deterministic on-device semantic embedding provider for Android.
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

/**
 * Cloud embedding provider using Google's text-embedding-004 model.
 */
class GeminiEmbeddingProvider(
    private val apiKeyProvider: () -> String?,
    override val dimension: Int = 768
) : EmbeddingProvider {

    override val modelName: String = "gemini-text-embedding-004"
    private val gson = Gson()

    override fun isAvailable(): Boolean = !apiKeyProvider().isNullOrBlank()

    override suspend fun generateEmbedding(text: String): FloatArray? {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) return null

        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=$apiKey"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val requestBody = JsonObject().apply {
                addProperty("model", "models/text-embedding-004")
                add("content", JsonObject().apply {
                    val parts = com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", text) })
                    }
                    add("parts", parts)
                })
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val responseJson = conn.inputStream.bufferedReader().use { it.readText() }
                val root = gson.fromJson(responseJson, JsonObject::class.java)
                val values = root.getAsJsonObject("embedding")?.getAsJsonArray("values")
                if (values != null && values.size() > 0) {
                    val array = FloatArray(values.size())
                    for (i in 0 until values.size()) {
                        array[i] = values.get(i).asFloat
                    }
                    array
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
