package com.jarvis.assistant.network

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ModelCandidate(
    val id: String,
    val majorVersion: Int,
    val minorVersion: Int,
    val isFlash: Boolean
) : Comparable<ModelCandidate> {
    override fun compareTo(other: ModelCandidate): Int {
        val ver = (majorVersion * 1000 + minorVersion).compareTo(other.majorVersion * 1000 + other.minorVersion)
        return if (ver != 0) ver else id.compareTo(other.id)
    }
}

/**
 * Shared, robust Gemini model resolver for all REST callers (Website Forge, Chat, Vision, Planning).
 *
 * Implements:
 * 1. Semantic version parsing (gemini-<major>.<minor>-flash...)
 * 2. Filters out live/image/tts/embedding/audio non-REST variants
 * 3. Excludes major version < 3 unless nothing else exists
 * 4. Priority DESC sorting (3.8 > 3.1 > 3.0 > 2.x)
 * 5. Automatic fallback on HTTP 404 / 403 / "no longer available"
 * 6. Replacement hint parsing ("Please update your code to use models/...")
 * 7. Verified model caching with 404 invalidation
 */
object GeminiModelResolver {

    const val KNOWN_GOOD_FLASH = "gemini-3.8-flash"
    const val BACKUP_KNOWN_GOOD = "gemini-3.1-flash"

    private val cachedWorkingModel = AtomicReference<String?>(null)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun getCachedModel(): String? = cachedWorkingModel.get()

    fun setCachedModel(model: String) {
        cachedWorkingModel.set(cleanModelId(model))
    }

    fun invalidateCache(failedModel: String? = null) {
        if (failedModel == null) {
            cachedWorkingModel.set(null)
        } else {
            val clean = cleanModelId(failedModel)
            cachedWorkingModel.compareAndSet(clean, null)
        }
    }

    fun cleanModelId(raw: String): String {
        return raw.trim().removePrefix("models/")
    }

    /**
     * Pure parser that filters and ranks models from a raw JSON ListModels response.
     */
    fun parseCandidateModels(jsonBody: String): List<String> {
        val candidates = mutableListOf<ModelCandidate>()

        try {
            val root = JsonParser.parseString(jsonBody).asJsonObject
            val modelsArray = root.getAsJsonArray("models") ?: return listOf(KNOWN_GOOD_FLASH, BACKUP_KNOWN_GOOD)

            val regex = Regex("""gemini-(\d+)(?:\.(\d+))?""", RegexOption.IGNORE_CASE)

            for (i in 0 until modelsArray.size()) {
                val obj = modelsArray.get(i).asJsonObject
                val rawName = obj.get("name")?.asString ?: continue
                val clean = cleanModelId(rawName)
                val lower = clean.lowercase(Locale.ROOT)

                // Check supportedGenerationMethods if present
                val methods = obj.getAsJsonArray("supportedGenerationMethods")
                val supportsGenContent = if (methods != null && methods.size() > 0) {
                    (0 until methods.size()).any { methods.get(it).asString == "generateContent" }
                } else {
                    true
                }

                if (!supportsGenContent) continue

                // Exclude specialized modalities not intended for text/code streaming
                if (lower.contains("live") ||
                    lower.contains("image") ||
                    lower.contains("tts") ||
                    lower.contains("embedding") ||
                    lower.contains("audio")
                ) {
                    continue
                }

                val match = regex.find(clean)
                val major = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val minor = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                val isFlash = lower.contains("flash")

                candidates.add(ModelCandidate(clean, major, minor, isFlash))
            }
        } catch (_: Throwable) {
            return listOf(KNOWN_GOOD_FLASH, BACKUP_KNOWN_GOOD)
        }

        // Filter: Prefer flash models
        val flashCandidates = candidates.filter { it.isFlash }
        val pool = if (flashCandidates.isNotEmpty()) flashCandidates else candidates

        // Filter: Exclude major version < 3 unless nothing else exists
        val modernPool = pool.filter { it.majorVersion >= 3 }
        val finalPool = if (modernPool.isNotEmpty()) modernPool else pool

        // Sort descending by version
        val sorted = finalPool.sortedDescending().map { it.id }.toMutableList()

        // Always guarantee known-good fallback constants exist at the end
        if (!sorted.contains(KNOWN_GOOD_FLASH)) {
            sorted.add(KNOWN_GOOD_FLASH)
        }
        if (!sorted.contains(BACKUP_KNOWN_GOOD)) {
            sorted.add(BACKUP_KNOWN_GOOD)
        }

        return sorted
    }

    /**
     * Extracts a replacement model suggestion from Google's error messages.
     * E.g. "This model models/gemini-2.5-flash is no longer available to new users. Please update your code to use models/gemini-3.8-flash"
     */
    fun extractReplacementHint(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        val hintRegex = Regex("""(?:use|to)\s+(?:models/)?(gemini-[a-zA-Z0-9.-]+)""", RegexOption.IGNORE_CASE)
        val match = hintRegex.find(errorMessage) ?: return null
        val candidate = cleanModelId(match.groupValues[1])
        val lower = candidate.lowercase(Locale.ROOT)
        if (lower.contains("live") || lower.contains("image") || lower.contains("tts") || lower.contains("embedding")) {
            return null
        }
        return candidate
    }

    /**
     * Queries the ListModels endpoint and returns ordered candidate models.
     */
    fun fetchCandidates(apiKey: String): List<String> {
        val cached = cachedWorkingModel.get()
        if (!cached.isNullOrBlank()) {
            return listOf(cached, KNOWN_GOOD_FLASH, BACKUP_KNOWN_GOOD).distinct()
        }

        return try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    parseCandidateModels(body)
                } else {
                    listOf(KNOWN_GOOD_FLASH, BACKUP_KNOWN_GOOD)
                }
            }
        } catch (_: Throwable) {
            listOf(KNOWN_GOOD_FLASH, BACKUP_KNOWN_GOOD)
        }
    }

    /**
     * Executes a REST call with automatic candidate fallback on HTTP 404 / 403 / deprecation.
     */
    inline fun <T> executeWithModelFallback(
        apiKey: String,
        initialCandidates: List<String>? = null,
        crossinline execute: (model: String) -> T
    ): T {
        val candidates = (initialCandidates ?: fetchCandidates(apiKey)).toMutableList()
        var lastException: Exception? = null

        var idx = 0
        while (idx < candidates.size) {
            val model = candidates[idx]
            try {
                val result = execute(model)
                setCachedModel(model)
                return result
            } catch (e: Exception) {
                lastException = e
                val msg = e.message.orEmpty()
                val is404OrDeprecated = msg.contains("404") ||
                        msg.contains("NOT_FOUND", ignoreCase = true) ||
                        msg.contains("no longer available", ignoreCase = true) ||
                        msg.contains("403") ||
                        msg.contains("PERMISSION_DENIED", ignoreCase = true)

                if (is404OrDeprecated) {
                    invalidateCache(model)
                    // Check if error body named a replacement model
                    val hint = extractReplacementHint(msg)
                    if (hint != null && !candidates.contains(hint)) {
                        candidates.add(idx + 1, hint)
                    }
                    idx++
                } else {
                    // Not a model-not-found error (e.g. 429 quota, 503 overloaded, network error) -> do not burn candidates
                    throw e
                }
            }
        }

        throw lastException ?: IllegalStateException("All candidate models failed")
    }
}
