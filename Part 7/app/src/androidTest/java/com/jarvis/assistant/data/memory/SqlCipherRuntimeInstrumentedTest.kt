package com.jarvis.assistant.data.memory

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class SqlCipherRuntimeInstrumentedTest {
    @Test
    fun encryptedDatabaseReopensWithCorrectKeyAndRejectsWrongKey() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File(context.cacheDir, "sqlcipher-runtime-${System.nanoTime()}.db")
        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipherKey = Base64.encode(rawKey, Base64.NO_WRAP)
        val wrongKey = Base64.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)

        try {
            val created = CipherDatabase.openOrCreateDatabase(
                file, cipherKey, null, null, null
            )
            try {
                created.execSQL("CREATE TABLE secret_payload (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                created.execSQL("INSERT INTO secret_payload(value) VALUES ('encrypted-round-trip')")
                created.rawQuery("PRAGMA cipher_status", emptyArray()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                created.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
            } finally {
                created.close()
            }

            val reopened = CipherDatabase.openDatabase(
                file.absolutePath, cipherKey, null, CipherDatabase.OPEN_READWRITE, null
            )
            try {
                reopened.rawQuery("SELECT value FROM secret_payload WHERE id = 1", emptyArray()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("encrypted-round-trip", cursor.getString(0))
                }
            } finally {
                reopened.close()
            }

            val preservedBytes = file.readBytes()
            val wrongKeyRejected = runCatching {
                val wrong = CipherDatabase.openDatabase(
                    file.absolutePath, wrongKey, null, CipherDatabase.OPEN_READWRITE,
                    net.zetetic.database.DatabaseErrorHandler { _, exception -> throw exception }, null
                )
                try {
                    wrong.rawQuery("SELECT value FROM secret_payload", emptyArray()).use { cursor ->
                        cursor.moveToFirst()
                    }
                } finally {
                    wrong.close()
                }
            }.isFailure
            assertTrue("Wrong-key open must not expose the encrypted payload", wrongKeyRejected)
            assertTrue("Wrong-key rejection must preserve the database", file.isFile)
            assertArrayEquals(preservedBytes, file.readBytes())
        } finally {
            rawKey.fill(0)
            cipherKey.fill(0)
            wrongKey.fill(0)
            listOf("", "-wal", "-shm").forEach { suffix -> File(file.absolutePath + suffix).delete() }
        }
    }
}
