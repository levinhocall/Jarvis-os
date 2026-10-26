package com.jarvis.assistant.forge

import android.content.Context
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.network.GeminiModelResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ForgeSelfTestResult(
    val success: Boolean,
    val model: String,
    val httpStatus: Int,
    val elapsedMs: Long,
    val summary: String
)

data class ForgeMiniBuildResult(
    val success: Boolean,
    val model: String,
    val stagesObserved: List<String>,
    val elapsedMs: Long,
    val htmlBytes: Int,
    val summary: String
)

object ForgeSelfTester {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun runProbe(context: Context): ForgeSelfTestResult = withContext(Dispatchers.IO) {
        val prefs = AppPreferences(context)
        val apiKey = prefs.getNextActiveKey("gemini")
            ?: prefs.apiKey.ifBlank { null }
            ?: return@withContext ForgeSelfTestResult(
                success = false,
                model = "N/A",
                httpStatus = 0,
                elapsedMs = 0L,
                summary = "Missing Gemini API key. Please configure your key in Settings."
            )

        val startTime = System.currentTimeMillis()

        try {
            val candidates = GeminiModelResolver.fetchCandidates(apiKey)
            var chosenModel = candidates.firstOrNull() ?: GeminiModelResolver.KNOWN_GOOD_FLASH
            var statusCode = 0
            var snippet = ""

            GeminiModelResolver.executeWithModelFallback(apiKey, candidates) { model ->
                chosenModel = model
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val body = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "Reply with OK")
                                })
                            })
                        })
                    })
                }

                val req = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    statusCode = resp.code
                    val respBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw GeminiApiException(resp.code, respBody)
                    }
                    snippet = respBody.take(80)
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            ForgeSelfTestResult(
                success = true,
                model = chosenModel,
                httpStatus = statusCode,
                elapsedMs = elapsed,
                summary = "SUCCESS (HTTP $statusCode) in ${elapsed}ms via model '$chosenModel'"
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            ForgeSelfTestResult(
                success = false,
                model = "Failed",
                httpStatus = (e as? GeminiApiException)?.code ?: 0,
                elapsedMs = elapsed,
                summary = "FAILED: ${e.message?.take(120)}"
            )
        }
    }

    suspend fun runMiniBuild(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): ForgeMiniBuildResult = withContext(Dispatchers.IO) {
        val prefs = AppPreferences(context)
        val apiKey = prefs.getNextActiveKey("gemini")
            ?: prefs.apiKey.ifBlank { null }
            ?: return@withContext ForgeMiniBuildResult(
                success = false,
                model = "N/A",
                stagesObserved = emptyList(),
                elapsedMs = 0L,
                htmlBytes = 0,
                summary = "Missing Gemini API key."
            )

        val startTime = System.currentTimeMillis()
        val client = WebsiteForgeClient(context)
        val stagesSeen = mutableListOf<String>()
        val htmlAccumulator = StringBuilder()

        try {
            val model = client.resolveModel(apiKey)
            stagesSeen.add("BLUEPRINT")
            withContext(Dispatchers.Main) { onProgress("Stage: BLUEPRINT") }

            client.streamGenerateSite(
                prompt = "Minimal 1-section test card with title 'Forge Test' and paragraph 'Build OK'",
                apiKeyOverride = apiKey
            ).collect { chunk ->
                htmlAccumulator.append(chunk)
                val detected = ForgeStageDetector.detect(htmlAccumulator.toString())
                if (detected.label !in stagesSeen) {
                    stagesSeen.add(detected.label)
                    withContext(Dispatchers.Main) { onProgress("Stage: ${detected.label}") }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            ForgeMiniBuildResult(
                success = true,
                model = model,
                stagesObserved = stagesSeen,
                elapsedMs = elapsed,
                htmlBytes = htmlAccumulator.length,
                summary = "MINI BUILD SUCCESS: ${htmlAccumulator.length} chars in ${elapsed}ms. Stages: ${stagesSeen.joinToString(" -> ")}"
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            ForgeMiniBuildResult(
                success = false,
                model = "Failed",
                stagesObserved = stagesSeen,
                elapsedMs = elapsed,
                htmlBytes = htmlAccumulator.length,
                summary = "MINI BUILD FAILED: ${e.message?.take(120)}"
            )
        }
    }
}
