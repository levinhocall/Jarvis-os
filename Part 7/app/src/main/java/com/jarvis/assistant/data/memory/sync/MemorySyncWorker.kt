package com.jarvis.assistant.data.memory.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.data.memory.MemoryDatabase
import com.jarvis.assistant.data.memory.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** Bidirectional sync with stable IDs, tombstones, conflict resolution and durable keyset cursor. */
class MemorySyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        private const val TAG = "MemorySyncWorker"
        private const val SCOPE = "jarvis-os-personal"
        private const val PAGE_SIZE = 200
        private const val MAX_PULL_PAGES_PER_RUN = 20
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
            val dao = MemoryDatabase.getInstance(applicationContext).memoryDao()
            pushLocalChanges(base, dao)?.let { return@withContext it }
            pullRemoteChanges(base, dao)?.let { return@withContext it }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Bidirectional memory sync failed; WorkManager will retry.", error)
            Result.retry()
        }
    }

    /** Upload only local changes; deletions and private records are always propagated as tombstones. */
    private suspend fun pushLocalChanges(base: HttpUrl, dao: com.jarvis.assistant.data.memory.MemoryDao): Result? {
        val memories = dao.getAllMemoriesForSync()
        for (memory in memories) {
            val sensitive = memory.sensitivity.uppercase() in setOf("SECRET", "CREDENTIAL", "AUTHENTICATION", "CONFIDENTIAL")
            val mustDelete = memory.status != MemoryEntity.STATUS_ACTIVE || sensitive || memory.isExpired
            if (memory.lastSyncedAt != null && memory.timestamp <= memory.lastSyncedAt && !(memory.isExpired && mustDelete)) continue
            val remoteId = memory.syncId.ifBlank { "jarvis-local-${memory.id}" }
            if (mustDelete) {
                val deleteUrl = base.newBuilder()
                    .addPathSegments("v1/memories/$remoteId")
                    .addQueryParameter("user_id", SCOPE)
                    .build()
                client.newCall(Request.Builder().url(deleteUrl).delete().build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 404) {
                        return if (response.code >= 500 || response.code == 429) Result.retry() else Result.failure()
                    }
                }
                dao.markMemorySynced(memory.id, memory.timestamp)
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
                    addProperty("sync_id", remoteId)
                    addProperty("key", memory.key)
                    addProperty("value", memory.value)
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return if (response.code >= 500 || response.code == 429) Result.retry() else Result.failure()
                }
            }
            dao.markMemorySynced(memory.id, memory.timestamp)
        }
        return null
    }

    /** Pull changed records including tombstones and persist a compound (updated_at,id) cursor per page. */
    private suspend fun pullRemoteChanges(base: HttpUrl, dao: com.jarvis.assistant.data.memory.MemoryDao): Result? {
        var state = dao.getMemorySyncState(SCOPE)
            ?: MemorySyncStateEntity(syncKey = SCOPE)
        repeat(MAX_PULL_PAGES_PER_RUN) {
            val urlBuilder = base.newBuilder()
                .addPathSegments("v1/memories")
                .addQueryParameter("user_id", SCOPE)
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("include_deleted", "true")
            if (state.cursorUpdatedAt.isNotBlank()) {
                urlBuilder.addQueryParameter("updated_after", state.cursorUpdatedAt)
                if (state.cursorId.isNotBlank()) urlBuilder.addQueryParameter("after_id", state.cursorId)
            }
            val request = Request.Builder().url(urlBuilder.build()).get().build()
            val responseBody = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return if (response.code >= 500 || response.code == 429) Result.retry() else Result.failure()
                }
                response.body?.string().orEmpty()
            }
            val root = JsonParser.parseString(responseBody).asJsonObject
            val rows = root.getAsJsonArray("results") ?: return Result.retry()
            if (rows.size() == 0) return null
            for (element in rows) {
                val remote = element.asJsonObject
                mergeRemoteRecord(remote, dao)
            }
            val cursor = root.getAsJsonObject("next_cursor")
            val nextUpdatedAt = cursor?.stringOrNull("updated_at").orEmpty()
            val nextId = cursor?.stringOrNull("id").orEmpty()
            if (nextUpdatedAt.isBlank() || nextId.isBlank()) return Result.retry()
            state = MemorySyncStateEntity(
                syncKey = SCOPE,
                cursorUpdatedAt = nextUpdatedAt,
                cursorId = nextId,
                updatedAt = System.currentTimeMillis()
            )
            dao.saveMemorySyncState(state)
            if (rows.size() < PAGE_SIZE) return null
        }
        // More pages remain; retry soon rather than holding a worker indefinitely.
        return Result.retry()
    }

    private suspend fun mergeRemoteRecord(remote: JsonObject, dao: com.jarvis.assistant.data.memory.MemoryDao) {
        val remoteId = remote.stringOrNull("id") ?: return
        val content = remote.stringOrNull("memory").orEmpty()
        val metadata = remote.getAsJsonObject("metadata") ?: JsonObject()
        val remoteTimestamp = parseTimestamp(remote.stringOrNull("updated_at"))
        val deleted = remote.stringOrNull("deleted_at") != null
        // Local SQLite row IDs are device-specific. Only the stable syncId may identify
        // a record across devices; metadata.local_id is diagnostic and never used as a key.
        val existing = dao.getMemoryBySyncId(remoteId)
        // Local pending writes are uploaded before this pull phase. Once here, the server's
        // updated_at is the authoritative order, avoiding cross-device wall-clock skew.

        val key = metadata.stringOrNull("key") ?: content.substringBefore(':').trim().ifBlank { "remote-memory-$remoteId" }
        val value = metadata.stringOrNull("value") ?: content.substringAfter(':', content).trim()
        val sensitivity = metadata.stringOrNull("sensitivity") ?: "PREFERENCE"
        if (sensitivity.uppercase() in setOf("SECRET", "CREDENTIAL", "AUTHENTICATION", "CONFIDENTIAL") && !deleted) return
        val merged = MemoryEntity(
            id = existing?.id ?: 0L,
            syncId = remoteId,
            lastSyncedAt = remoteTimestamp,
            category = metadata.stringOrNull("category") ?: MemoryEntity.CATEGORY_NOTES,
            key = key,
            value = value,
            rawText = content,
            timestamp = remoteTimestamp,
            provenance = metadata.stringOrNull("provenance") ?: "REMOTE_SYNC",
            sensitivity = sensitivity,
            expiryTimeMs = metadata.longOrNull("expiry_time_ms"),
            memoryType = metadata.stringOrNull("memory_type") ?: MemoryEntity.TYPE_PERSONAL_FACT,
            status = if (deleted) MemoryEntity.STATUS_DELETED else MemoryEntity.STATUS_ACTIVE,
            importance = metadata.floatOrNull("importance") ?: 0.5f,
            confidence = metadata.floatOrNull("confidence") ?: 0.8f,
            sourceConversationId = metadata.stringOrNull("source_conversation_id"),
            sourceMessageId = metadata.stringOrNull("source_message_id"),
            embeddingJson = null,
            embeddingModel = null,
            embeddingDimension = 0,
            pinned = metadata.booleanOrNull("pinned") ?: false
        )
        if (existing == null) dao.insertMemory(merged) else dao.updateMemory(merged)
    }

    private fun parseTimestamp(value: String?): Long = runCatching {
        if (value.isNullOrBlank()) 0L else OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.longOrNull(name: String): Long? =
        get(name)?.takeIf { !it.isJsonNull }?.asLong

    private fun JsonObject.floatOrNull(name: String): Float? =
        get(name)?.takeIf { !it.isJsonNull }?.asFloat

    private fun JsonObject.booleanOrNull(name: String): Boolean? =
        get(name)?.takeIf { !it.isJsonNull }?.asBoolean

    private fun isPrivateHttpHost(host: String): Boolean {
        val value = host.lowercase()
        if (value == "localhost" || value == "127.0.0.1" || value.endsWith(".local")) return true
        if (value.startsWith("10.") || value.startsWith("192.168.")) return true
        val parts = value.split(".")
        return parts.size == 4 && parts[0] == "172" && (parts[1].toIntOrNull()?.let { it in 16..31 } == true)
    }
}
