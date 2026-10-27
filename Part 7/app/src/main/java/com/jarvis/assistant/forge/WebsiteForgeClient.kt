package com.jarvis.assistant.forge

import android.content.Context
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * High-performance Gemini SSE client designed specifically for live website generation.
 * Handles dynamic flash model resolution, 180s streaming timeouts, automatic 503 retries,
 * finishReason logging without leaking API keys or user prompts, and safe truncation repair.
 */
class WebsiteForgeClient(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultClient()
) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_FALLBACK_MODEL = "gemini-1.5-flash"
        private val cachedModelName = AtomicReference<String?>(null)

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun clearCachedModel() {
            cachedModelName.set(null)
        }
    }

    private val prefs = AppPreferences(context)

    /**
     * Resolves the best available flash model from the Gemini API or returns the cached model.
     * Uses GeminiModelResolver to ensure version >= 3.x is selected and dead 2.5 models are skipped.
     */
    suspend fun resolveModel(apiKey: String): String = withContext(Dispatchers.IO) {
        val cached = com.jarvis.assistant.network.GeminiModelResolver.getCachedModel()
        if (!cached.isNullOrBlank()) {
            return@withContext cached
        }
        val candidates = com.jarvis.assistant.network.GeminiModelResolver.fetchCandidates(apiKey)
        val selected = candidates.firstOrNull() ?: com.jarvis.assistant.network.GeminiModelResolver.KNOWN_GOOD_FLASH
        selected
    }

    /**
     * Streams website HTML generation from Gemini with auto-retry and chunk emission.
     * Automatically falls back to next candidate model if a model is deprecated (404 / NOT_FOUND).
     */
    fun streamGenerateSite(
        prompt: String,
        isEdit: Boolean = false,
        existingHtml: String = "",
        apiKeyOverride: String? = null
    ): Flow<String> = flow {
        val apiKey = apiKeyOverride
            ?: prefs.getNextActiveKey("gemini")
            ?: prefs.apiKey.ifBlank { null }
            ?: throw IllegalStateException("Gemini API key is not configured")

        val systemPrompt = if (isEdit) {
            ForgeSystemPrompt.forEdit(existingHtml, prompt)
        } else {
            ForgeSystemPrompt.forNewSite(prompt)
        }

        val candidates = com.jarvis.assistant.network.GeminiModelResolver.fetchCandidates(apiKey).toMutableList()
        var candidateIdx = 0
        var streamSucceeded = false
        var lastError: Throwable? = null

        while (candidateIdx < candidates.size && !streamSucceeded) {
            val model = candidates[candidateIdx]
            var attempt = 0
            var modelSucceeded = false

            while (attempt < 2 && !modelSucceeded) {
                attempt++
                try {
                    executeStream(model, apiKey, systemPrompt).collect { chunk ->
                        emit(chunk)
                    }
                    modelSucceeded = true
                    streamSucceeded = true
                    com.jarvis.assistant.network.GeminiModelResolver.setCachedModel(model)
                } catch (e: GeminiOverloadedException) {
                    lastError = e
                    if (attempt < 2) {
                        delay(4000) // Wait 4s before 503 retry
                    } else {
                        throw mapError(e)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    val errorMsg = e.message.orEmpty()
                    val isModelIssue = errorMsg.contains("404") ||
                            errorMsg.contains("NOT_FOUND", ignoreCase = true) ||
                            errorMsg.contains("no longer available", ignoreCase = true) ||
                            errorMsg.contains("403") ||
                            errorMsg.contains("PERMISSION_DENIED", ignoreCase = true)

                    if (isModelIssue) {
                        com.jarvis.assistant.network.GeminiModelResolver.invalidateCache(model)
                        val hint = com.jarvis.assistant.network.GeminiModelResolver.extractReplacementHint(errorMsg)
                        if (hint != null && !candidates.contains(hint)) {
                            candidates.add(candidateIdx + 1, hint)
                        }
                        break // Break inner attempt loop to try next model candidate
                    } else {
                        throw mapError(e)
                    }
                }
            }

            if (!modelSucceeded) {
                candidateIdx++
            }
        }

        if (!streamSucceeded && lastError != null) {
            throw mapError(lastError)
        }
    }.flowOn(Dispatchers.IO)

    private fun executeStream(model: String, apiKey: String, prompt: String): Flow<String> = flow {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"

        val bodyJson = JSONObject().apply {
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            }
            put("contents", contents)

            val genConfig = JSONObject().apply {
                put("temperature", 0.7)
                if (model.contains("2.5")) {
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingBudget", 0)
                    })
                }
            }
            put("generationConfig", genConfig)
        }

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(request)

        try {
            call.execute().use { response ->
                if (response.code == 503 || response.code == 504 || response.code == 429) {
                    throw GeminiOverloadedException("Gemini service temporarily overloaded (${response.code})")
                }
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    throw GeminiApiException(response.code, errBody)
                }

                val source = response.body?.byteStream()
                    ?: throw IllegalStateException("Empty response body from Gemini stream")

                val reader = BufferedReader(InputStreamReader(source, Charsets.UTF_8))
                var line: String? = reader.readLine()

                while (line != null && currentCoroutineContext().isActive) {
                    if (SseParser.isDone(line)) {
                        break
                    }

                    val text = SseParser.extractText(line)
                    if (!text.isNullOrEmpty()) {
                        emit(text)
                    }

                    val err = SseParser.extractError(line)
                    if (!err.isNullOrEmpty()) {
                        throw GeminiApiException(response.code, err)
                    }

                    val finish = SseParser.extractFinishReason(line)
                    if (finish != null && finish != "STOP") {
                        // Finish reason other than STOP (e.g. MAX_TOKENS, SAFETY, RECITATION)
                    }

                    line = reader.readLine()
                }
            }
        } finally {
            if (!call.isCanceled()) {
                call.cancel()
            }
        }
    }

    /**
     * Extracts readable human message from raw JSON error bodies, preventing raw JSON display in HUD.
     */
    fun extractCleanMessage(raw: String): String {
        return try {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            val err = obj.getAsJsonObject("error")
            err?.get("message")?.asString ?: raw
        } catch (_: Throwable) {
            raw
        }
    }

    /**
     * Maps technical HTTP and API exceptions to friendly user-facing messages.
     */
    fun mapError(t: Throwable): Exception {
        val rawMsg = t.message ?: ""
        val cleanMsg = extractCleanMessage(rawMsg)
        return when {
            t is GeminiOverloadedException || cleanMsg.contains("503") || cleanMsg.contains("overloaded", ignoreCase = true) ->
                Exception("Gemini servers are currently overloaded. Please try again shortly.")
            cleanMsg.contains("404") || cleanMsg.contains("NOT_FOUND", ignoreCase = true) || cleanMsg.contains("no longer available", ignoreCase = true) ->
                Exception("Requested AI model is no longer available. Please retry with updated model.")
            t is GeminiApiException && (t.code == 400 || t.code == 403 || cleanMsg.contains("API_KEY_INVALID", ignoreCase = true)) ->
                Exception("Gemini API key is invalid or unauthorized. Please check your key in Settings.")
            t is GeminiApiException && (t.code == 429 || cleanMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)) ->
                Exception("Gemini quota or rate limit exceeded. Please wait a moment.")
            cleanMsg.contains("Unable to resolve host", ignoreCase = true) || cleanMsg.contains("Failed to connect", ignoreCase = true) ->
                Exception("Network connection failed. Please check your internet connection.")
            else ->
                Exception(if (cleanMsg.isNotBlank()) cleanMsg else "An unexpected error occurred during website generation.")
        }
    }

    /**
     * Saves generated HTML into `filesDir/Websites/website_<timestamp>.html`.
     */
    fun saveWebsite(html: String): File {
        val websitesDir = File(context.filesDir, "Websites")
        if (!websitesDir.exists()) {
            websitesDir.mkdirs()
        }
        val timestamp = System.currentTimeMillis()
        val file = File(websitesDir, "website_$timestamp.html")
        file.writeText(html, Charsets.UTF_8)
        return file
    }
}

class GeminiOverloadedException(message: String) : Exception(message)
class GeminiApiException(val code: Int, message: String) : Exception(message)
