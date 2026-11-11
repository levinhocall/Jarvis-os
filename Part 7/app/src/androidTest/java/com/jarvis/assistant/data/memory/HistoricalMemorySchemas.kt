package com.jarvis.assistant.data.memory

import androidx.room.*
import com.jarvis.assistant.data.chat.ChatMessageEntity
import com.jarvis.assistant.data.chat.ChatSessionEntity
import com.jarvis.assistant.data.mission.MissionEntity
import com.jarvis.assistant.data.notification.NotificationHistoryEntity

// Test fixtures reconstructed from exact historical entity declarations; not shipped snapshots.
// V1: d227bca^, V5: 81c5ec1. See schemas/README.md for provenance and limits.
@Entity(tableName = "memories")
data class HistoricalMemoryV1(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,
    val key: String,
    val value: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "memories")
data class HistoricalMemoryV5(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,
    val key: String,
    val value: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val provenance: String = "EXPLICIT_USER_STATEMENT",
    val sensitivity: String = "PREFERENCE",
    val expiryTimeMs: Long? = null,
    val memoryType: String = "PERSONAL_FACT",
    val status: String = "ACTIVE",
    val importance: Float = 0.5f,
    val confidence: Float = 0.8f,
    val sourceConversationId: String? = null,
    val sourceMessageId: String? = null,
    val embeddingJson: String? = null,
    val embeddingModel: String? = null,
    val embeddingDimension: Int = 0,
    val pinned: Boolean = false
)

@Dao
interface HistoricalMemoryDao {
    @Query("SELECT id FROM memories ORDER BY id")
    suspend fun memoryIds(): List<Long>
}

@Database(entities = [HistoricalMemoryV1::class], version = 1, exportSchema = true)
abstract class HistoricalV1Database : RoomDatabase() {
    abstract fun memoryDao(): HistoricalMemoryDao
}

@Database(
    entities = [HistoricalMemoryV5::class, ChatSessionEntity::class, ChatMessageEntity::class,
        MissionEntity::class, NotificationHistoryEntity::class],
    version = 5, exportSchema = true
)
abstract class HistoricalV5Database : RoomDatabase() {
    abstract fun memoryDao(): HistoricalMemoryDao
}
