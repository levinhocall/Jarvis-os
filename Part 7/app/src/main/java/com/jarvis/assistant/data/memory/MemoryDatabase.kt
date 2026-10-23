package com.jarvis.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jarvis.assistant.data.chat.ChatDao
import com.jarvis.assistant.data.chat.ChatMessageEntity
import com.jarvis.assistant.data.chat.ChatSessionEntity
import com.jarvis.assistant.data.mission.MissionDao
import com.jarvis.assistant.data.mission.MissionEntity

@Database(
    entities = [
        MemoryEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        MissionEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao
    abstract fun chatDao(): ChatDao
    abstract fun missionDao(): MissionDao

    companion object {
        private const val DATABASE_NAME = "jarvis_memory.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN provenance TEXT NOT NULL DEFAULT 'EXPLICIT_USER_STATEMENT'")
                db.execSQL("ALTER TABLE memories ADD COLUMN sensitivity TEXT NOT NULL DEFAULT 'PREFERENCE'")
                db.execSQL("ALTER TABLE memories ADD COLUMN expiryTimeMs INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create chat_sessions table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL DEFAULT 0,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        systemPrompt TEXT
                    )
                """.trimIndent())

                // 2. Create chat_messages table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'SENT',
                        metadata TEXT NOT NULL DEFAULT '{}'
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sessionId ON chat_messages(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)")

                // 3. Upgrade memories table with Phase B & C columns
                db.execSQL("ALTER TABLE memories ADD COLUMN memoryType TEXT NOT NULL DEFAULT 'PERSONAL_FACT'")
                db.execSQL("ALTER TABLE memories ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE memories ADD COLUMN importance REAL NOT NULL DEFAULT 0.5")
                db.execSQL("ALTER TABLE memories ADD COLUMN confidence REAL NOT NULL DEFAULT 0.8")
                db.execSQL("ALTER TABLE memories ADD COLUMN sourceConversationId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE memories ADD COLUMN sourceMessageId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE memories ADD COLUMN embeddingJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE memories ADD COLUMN embeddingModel TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE memories ADD COLUMN embeddingDimension INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS missions (
                        id TEXT NOT NULL PRIMARY KEY,
                        goal TEXT NOT NULL,
                        status TEXT NOT NULL,
                        planJson TEXT NOT NULL,
                        currentStepIndex INTEGER NOT NULL,
                        approvalState TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deduplicationKeysJson TEXT NOT NULL DEFAULT '[]',
                        outcomeSummary TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_missions_status ON missions(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_missions_updatedAt ON missions(updatedAt)")
            }
        }

        @Volatile
        private var instance: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
