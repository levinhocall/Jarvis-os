package com.jarvis.assistant.data.memory

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {
    @Test
    fun migrationsV1ThroughV5PreserveMemoryAndCreateExpectedTables() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val databaseName = "jarvis-migration-${UUID.randomUUID()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Version 1 schema before provenance, chat, mission and notification additions.
                    db.execSQL(
                        """
                        CREATE TABLE memories (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            category TEXT NOT NULL,
                            `key` TEXT NOT NULL,
                            value TEXT NOT NULL,
                            rawText TEXT NOT NULL,
                            timestamp INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "INSERT INTO memories(category, `key`, value, rawText, timestamp) VALUES (?, ?, ?, ?, ?)",
                        arrayOf("Preferences", "theme", "dark", "User prefers dark theme", 123456L)
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("The migration test controls version transitions explicitly.")
                }
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)

        try {
            val db = helper.writableDatabase
            MemoryDatabase.MIGRATION_1_2.migrate(db)
            MemoryDatabase.MIGRATION_2_3.migrate(db)
            MemoryDatabase.MIGRATION_3_4.migrate(db)
            MemoryDatabase.MIGRATION_4_5.migrate(db)

            db.query("SELECT category, `key`, value FROM memories WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Preferences", cursor.getString(0))
                assertEquals("theme", cursor.getString(1))
                assertEquals("dark", cursor.getString(2))
            }

            val expectedTables = setOf("memories", "chat_sessions", "chat_messages", "missions", "notification_history")
            val actualTables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) actualTables.add(cursor.getString(0))
            }
            assertTrue("All V5 application tables must exist", actualTables.containsAll(expectedTables))

            val memoryColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(memories)").use { cursor ->
                while (cursor.moveToNext()) memoryColumns.add(cursor.getString(1))
            }
            assertTrue(memoryColumns.containsAll(setOf(
                "provenance", "sensitivity", "expiryTimeMs", "memoryType", "status",
                "importance", "confidence", "sourceConversationId", "sourceMessageId",
                "embeddingJson", "embeddingModel", "embeddingDimension", "pinned"
            )))
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }
}
