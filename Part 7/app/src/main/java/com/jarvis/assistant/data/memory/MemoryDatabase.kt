package com.jarvis.assistant.data.memory

import com.jarvis.assistant.data.directives.*
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.jarvis.assistant.data.chat.ChatDao
import com.jarvis.assistant.data.chat.ChatMessageEntity
import com.jarvis.assistant.data.chat.ChatSessionEntity
import com.jarvis.assistant.data.mission.MissionDao
import com.jarvis.assistant.data.mission.MissionEntity
import com.jarvis.assistant.data.notification.NotificationHistoryEntity
import com.jarvis.assistant.data.notification.NotificationHistoryDao
import com.jarvis.assistant.data.memory.sync.MemorySyncStateEntity
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import android.os.Looper

@Database(
    entities = [
        MemoryEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        MissionEntity::class,
        NotificationHistoryEntity::class,
        MemorySyncStateEntity::class,
        IdeaEntity::class,
        LearningProfileEntity::class,
        LearningAttemptEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun directiveDao(): DirectiveDao
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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE memories ADD COLUMN lastSyncedAt INTEGER")
                // Preserve the existing remote identity scheme for rows already synced.
                db.execSQL("UPDATE memories SET syncId = 'jarvis-local-' || id WHERE syncId = ''")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory_sync_state (
                        syncKey TEXT NOT NULL PRIMARY KEY,
                        cursorUpdatedAt TEXT NOT NULL,
                        cursorId TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ideas (id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, refinement TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_updatedAt ON ideas(updatedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS learning_profile (id INTEGER NOT NULL PRIMARY KEY, trainer TEXT NOT NULL, level TEXT NOT NULL, goal TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS learning_attempts (id TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL, trainer TEXT NOT NULL, level TEXT NOT NULL, goal TEXT NOT NULL, userText TEXT NOT NULL, reply TEXT NOT NULL, correction TEXT NOT NULL, grammar INTEGER, fluency INTEGER, pronunciation INTEGER, audioAssessed INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_attempts_createdAt ON learning_attempts(createdAt)")
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

            }

        /** The SQLite helper owns corruption handling; never delegate to its deleting default. */
        private fun preservingConfiguration(
            configuration: SupportSQLiteOpenHelper.Configuration
        ): SupportSQLiteOpenHelper.Configuration {
            val original = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
                override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    original.onUpgrade(db, oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    original.onDowngrade(db, oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
                override fun onCorruption(db: SupportSQLiteDatabase) {
                    throw IllegalStateException(
                        "Database corruption detected; database and sidecars retained for manual recovery."
                    )
                }
            }
            return SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false)
                .build()
        }

        /**
         * Defers SQLCipher loading, Keystore work, and any plaintext-to-encrypted migration
         * until Room first opens the database. Room DAO operations are dispatched off-main;
         * Application.onCreate therefore never waits for database encryption or file I/O.
         */
        private class DeferredSqlCipherOpenHelperFactory(context: Context) : SupportSQLiteOpenHelper.Factory {
            private val appContext = context.applicationContext

            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                return object : SupportSQLiteOpenHelper {
                    @Volatile private var delegate: SupportSQLiteOpenHelper? = null
                    @Volatile private var writeAheadLoggingEnabled = false

                    private fun getDelegate(): SupportSQLiteOpenHelper {
                        check(Looper.myLooper() != Looper.getMainLooper()) {
                            "JARVIS database must be opened from a background dispatcher."
                        }
                        delegate?.let { return it }
                        return synchronized(this) {
                            delegate ?: DatabaseEncryption.prepare(appContext)
                                .create(preservingConfiguration(configuration))
                                .also { helper ->
                                    helper.setWriteAheadLoggingEnabled(writeAheadLoggingEnabled)
                                    delegate = helper
                                }
                        }
                    }

                    override val databaseName: String?
                        get() = configuration.name

                    override val writableDatabase: SupportSQLiteDatabase
                        get() = getDelegate().writableDatabase

                    override val readableDatabase: SupportSQLiteDatabase
                        get() = getDelegate().readableDatabase

                    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                        writeAheadLoggingEnabled = enabled
                        delegate?.setWriteAheadLoggingEnabled(enabled)
                    }

                    override fun close() {
                        synchronized(this) {
                            delegate?.close()
                            delegate = null
                        }
                    }
                }
            }
        }

        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(DeferredSqlCipherOpenHelperFactory(context.applicationContext))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .addCallback(corruptionRecoveryCallback(context.applicationContext))
                    // Never silently erase private memory, chats, or missions when a migration is missing.
                    .build()
                    .also { database -> instance = database }
            }
        }
    }
}
