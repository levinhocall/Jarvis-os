package com.jarvis.assistant.data.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories WHERE status != 'DELETED' ORDER BY pinned DESC, timestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' ORDER BY pinned DESC, timestamp DESC")
    fun getActiveMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' ORDER BY pinned DESC, timestamp DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE category = :category AND status != 'DELETED' ORDER BY timestamp DESC")
    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE memoryType = :memoryType AND status = 'ACTIVE' ORDER BY timestamp DESC")
    fun getMemoriesByTypeFlow(memoryType: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE (rawText LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%') AND status != 'DELETED' ORDER BY timestamp DESC")
    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE (rawText LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%') AND status = 'ACTIVE' ORDER BY timestamp DESC")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("UPDATE memories SET status = :status WHERE id = :id")
    suspend fun updateMemoryStatus(id: Long, status: String)

    @Query("UPDATE memories SET status = 'DELETED' WHERE id = :id")
    suspend fun softDeleteMemory(id: Long)

    @Query("UPDATE memories SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE memories SET embeddingJson = :embeddingJson, embeddingModel = :model, embeddingDimension = :dimension WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embeddingJson: String, model: String, dimension: Int)

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND embeddingJson IS NOT NULL")
    suspend fun getActiveMemoriesWithEmbeddings(): List<MemoryEntity>

    @Query("DELETE FROM memories WHERE rawText LIKE '%' || :keyword || '%' OR `key` LIKE '%' || :keyword || '%' OR `value` LIKE '%' || :keyword || '%'")
    suspend fun deleteMemoriesByKeyword(keyword: String): Int

    @Query("SELECT * FROM memories WHERE `key` = :key AND category = :category AND status = 'ACTIVE' LIMIT 1")
    suspend fun getMemoryByKeyAndCategory(key: String, category: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE `key` = :key AND status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveMemoryByKey(key: String): MemoryEntity?

    @Query("DELETE FROM memories WHERE expiryTimeMs IS NOT NULL AND expiryTimeMs < :currentTime")
    suspend fun deleteExpiredMemories(currentTime: Long): Int

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()
}
