package com.jarvis.assistant.data.memory

import androidx.room.*
import java.util.UUID

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

// Immutable v5 fixtures: never import production entities into a historical database.
// Exact persisted shapes at 81c5ec1; schema identity must remain 5a1ef08ec69aa450a6910b77c3e04905.
@Entity(tableName = "chat_sessions")
data class HistoricalChatSessionV5(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false,
    val pinned: Boolean = false,
    val systemPrompt: String? = null
)

@Entity(
    tableName = "chat_messages",
    indices = [
        Index("sessionId"),
        Index("timestamp")
    ]
)
data class HistoricalChatMessageV5(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String, // "USER", "ASSISTANT", "SYSTEM"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "SENT", // "SENT", "DELIVERED", "FAILED", "STREAMING"
    val metadata: String = "{}"
)

@Entity(
    tableName = "missions",
    indices = [
        Index("status"),
        Index("updatedAt")
    ]
)
data class HistoricalMissionV5(
    @PrimaryKey
    val id: String,
    val goal: String,
    val status: String,
    val planJson: String,
    val currentStepIndex: Int,
    val approvalState: String,
    val updatedAt: Long,
    val deduplicationKeysJson: String = "[]",
    val outcomeSummary: String? = null
)

@Entity(
    tableName = "notification_history",
    indices = [Index(value = ["timestamp"]), Index(value = ["packageName"])]
)
data class HistoricalNotificationHistoryV5(
    @PrimaryKey val id: String,
    val packageName: String,
    val sender: String,
    val message: String,
    val timestamp: Long,
    val isRead: Boolean = false
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
    entities = [HistoricalMemoryV5::class, HistoricalChatSessionV5::class, HistoricalChatMessageV5::class,
        HistoricalMissionV5::class, HistoricalNotificationHistoryV5::class],
    version = 5, exportSchema = true
)
abstract class HistoricalV5Database : RoomDatabase() {
    abstract fun memoryDao(): HistoricalMemoryDao
}
