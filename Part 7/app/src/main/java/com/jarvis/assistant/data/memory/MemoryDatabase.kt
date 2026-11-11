package com.jarvis.assistant.data.memory

import com.jarvis.assistant.data.directives.*
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.jarvis.assistant.data.chat.ChatDao
import com.jarvis.assistant.data.chat.ChatMessageEntity
import com.jarvis.assistant.data.chat.ChatMigrationReceiptEntity
import com.jarvis.assistant.data.chat.ChatSessionEntity
import com.jarvis.assistant.data.mission.MissionDao
import com.jarvis.assistant.data.mission.MissionEntity
import com.jarvis.assistant.data.notification.NotificationHistoryEntity
import com.jarvis.assistant.data.notification.NotificationHistoryDao
import com.jarvis.assistant.data.memory.sync.MemorySyncStateEntity
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ChatMigrationReceiptEntity::class,
        MissionEntity::class,
        NotificationHistoryEntity::class,
        MemorySyncStateEntity::class,
        IdeaEntity::class,
        LearningProfileEntity::class,
        LearningAttemptEntity::class,
        AutonomyDecisionEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class MemoryDatabase : RoomDatabase() {

    private val openScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private val readiness = DatabaseReadiness(openScope) {
        android.os.Trace.beginSection("JARVIS.SQLCipher.open")
        try { openHelper.writableDatabase } finally { android.os.Trace.endSection() }
        this@MemoryDatabase
    }

    val initializationState: kotlinx.coroutines.flow.StateFlow<DatabaseState<MemoryDatabase>> = readiness.state

    /** Retains the existing Unit-returning callback signature used by repositories. */
    suspend fun awaitReady() { readiness.awaitReady() }

    /** Deadline limits only the waiter; native encryption/migration continues on its owned IO scope. */
    suspend fun awaitReady(timeoutMs: Long): MemoryDatabase = readiness.awaitReady(timeoutMs)

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
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS autonomy_decisions (id TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL, reason TEXT NOT NULL, action TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_autonomy_decisions_createdAt ON autonomy_decisions(createdAt)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room runs this in its upgrade transaction on the existing owned IO opener.
                // Keep the source scan immutable: only rowid/position pairs go into the temp map.
                // The indexed lookup avoids a quadratic COUNT and does not materialize chat in Kotlin.
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TEMP TABLE jarvis_chat_position_backfill (messageRowId INTEGER PRIMARY KEY NOT NULL, position INTEGER NOT NULL)")
                db.compileStatement("INSERT INTO jarvis_chat_position_backfill(messageRowId,position) VALUES (?,?)").use { insert ->
                    db.query("SELECT rowid,sessionId FROM chat_messages ORDER BY sessionId COLLATE BINARY,timestamp,rowid").use { rows ->
                        var previousSession: String? = null
                        var position = 0L
                        while (rows.moveToNext()) {
                            val session = rows.getString(1)
                            if (session != previousSession) { position = 0L; previousSession = session }
                            insert.bindLong(1, rows.getLong(0))
                            insert.bindLong(2, position++)
                            insert.executeInsert()
                        }
                    }
                }
                db.execSQL("UPDATE chat_messages SET position = (SELECT position FROM jarvis_chat_position_backfill WHERE messageRowId = chat_messages.rowid)")
                db.execSQL("DROP TABLE jarvis_chat_position_backfill")
                db.execSQL("CREATE INDEX index_chat_messages_sessionId_position ON chat_messages(sessionId,position)")
                db.execSQL("""
                    CREATE TABLE chat_migration_receipts (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        sourceSha256 TEXT,
                        payloadVersion INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        committedCursor INTEGER NOT NULL,
                        uniqueMessageCount INTEGER NOT NULL,
                        importSessionId TEXT,
                        doneAtMs INTEGER,
                        backupVersionCode INTEGER,
                        cleanupDueAtMs INTEGER,
                        cleanupOfferedAtMs INTEGER,
                        updatedAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                // Empty on upgrade: Room history alone cannot prove that JSON was imported/backed up.
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
        internal fun preservingConfiguration(
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

        suspend fun getReadyInstance(
            context: Context,
            timeoutMs: Long = DatabaseReadiness.DEFAULT_WAIT_MS
        ): MemoryDatabase = getInstance(context).awaitReady(timeoutMs)

        // This builds only the Room facade. Never force open or await from this function.
        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(DeferredSqlCipherOpenHelperFactory { configuration ->
                        DatabaseEncryption.prepare(context.applicationContext)
                            .create(preservingConfiguration(configuration))
                    })
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .addCallback(corruptionRecoveryCallback(context.applicationContext))
                    // Never silently erase private memory, chats, or missions when a migration is missing.
                    .build()
                    .also { database -> instance = database }
            }
        }
    }
}
