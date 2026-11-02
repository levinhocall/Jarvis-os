package com.jarvis.assistant.data.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.jarvis.assistant.data.memory.sync.MemorySyncStateEntity

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories WHERE status != 'DELETED' ORDER BY pinned DESC, timestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' ORDER BY pinned DESC, timestamp DESC")
    fun getActiveMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' ORDER BY pinned DESC, timestamp DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    /** Includes tombstones and superseded rows so remote sync can propagate deletions. */
    @Query("SELECT * FROM memories ORDER BY timestamp ASC, id ASC")
    suspend fun getAllMemoriesForSync(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE syncId = :syncId LIMIT 1")
    suspend fun getMemoryBySyncId(syncId: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: Long): MemoryEntity?

    @Query("UPDATE memories SET lastSyncedAt = :syncedAt WHERE id = :id")
    suspend fun markMemorySynced(id: Long, syncedAt: Long)

    @Query("SELECT * FROM memory_sync_state WHERE syncKey = :syncKey LIMIT 1")
    suspend fun getMemorySyncState(syncKey: String): MemorySyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMemorySyncState(state: MemorySyncStateEntity)

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

    suspend fun deleteMemory(memory: MemoryEntity) {
        softDeleteMemory(memory.id)
    }

    /** Explicit user erasure includes superseded versions of the same fact. */
    @Query("DELETE FROM memories WHERE `key` = (SELECT `key` FROM memories WHERE id = :id)")
    suspend fun permanentlyDeleteMemoryById(id: Long): Int

    @Query("UPDATE memories SET status = 'DELETED', timestamp = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("UPDATE memories SET status = :status, timestamp = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE id = :id")
    suspend fun updateMemoryStatus(id: Long, status: String)

    @Query("UPDATE memories SET status = :status, timestamp = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE `key` = :key AND status = 'ACTIVE'")
    suspend fun updateActiveMemoriesByKey(key: String, status: String)

    /** Supersede the previous active value and insert its replacement atomically. */
    @Transaction
    suspend fun insertReplacingActiveMemory(memory: MemoryEntity): Long {
        updateActiveMemoriesByKey(memory.key, MemoryEntity.STATUS_SUPERSEDED)
        return insertMemory(memory)
    }

    @Query("UPDATE memories SET status = 'DELETED', timestamp = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE id = :id")
    suspend fun softDeleteMemory(id: Long)

    @Query("UPDATE memories SET pinned = :pinned, timestamp = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE memories SET embeddingJson = :embeddingJson, embeddingModel = :model, embeddingDimension = :dimension WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embeddingJson: String, model: String, dimension: Int)

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND embeddingJson IS NOT NULL")
    suspend fun getActiveMemoriesWithEmbeddings(): List<MemoryEntity>

    @Query("UPDATE memories SET status = 'DELETED', timestamp = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE status != 'DELETED' AND (rawText LIKE '%' || :keyword || '%' OR `key` LIKE '%' || :keyword || '%' OR `value` LIKE '%' || :keyword || '%')")
    suspend fun deleteMemoriesByKeyword(keyword: String): Int

    @Query("SELECT * FROM memories WHERE `key` = :key AND category = :category AND status = 'ACTIVE' LIMIT 1")
    suspend fun getMemoryByKeyAndCategory(key: String, category: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE `key` = :key AND status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveMemoryByKey(key: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE `key` = :key ORDER BY timestamp DESC")
    suspend fun getMemoriesByKeyIncludingInactive(key: String): List<MemoryEntity>

    @Query("UPDATE memories SET status = 'DELETED', timestamp = :currentTime WHERE status != 'DELETED' AND expiryTimeMs IS NOT NULL AND expiryTimeMs < :currentTime")
    suspend fun deleteExpiredMemories(currentTime: Long): Int

    @Query("UPDATE memories SET status = 'DELETED', timestamp = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE status != 'DELETED'")
    suspend fun clearAllMemories()
}
