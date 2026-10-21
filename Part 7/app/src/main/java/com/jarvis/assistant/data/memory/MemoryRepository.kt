package com.jarvis.assistant.data.memory

import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {

    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> = memoryDao.getAllMemories()

    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> {
        return if (category == "All") {
            memoryDao.getAllMemoriesFlow()
        } else {
            memoryDao.getMemoriesByCategoryFlow(category)
        }
    }

    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>> = memoryDao.searchMemoriesFlow(query)

    suspend fun searchMemories(query: String): List<MemoryEntity> = memoryDao.searchMemories(query)

    suspend fun saveMemory(
        rawText: String,
        category: String = MemoryEntity.CATEGORY_NOTES,
        key: String = "",
        value: String = "",
        provenance: String = "EXPLICIT_USER_STATEMENT",
        sensitivity: String = "PREFERENCE",
        expiryTimeMs: Long? = null
    ): Long {
        val sanitizedRaw = EpisodicVectorMemory.sanitizeForStorage(rawText)
        val sanitizedVal = EpisodicVectorMemory.sanitizeForStorage(value.ifBlank { rawText })
        val calculatedKey = key.ifBlank {
            sanitizedRaw.take(30).trim()
        }

        // Deduplication & Conflict Prevention: Update existing record if key & category match
        val existing = memoryDao.getMemoryByKeyAndCategory(calculatedKey, category)
        return if (existing != null) {
            val updated = existing.copy(
                value = sanitizedVal,
                rawText = sanitizedRaw,
                timestamp = System.currentTimeMillis(),
                provenance = provenance,
                sensitivity = sensitivity,
                expiryTimeMs = expiryTimeMs
            )
            memoryDao.updateMemory(updated)
            existing.id
        } else {
            val entity = MemoryEntity(
                category = category,
                key = calculatedKey,
                value = sanitizedVal,
                rawText = sanitizedRaw,
                provenance = provenance,
                sensitivity = sensitivity,
                expiryTimeMs = expiryTimeMs
            )
            memoryDao.insertMemory(entity)
        }
    }

    suspend fun cleanupExpiredMemories(): Int {
        return memoryDao.deleteExpiredMemories(System.currentTimeMillis())
    }

    suspend fun editMemory(id: Long, newKey: String, newValue: String, newCategory: String? = null): Boolean {
        val all = memoryDao.getAllMemories()
        val target = all.firstOrNull { it.id == id } ?: return false
        val sanitizedVal = EpisodicVectorMemory.sanitizeForStorage(newValue)
        val updated = target.copy(
            key = newKey.trim(),
            value = sanitizedVal,
            rawText = "${newKey.trim()}: $sanitizedVal",
            category = newCategory ?: target.category,
            timestamp = System.currentTimeMillis()
        )
        memoryDao.updateMemory(updated)
        return true
    }

    suspend fun exportMemoriesAsJson(): String {
        val list = memoryDao.getAllMemories()
        val jsonArray = org.json.JSONArray()
        for (item in list) {
            val obj = org.json.JSONObject().apply {
                put("id", item.id)
                put("category", item.category)
                put("key", item.key)
                put("value", item.value)
                put("rawText", item.rawText)
                put("timestamp", item.timestamp)
                put("provenance", item.provenance)
                put("sensitivity", item.sensitivity)
                if (item.expiryTimeMs != null) {
                    put("expiryTimeMs", item.expiryTimeMs)
                }
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    suspend fun importMemoriesFromJson(jsonStr: String): Int {
        var count = 0
        try {
            val jsonArray = org.json.JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val cat = obj.optString("category", MemoryEntity.CATEGORY_NOTES)
                val key = obj.optString("key", "")
                val value = obj.optString("value", "")
                val raw = obj.optString("rawText", "$key: $value")
                val prov = obj.optString("provenance", "EXPLICIT_USER_STATEMENT")
                val sens = obj.optString("sensitivity", "PREFERENCE")
                val exp = if (obj.has("expiryTimeMs")) obj.getLong("expiryTimeMs") else null

                if (key.isNotBlank()) {
                    saveMemory(
                        rawText = raw,
                        category = cat,
                        key = key,
                        value = value,
                        provenance = prov,
                        sensitivity = sens,
                        expiryTimeMs = exp
                    )
                    count++
                }
            }
        } catch (_: Exception) {}
        return count
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        memoryDao.updateMemory(memory)
    }

    suspend fun deleteMemoryById(id: Long) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun deleteMemoriesByKeyword(keyword: String): Int {
        return memoryDao.deleteMemoriesByKeyword(keyword)
    }

    suspend fun clearAll() {
        memoryDao.clearAllMemories()
    }
}
