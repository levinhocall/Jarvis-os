package com.jarvis.assistant.network

import com.google.gson.JsonParser
import com.jarvis.assistant.automation.agent.awaitCouncilResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class GeminiRequestKind { FAST_TEXT, REASONING, LIVE_AUDIO }

data class AvailableGeminiModel(val name: String, val methods: Set<String>)

/** ListModels establishes endpoint availability, not tool/audio compatibility beyond its methods. */
object GeminiModelSelection {
    private val safeName = Regex("models/[A-Za-z0-9._-]+")

    fun choose(models: List<AvailableGeminiModel>, kind: GeminiRequestKind, preferred: String = ""): String {
        val eligible = models.filter { model ->
            safeName.matches(model.name) && when (kind) {
                GeminiRequestKind.LIVE_AUDIO -> "bidiGenerateContent" in model.methods
                else -> "generateContent" in model.methods &&
                    listOf("image", "tts", "live", "embedding", "robotics").none { model.name.contains(it, true) }
            }
        }.distinctBy { it.name }
        val normalized = if (preferred.startsWith("models/")) preferred else "models/$preferred"
        eligible.firstOrNull { it.name == normalized }?.let { return it.name }
        val ranked = eligible.sortedWith(compareBy<AvailableGeminiModel> {
            when (kind) {
                GeminiRequestKind.REASONING -> if (it.name.contains("pro", true)) 0 else 1
                GeminiRequestKind.FAST_TEXT -> if (it.name.contains("flash", true)) 0 else 1
                GeminiRequestKind.LIVE_AUDIO -> if (it.name.contains("native-audio", true)) 0 else 1
            }
        }.thenBy { if (it.name.contains("preview", true) || it.name.contains("experimental", true)) 1 else 0 }
            .thenBy { it.name })
        return ranked.firstOrNull()?.name ?: throw IOException("No compatible Gemini model is available for ${kind.name}. Check project access in Settings.")
    }
}

/** Process-wide, bounded, account-scoped discovery. API keys are never put in URLs or persisted. */
internal class GeminiModelResolver(
    private val fetchPage: suspend (key: String, token: String?) -> String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = 10 * 60_000L
) {
    private data class Entry(val models: List<AvailableGeminiModel>, val fetchedAt: Long)
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Entry>()

    suspend fun resolve(key: String, kind: GeminiRequestKind, preferred: String = ""): String {
        require(key.isNotBlank()) { "Configure a Gemini API key in Settings." }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val models = mutex.withLock {
            val cached = cache[fingerprint]
            if (cached != null && now() - cached.fetchedAt in 0 until ttlMs) return@withLock cached.models
            val all = mutableListOf<AvailableGeminiModel>()
            val visited = mutableSetOf<String>()
            var token: String? = null
            do {
                if (visited.size >= 10) throw IOException("Gemini model catalogue exceeds pagination limit.")
                val json = JsonParser.parseString(fetchPage(key, token)).asJsonObject
                json.getAsJsonArray("models")?.forEach { element ->
                    val model = element.asJsonObject
                    val name = model.get("name")?.asString.orEmpty()
                    val methods = model.getAsJsonArray("supportedGenerationMethods")?.map { it.asString }?.toSet().orEmpty()
                    all.add(AvailableGeminiModel(name, methods))
                }
                token = json.get("nextPageToken")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                if (token != null && !visited.add(token!!)) throw IOException("Gemini returned a repeated model page.")
            } while (token != null)
            if (cache.size >= 8) cache.remove(cache.keys.first())
            cache[fingerprint] = Entry(all.toList(), now())
            all.toList()
        }
        return GeminiModelSelection.choose(models, kind, preferred)
    }
}

object GeminiModelCatalog {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val resolver = GeminiModelResolver(fetchPage = { key, token ->
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("generativelanguage.googleapis.com")
            .addPathSegments("v1beta/models").addQueryParameter("pageSize", "100")
            .apply { if (token != null) addQueryParameter("pageToken", token) }.build()
        val response = client.newCall(Request.Builder().url(url).header("x-goog-api-key", key).build())
            .awaitCouncilResponse(maxBytes = 4 * 1024 * 1024L)
        if (!response.isSuccessful) throw IOException("Gemini model discovery failed (HTTP ${response.code}). Check connectivity and project access.")
        response.body
    })

    suspend fun resolve(key: String, kind: GeminiRequestKind, preferred: String = ""): String =
        resolver.resolve(key.trim(), kind, preferred)
}
