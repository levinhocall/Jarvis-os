package com.jarvis.assistant.data.memory

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native SQLCipher tests: compilation alone is NOT execution or device timing evidence. */
@RunWith(AndroidJUnit4::class)
class ChatSchemaV9InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val key = android.util.Base64.encode(
        ByteArray(32).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP
    )

    private fun factory(): SupportSQLiteOpenHelper.Factory = object : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            SupportOpenHelperFactory(key.copyOf(), null, false)
                .create(MemoryDatabase.preservingConfiguration(configuration))
    }

    @get:Rule val helper = MigrationTestHelper(instrumentation, MemoryDatabase::class.java, emptyList(), factory())

    @Before fun loadCipher() { System.loadLibrary("sqlcipher") }

    @Test fun tenThousandMessageUpgradePreservesAllOldColumnsAndAssignsPerSessionPositions() = runBlocking(Dispatchers.IO) {
        val name = "chat-v9-seeded-${UUID.randomUUID()}.db"
        try {
            val before = seedV8(name, 10_000)
            val start = SystemClock.elapsedRealtimeNanos()
            helper.runMigrationsAndValidate(name, 9, true, MemoryDatabase.MIGRATION_8_9).use { upgraded ->
                // Includes open/upgrade/Room schema validation, excludes seed and assertions.
                val upgradeAndValidationMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                Log.i(TAG, "SQLCipher v8-to-v9 open+upgrade+validation: $upgradeAndValidationMs ms; seededMessages=10000")
                assertMigrated(upgraded, before)
            }
            val room = openRoom(name)
            try {
                room.awaitReady(120_000)
                assertEquals(9, room.openHelper.writableDatabase.version)
                assertMigrated(room.openHelper.writableDatabase, before)
                assertTrue(room.initializationState.value is DatabaseState.Ready<*>)
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun emptyV8UpgradeDoesNotInventMessagesReceiptsOrMigrationCompletion() = runBlocking(Dispatchers.IO) {
        val name = "chat-v9-empty-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 8).close()
            helper.runMigrationsAndValidate(name, 9, true, MemoryDatabase.MIGRATION_8_9).use { upgraded ->
                assertEquals(0L, count(upgraded, "chat_messages"))
                assertEquals(0L, count(upgraded, "chat_migration_receipts"))
                upgraded.execSQL("INSERT INTO chat_sessions(id,title,createdAt,updatedAt,isArchived,pinned) VALUES ('new','New',1,1,0,0)")
                upgraded.execSQL("INSERT INTO chat_messages(id,sessionId,role,content,timestamp,status,metadata) VALUES ('new','new','USER','hello',1,'SENT','{}')")
                upgraded.query("SELECT position FROM chat_messages WHERE id='new'").use {
                    assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun timeoutDuringTenThousandMessageUpgradeIsNotFailedAndTheSameOpenerCompletes() = runBlocking(Dispatchers.IO) {
        val name = "chat-v9-timeout-${UUID.randomUUID()}.db"
        val reachedBarrier = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        // Only this test holds the REAL migration before Room's upgrade transaction commits.
        val heldMigration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val start = SystemClock.elapsedRealtimeNanos()
                MemoryDatabase.MIGRATION_8_9.migrate(db)
                val migrationMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                Log.i(TAG, "SQLCipher v8-to-v9 migration body: $migrationMs ms; seededMessages=10000; excludes injected barrier")
                reachedBarrier.complete(Unit)
                check(release.await(120, TimeUnit.SECONDS)) { "Test migration barrier was not released" }
            }
        }
        var room: MemoryDatabase? = null
        try {
            val before = seedV8(name, 10_000)
            val opened = openRoom(name, heldMigration).also { room = it }
            val shortWaiter = async { runCatching { opened.awaitReady(25) } }
            withTimeout(120_000) { reachedBarrier.await() }
            assertTrue(shortWaiter.await().exceptionOrNull() is DatabaseReadinessTimeoutException)
            assertEquals(DatabaseState.Loading, opened.initializationState.value)
            release.countDown()
            assertSame(opened, opened.awaitReady(120_000))
            assertTrue(opened.initializationState.value is DatabaseState.Ready<*>)
            assertMigrated(opened.openHelper.writableDatabase, before)
        } finally {
            release.countDown()
            room?.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun actualMigrationFailureRollsBackAndARecreatedOpenerCanRetryWithoutLosingRows() = runBlocking(Dispatchers.IO) {
        val name = "chat-v9-rollback-${UUID.randomUUID()}.db"
        try {
            val before = seedV8(name, 10_000)
            val injectedFailure = object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MemoryDatabase.MIGRATION_8_9.migrate(db)
                    error("Injected upgrade failure before Room commit")
                }
            }
            val broken = openRoom(name, injectedFailure)
            try {
                assertTrue(runCatching { broken.awaitReady(120_000) }.isFailure)
                assertTrue(broken.initializationState.value is DatabaseState.Failed)
                assertTrue(context.getDatabasePath(name).isFile)
            } finally { broken.close() }
            // If the failed DDL/backfill had committed, retry would hit duplicate-column/table errors.
            val retry = openRoom(name)
            try {
                retry.awaitReady(120_000)
                assertMigrated(retry.openHelper.writableDatabase, before)
            } finally { retry.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun openRoom(name: String, migration: Migration = MemoryDatabase.MIGRATION_8_9): MemoryDatabase =
        Room.databaseBuilder(context, MemoryDatabase::class.java, name)
            .openHelperFactory(factory())
            .addMigrations(migration)
            .build()

    private data class Snapshot(
        val columns: Map<String, List<String>>,
        val digests: Map<String, List<Byte>>,
        val orderedMessageIds: List<String>
    )

    private fun seedV8(name: String, messages: Int): Snapshot = helper.createDatabase(name, 8).use { db ->
        // All DDL comes from the committed v8 export via MigrationTestHelper, not handmade v8 SQL.
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        val columns = tables.associateWith { table ->
            db.query("PRAGMA table_info(`$table`)").use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(1)) }
            }
        }
        db.beginTransaction()
        try {
            // Synthetic valid SQLite rows pin every original table/column, not just chat content.
            for (table in tables.filter { it != "chat_messages" && it != "chat_sessions" }) {
                val types = db.query("PRAGMA table_info(`$table`)").use { rows ->
                    buildList { while (rows.moveToNext()) add(rows.getString(2)) }
                }
                val fields = columns.getValue(table).joinToString(",") { "`$it`" }
                val placeholders = types.joinToString(",") { "?" }
                db.compileStatement("INSERT INTO `$table` ($fields) VALUES ($placeholders)").use { insert ->
                    types.forEachIndexed { i, type ->
                        when (type) {
                            "INTEGER" -> insert.bindLong(i + 1, 1)
                            "REAL" -> insert.bindDouble(i + 1, 0.5)
                            else -> insert.bindString(i + 1, "preserve-$table-$i")
                        }
                    }
                    insert.executeInsert()
                }
            }
            val sessions = listOf("session-z", "session-β", "session-हिन्दी", "empty-session")
            sessions.forEach { id ->
                db.execSQL("INSERT INTO chat_sessions(id,title,createdAt,updatedAt,isArchived,pinned) VALUES (?,?,1,2,0,0)", arrayOf(id, "Preserve $id"))
            }
            db.compileStatement("INSERT INTO chat_messages(id,sessionId,role,content,timestamp,status,metadata) VALUES (?,?,?,?,?,?,?)").use { insert ->
                repeat(messages) { i ->
                    insert.bindString(1, "seed-${(messages - i).toString().padStart(5, '0')}")
                    insert.bindString(2, sessions[i % 3])
                    insert.bindString(3, if (i % 2 == 0) "USER" else "ASSISTANT")
                    insert.bindString(4, if (i % 4 == 0) "Repeated text" else "नमस्ते, kal milte hain $i")
                    val timestamp = when {
                        i % 997 == 0 -> Long.MAX_VALUE
                        i % 991 == 0 -> -5L
                        else -> ((messages - i) / 4).toLong()
                    }
                    insert.bindLong(5, timestamp)
                    insert.bindString(6, if (i % 2 == 0) "SENT" else "STREAMING")
                    insert.bindString(7, "{\"source\":\"seed-$i\"}")
                    insert.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        Snapshot(columns, columns.mapValues { (table, names) -> digest(db, table, names) },
            db.query("SELECT id FROM chat_messages ORDER BY sessionId COLLATE BINARY,timestamp,rowid").use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            })
    }

    private fun assertMigrated(db: SupportSQLiteDatabase, before: Snapshot) {
        for ((table, names) in before.columns) {
            assertEquals("Original columns/rows changed: $table", before.digests.getValue(table), digest(db, table, names))
        }
        val ids = mutableListOf<String>()
        db.query("SELECT id,sessionId,position FROM chat_messages ORDER BY sessionId COLLATE BINARY,position").use { rows ->
            var previous: String? = null
            var next = 0L
            while (rows.moveToNext()) {
                val session = rows.getString(1)
                if (session != previous) { previous = session; next = 0L }
                assertEquals("Position is not contiguous within its session", next++, rows.getLong(2))
                ids += rows.getString(0)
            }
        }
        assertEquals(before.orderedMessageIds, ids)
        assertEquals(0L, count(db, "chat_migration_receipts"))
        db.query("SELECT name FROM sqlite_temp_master WHERE name='jarvis_chat_position_backfill'").use { assertFalse(it.moveToFirst()) }
    }

    private fun digest(db: SupportSQLiteDatabase, table: String, columns: List<String>): List<Byte> {
        val hash = MessageDigest.getInstance("SHA-256")
        val fields = columns.joinToString(",") { "`$it`" }
        db.query("SELECT $fields FROM `$table` ORDER BY rowid").use { rows ->
            while (rows.moveToNext()) {
                for (i in columns.indices) {
                    hash.update(rows.getType(i).toByte())
                    val bytes = if (rows.isNull(i)) ByteArray(0) else rows.getString(i).toByteArray(Charsets.UTF_8)
                    hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                    hash.update(bytes)
                }
            }
        }
        return hash.digest().toList()
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM `$table`").use { assertTrue(it.moveToFirst()); it.getLong(0) }

    companion object { private const val TAG = "JarvisDbMigrationTest" }
}
