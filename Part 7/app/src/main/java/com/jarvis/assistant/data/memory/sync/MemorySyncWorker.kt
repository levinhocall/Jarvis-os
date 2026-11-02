package com.jarvis.assistant.data.memory.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.JsonObject
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.data.memory.MemoryDatabase
import com.jarvis.assistant.data.memory.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Local-authoritative sync. Stable IDs make retries idempotent; tombstones propagate deletion. */
class MemorySyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        private const val TAG = "MemorySyncWorker"
        private const val SCOPE = "jarvis-os-personal"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.MEMORY_SYNC_ENDPOINT.trim().trimEnd('/')
        if (endpoint.isBlank()) return@withContext Result.success()
        val base = endpoint.toHttpUrlOrNull() ?: return@withContext Result.failure()
        if (base.scheme != "https" && !isPrivateHttpHost(base.host)) return@withContext Result.failure()

        try {
            val memories = MemoryDatabase.getInstance(applicationContext).memoryDao().getAllMemoriesForSync()
            for (memory in memories) {
                val remoteId = "jarvis-local-${memory.id}"
                val sensitive = memory.sensitivity.uppercase() in setOf("SECRET", "CREDENTIAL", "AUTHENTICATION", "CONFIDENTIAL")
                if (memory.status != MemoryEntity.STATUS_ACTIVE || sensitive || memory.isExpired) {
                    val deleteUrl = base.newBuilder()
                        .addPathSegments("v1/memories/$remoteId")
                        .addQueryParameter("user_id", SCOPE)
                        .build()
                    client.newCall(Request.Builder().url(deleteUrl).delete().build()).execute().use {
                        if (!it.isSuccessful && it.code != 404 && (it.code >= 500 || it.code == 429)) return@withContext Result.retry()
                        if (!it.isSuccessful && it.code != 404) return@withContext Result.failure()
                    }
                    continue
                }

                val payload = JsonObject().apply {
                    addProperty("user_id", SCOPE)
                    addProperty("memory_id", remoteId)
                    add("messages", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", "${memory.key}: ${memory.value}")
                        })
                    })
                    add("metadata", JsonObject().apply {
                        addProperty("local_id", memory.id)
                        addProperty("category", memory.category)
                        addProperty("memory_type", memory.memoryType)
                        addProperty("provenance", memory.provenance)
                        addProperty("sensitivity", memory.sensitivity)
                        addProperty("importance", memory.importance)
                        addProperty("confidence", memory.confidence)
                        addProperty("pinned", memory.pinned)
                        addProperty("local_timestamp", memory.timestamp)
                        memory.sourceConversationId?.let { addProperty("source_conversation_id", it) }
                        memory.sourceMessageId?.let { addProperty("source_message_id", it) }
                        memory.expiryTimeMs?.let { addProperty("expiry_time_ms", it) }
                    })
                }
                val request = Request.Builder()
                    .url(base.newBuilder().addPathSegments("v1/memories").build())
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use {
                    if (!it.isSuccessful) {
                        if (it.code >= 500 || it.code == 429) return@withContext Result.retry()
                        return@withContext Result.failure()
                    }
                }
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Memory sync attempt failed; WorkManager will retry.", error)
            Result.retry()
        }
    }

    private fun isPrivateHttpHost(host: String): Boolean {
        val value = host.lowercase()
        if (value == "localhost" || value == "127.0.0.1" || value.endsWith(".local")) return true
        if (value.startsWith("10.") || value.startsWith("192.168.")) return true
        val parts = value.split(".")
        return parts.size == 4 && parts[0] == "172" && (parts[1].toIntOrNull() in 16..31)
    }
}
