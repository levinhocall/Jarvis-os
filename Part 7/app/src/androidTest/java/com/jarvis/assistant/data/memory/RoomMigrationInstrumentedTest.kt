package com.jarvis.assistant.data.memory

import androidx.room.Room
import androidx.room.RoomDatabase
import android.os.Looper
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Uses native SQLCipher + Room's actual upgrade/validation path, never direct migrate() calls. */
@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val key = android.util.Base64.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
    private fun factory(passphrase: ByteArray = key): SupportSQLiteOpenHelper.Factory =
        object : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
                SupportOpenHelperFactory(passphrase.copyOf(), null, false)
                    .create(MemoryDatabase.preservingConfiguration(configuration))
        }

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, MemoryDatabase::class.java, emptyList(), factory())

    @Before fun loadCipher() { System.loadLibrary("sqlcipher") }

    @Test fun encryptedV1ThroughV5ThenCurrentRoomPreservesRecords() = runBlocking {
        val name = "encrypted-upgrade-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 1).use { db ->
                db.execSQL("INSERT INTO memories(id,category,`key`,value,rawText,timestamp) VALUES (1,'Preferences','theme','dark','prefers dark',123)")
                assertCipher(db)
            }
            helper.runMigrationsAndValidate(name, 5, true,
                MemoryDatabase.MIGRATION_1_2, MemoryDatabase.MIGRATION_2_3,
                MemoryDatabase.MIGRATION_3_4, MemoryDatabase.MIGRATION_4_5
            ).use { db ->
                assertEquals(5, db.version)
                assertMemory(db)
                assertCipher(db)
                db.execSQL("INSERT INTO chat_sessions(id,title,createdAt,updatedAt,isArchived,pinned) VALUES ('chat','Preserved',1,2,0,0)")
                db.execSQL("INSERT INTO chat_messages(id,sessionId,role,content,timestamp,status,metadata) VALUES ('msg','chat','USER','hello',1,'SENT','{}')")
                db.execSQL("INSERT INTO missions(id,goal,status,planJson,currentStepIndex,approvalState,updatedAt,deduplicationKeysJson) VALUES ('mission','Preserved','PAUSED','{}',0,'NONE',1,'[]')")
                db.execSQL("INSERT INTO notification_history(id,packageName,sender,message,timestamp,isRead) VALUES ('note','test','sender','preserved',1,0)")
            }
            // Reopen through a real Room v5 implementation, including Room identity validation.
            val historical = Room.databaseBuilder(context, HistoricalV5Database::class.java, name)
                .openHelperFactory(factory()).build()
            try { assertEquals(listOf(1L), historical.memoryDao().memoryIds()) }
            finally { historical.close() }

            val current = openRoom(name, key)
            try {
                current.awaitReady()
                assertEquals(8, current.openHelper.writableDatabase.version)
                assertMemory(current.openHelper.writableDatabase)
                assertEquals("hello", current.chatDao().getMessagesForSession("chat").single().content)
                assertEquals("Preserved", current.missionDao().getMissionById("mission")!!.goal)
                current.openHelper.writableDatabase.query("SELECT message FROM notification_history WHERE id='note'").use {
                    assertTrue(it.moveToFirst()); assertEquals("preserved", it.getString(0))
                }
                assertEquals("jarvis-local-1", current.memoryDao().getMemoryById(1)!!.syncId)
            } finally { current.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun readinessAwaitedOnMainOpensNativeRoomOnWorker() = runBlocking {
        val name = "encrypted-main-await-${UUID.randomUUID()}.db"
        val db = withContext(Dispatchers.Main) { openRoom(name, key) }
        try {
            withContext(Dispatchers.Main) {
                db.awaitReady()
                assertTrue(db.memoryDao().getAllMemories().isEmpty())
            }
        } finally { withContext(Dispatchers.IO) { db.close(); context.deleteDatabase(name) } }
    }

    @Test fun wrongAndMissingCipherKeyAreRejectedWithoutErasingDatabase() = runBlocking {
        val name = "encrypted-wrong-key-${UUID.randomUUID()}.db"
        try {
            createPayload(name)
            val file = context.getDatabasePath(name)
            val original = file.readBytes()
            for (badKey in listOf(ByteArray(32) { 3 }, ByteArray(0))) {
                val wrong = openRoom(name, badKey)
                try { assertTrue("Wrong/missing key must fail", runCatching { wrong.awaitReady() }.isFailure) }
                finally { wrong.close() }
                assertTrue(file.isFile)
                assertArrayEquals(original, file.readBytes())
            }
            val correct = openRoom(name, key)
            try { correct.awaitReady(); assertEquals("retained", correct.memoryDao().getAllMemories().single().value) }
            finally { correct.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun corruptCiphertextIsPreservedAndKnownGoodBackupCanBeRestored() = runBlocking {
        val name = "encrypted-corrupt-${UUID.randomUUID()}.db"
        try {
            createPayload(name)
            val file = context.getDatabasePath(name)
            val backup = file.readBytes()
            val corrupt = backup.copyOf().also { bytes -> repeat(minOf(128, bytes.size)) { bytes[it] = 0x5a } }
            file.writeBytes(corrupt)
            val broken = openRoom(name, key)
            try { assertTrue(runCatching { broken.awaitReady() }.isFailure) }
            finally { broken.close() }
            assertArrayEquals("Corruption must never trigger deleting default recovery", corrupt, file.readBytes())
            // Explicit test recovery from a verified backup, not invented repair of unreadable data.
            file.writeBytes(backup)
            val restored = openRoom(name, key)
            try { restored.awaitReady(); assertEquals("retained", restored.memoryDao().getAllMemories().single().value) }
            finally { restored.close() }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun createPayload(name: String) {
        val db = openRoom(name, key)
        try {
            db.awaitReady()
            db.memoryDao().insertMemory(MemoryEntity(category = "Notes", key = "test", value = "retained", rawText = "retained"))
        } finally { db.close() }
    }

    private fun openRoom(name: String, passphrase: ByteArray): MemoryDatabase =
        Room.databaseBuilder(context, MemoryDatabase::class.java, name)
            .openHelperFactory(factory(passphrase))
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    assertNotEquals("Native database must open off Main", Looper.getMainLooper(), Looper.myLooper())
                }
            })
            .addMigrations(MemoryDatabase.MIGRATION_1_2, MemoryDatabase.MIGRATION_2_3,
                MemoryDatabase.MIGRATION_3_4, MemoryDatabase.MIGRATION_4_5,
                MemoryDatabase.MIGRATION_5_6, MemoryDatabase.MIGRATION_6_7, MemoryDatabase.MIGRATION_7_8)
            .build()

    private fun assertMemory(db: SupportSQLiteDatabase) {
        db.query("SELECT value, rawText, timestamp FROM memories WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals("dark", it.getString(0))
            assertEquals("prefers dark", it.getString(1)); assertEquals(123L, it.getLong(2))
        }
    }
    private fun assertCipher(db: SupportSQLiteDatabase) {
        db.query("PRAGMA cipher_status").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
    }
}
