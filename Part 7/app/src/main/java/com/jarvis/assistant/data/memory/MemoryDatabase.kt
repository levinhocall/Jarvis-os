package com.jarvis.assistant.data.memory

import android.content.Context
import android.util.Log
import java.io.File
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
import com.jarvis.assistant.data.notification.NotificationHistoryEntity
import com.jarvis.assistant.data.notification.NotificationHistoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@Database(
    entities = [
        MemoryEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        MissionEntity::class,
        NotificationHistoryEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao
    abstract fun chatDao(): ChatDao
    abstract fun missionDao(): MissionDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS notification_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        packageName TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        message TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isRead INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_history_timestamp ON notification_history(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_history_packageName ON notification_history(packageName)")
            }
        }

        @Volatile
        private var instance: MemoryDatabase? = null

        private fun corruptionRecoveryCallback(context: Context): RoomDatabase.Callback =
            object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    DatabaseKeyStore(context.applicationContext).markInitializationComplete()
                }

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    // Room will close and remove the corrupt database after this callback.
                    // Preserve the original bytes and SQLite sidecars inside app-private,
                    // non-backed-up storage so support tooling can attempt a later salvage.
                    val source = context.getDatabasePath(DATABASE_NAME)
                    if (!source.exists()) {
                        Log.e("MemoryDatabase", "Corruption reported but database file was not present.")
                        return
                    }

                    val recoveryDirectory = File(context.noBackupFilesDir, "database-recovery")
                    if (!recoveryDirectory.exists() && !recoveryDirectory.mkdirs()) {
                        Log.e("MemoryDatabase", "Could not create private database recovery directory.")
                        return
                    }

                    val backup = File(
                        recoveryDirectory,
                        "jarvis_memory_corrupt_${System.currentTimeMillis()}.db"
                    )
                    try {
                        source.copyTo(backup, overwrite = false)
                        listOf("-wal", "-shm").forEach { suffix ->
                            val sidecar = File(source.absolutePath + suffix)
                            if (sidecar.exists()) {
                                sidecar.copyTo(File(backup.absolutePath + suffix), overwrite = false)
                            }
                        }
                        Log.e("MemoryDatabase", "Corrupt database snapshot preserved in app-private recovery storage.")
                    } catch (error: Exception) {
                        Log.e("MemoryDatabase", "Could not preserve corrupt database snapshot.", error)
                        backup.delete()
                    }
                }
            }

        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    // Complete/recover encryption before Room opens the database file.
                    val encryptedOpenHelperFactory = runBlocking(Dispatchers.IO) {
                        DatabaseEncryption.prepare(context.applicationContext)
                    }
                    Room.databaseBuilder(
                        context.applicationContext,
                        MemoryDatabase::class.java,
                        DATABASE_NAME
                    )
                    .openHelperFactory(encryptedOpenHelperFactory)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .addCallback(corruptionRecoveryCallback(context.applicationContext))
                    // Never silently erase private memory, chats, or missions when a migration is missing.
                    .build()
                    .also { database ->
                        database.openHelper.writableDatabase
                        DatabaseKeyStore(context.applicationContext).markInitializationComplete()
                        instance = database
                    }
                }
            }
        }
    }
}
