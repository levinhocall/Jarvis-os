package com.jarvis.assistant.automation.routing

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jarvis.assistant.automation.agent.GeminiSchemaConverter
import com.jarvis.assistant.automation.agent.LlmStepDecision
import com.jarvis.assistant.automation.mission.ToolDefinition
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

enum class ProviderHealthStatus {
    HEALTHY,
    DEGRADED,
    OFFLINE,
    RATE_LIMITED,
    UNAUTHORIZED
}

data class LlmRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val tools: List<ToolDefinition> = emptyList()
)

data class LlmResponse(
    val content: String,
    val toolCalls: List<LlmStepDecision.InvokeTool> = emptyList(),
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val latencyMs: Long = 0L,
    val providerId: String = "",
    val modelName: String = ""
)

/**
 * Universal interface for LLM inference and function-calling providers.
 */
interface LlmProvider {
    val id: String
    val supportsFunctionCalling: Boolean
    suspend fun health(): ProviderHealthStatus
    suspend fun generate(request: LlmRequest): LlmResponse
}

/**
 * Google Gemini Provider implementation.
 */
class GeminiProvider(
    private val preferences: AppPreferences,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) : LlmProvider {

    override val id: String = "gemini_flash"
    override val supportsFunctionCalling: Boolean = true

    override suspend fun health(): ProviderHealthStatus {
        val key = preferences.getNextActiveKey("gemini") ?: preferences.apiKey
        return if (key.isBlank()) ProviderHealthStatus.UNAUTHORIZED else ProviderHealthStatus.HEALTHY
    }

    override suspend fun generate(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var currentKey = preferences.getNextActiveKey("gemini") ?: preferences.apiKey

        if (currentKey.isBlank()) {
            throw IllegalStateException("No active Gemini API key available in key pool.")
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$currentKey"

        val contents = mutableListOf<Map<String, Any>>()
        contents.add(
            mapOf(
                "role" to "user",
                "parts" to listOf(mapOf("text" to request.prompt))
            )
        )

        val payload = mutableMapOf<String, Any>(
            "contents" to contents
        )

        if (request.tools.isNotEmpty()) {
            payload["tools"] = GeminiSchemaConverter.convertTools(request.tools)
        }

        val reqBody = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder().url(url).post(reqBody).build()

        val httpResponse = try {
            client.newCall(httpRequest).execute()
        } catch (e: Exception) {
            throw e
        }

        val latency = System.currentTimeMillis() - startTime
        val respBody = httpResponse.body?.string() ?: ""

        if (httpResponse.code == 429) {
            preferences.reportKeyRateLimit("gemini", currentKey)
            throw IllegalStateException("Gemini Rate limit (429) encountered. Cooldown applied to key.")
        }
        if (httpResponse.code in listOf(401, 403)) {
            preferences.reportKeyInvalid("gemini", currentKey)
            throw IllegalStateException("Gemini Authentication failed (${httpResponse.code}). Key marked invalid.")
        }
        if (!httpResponse.isSuccessful) {
            throw IllegalStateException("Gemini error ${httpResponse.code}: $respBody")
        }

        val json = gson.fromJson(respBody, JsonObject::class.java)
        var textResult = ""
        val toolCalls = mutableListOf<LlmStepDecision.InvokeTool>()

        val candidates = json.getAsJsonArray("candidates")
        if (candidates != null && candidates.size() > 0) {
            val parts = candidates.get(0).asJsonObject.getAsJsonObject("content")?.getAsJsonArray("parts")
            if (parts != null) {
                for (i in 0 until parts.size()) {
                    val p = parts.get(i).asJsonObject
                    if (p.has("text")) {
                        textResult += p.get("text").asString
                    }
                    if (p.has("functionCall")) {
                        val fn = p.getAsJsonObject("functionCall")
                        val fnName = fn.get("name")?.asString ?: ""
                        val argsMap = mutableMapOf<String, Any?>()
                        if (fn.has("args") && fn.get("args").isJsonObject) {
                            fn.getAsJsonObject("args").entrySet().forEach { (k, v) ->
                                argsMap[k] = if (v.isJsonPrimitive) v.asString else v.toString()
                            }
                        }
                        toolCalls.add(LlmStepDecision.InvokeTool(fnName, argsMap))
                    }
                }
            }
        }

        // Token estimation
        val promptTokens = request.prompt.length / 4
        val compTokens = textResult.length / 4
        preferences.recordUsage("gemini", promptTokens, compTokens, (promptTokens + compTokens) * 0.0000005)

        LlmResponse(
            content = textResult,
            toolCalls = toolCalls,
            promptTokens = promptTokens,
            completionTokens = compTokens,
            latencyMs = latency,
            providerId = id,
            modelName = "gemini-1.5-flash"
        )
    }
}

/**
 * OpenAI Compatible Provider implementation (supports DeepSeek, OpenRouter, Groq, Ollama, etc.).
 */
class OpenAiCompatibleProvider(
    private val preferences: AppPreferences,
    override val id: String = "openai_compatible",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val modelName: String = "gpt-4o-mini",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) : LlmProvider {

    override val supportsFunctionCalling: Boolean = true

    override suspend fun health(): ProviderHealthStatus {
        val key = preferences.getNextActiveKey(id)
        return if (key.isNullOrBlank()) ProviderHealthStatus.UNAUTHORIZED else ProviderHealthStatus.HEALTHY
    }

    override suspend fun generate(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val currentKey = preferences.getNextActiveKey(id)
            ?: throw IllegalStateException("No active API key configured for provider '$id'.")

        val url = if (baseUrl.endsWith("/")) "${baseUrl}chat/completions" else "$baseUrl/chat/completions"

        val messages = mutableListOf<Map<String, String>>()
        if (!request.systemPrompt.isNullOrBlank()) {
            messages.add(mapOf("role" to "system", "content" to request.systemPrompt))
        }
        messages.add(mapOf("role" to "user", "content" to request.prompt))

        val payload = mutableMapOf<String, Any>(
            "model" to modelName,
            "messages" to messages,
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens
        )

        val reqBody = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $currentKey")
            .post(reqBody)
            .build()

        val httpResponse = client.newCall(httpRequest).execute()
        val latency = System.currentTimeMillis() - startTime
        val respBody = httpResponse.body?.string() ?: ""

        if (httpResponse.code == 429) {
            preferences.reportKeyRateLimit(id, currentKey)
            throw IllegalStateException("Rate limit (429) encountered for provider '$id'.")
        }
        if (httpResponse.code in listOf(401, 403)) {
            preferences.reportKeyInvalid(id, currentKey)
            throw IllegalStateException("Authentication failed (${httpResponse.code}) for provider '$id'.")
        }
        if (!httpResponse.isSuccessful) {
            throw IllegalStateException("OpenAI compatible provider error ${httpResponse.code}: $respBody")
        }

        val json = gson.fromJson(respBody, JsonObject::class.java)
        val choices = json.getAsJsonArray("choices")
        val content = if (choices != null && choices.size() > 0) {
            choices.get(0).asJsonObject.getAsJsonObject("message")?.get("content")?.asString ?: ""
        } else ""

        val usage = json.getAsJsonObject("usage")
        val pTokens = usage?.get("prompt_tokens")?.asInt ?: (request.prompt.length / 4)
        val cTokens = usage?.get("completion_tokens")?.asInt ?: (content.length / 4)

        preferences.recordUsage(id, pTokens, cTokens, (pTokens * 0.00000015) + (cTokens * 0.0000006))

        LlmResponse(
            content = content,
            promptTokens = pTokens,
            completionTokens = cTokens,
            latencyMs = latency,
            providerId = id,
            modelName = modelName
        )
    }
}
