package com.jarvis.assistant.data.memory

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.util.Base64
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Prepares the Room database before Room opens it. Existing plaintext databases are exported
 * into a sibling encrypted staging file, verified, then atomically swapped while retaining a
 * recoverable plaintext backup. No destructive Room fallback is permitted.
 */
internal object DatabaseEncryption {
    private const val TAG = "DatabaseEncryption"
    private const val DATABASE_NAME = "jarvis_memory.db"
    private const val STAGING_NAME = "jarvis_memory.db.encrypting"
    private const val BACKUP_NAME = "jarvis_memory.db.plaintext-backup"

    @Synchronized
    fun prepare(context: Context): SupportOpenHelperFactory {
        System.loadLibrary("sqlcipher")
        val app = context.applicationContext
        val database = app.getDatabasePath(DATABASE_NAME)
        val staging = File(database.parentFile, STAGING_NAME)
        val backup = File(database.parentFile, BACKUP_NAME)
        val keyStore = DatabaseKeyStore(app)

        // Recover either side of an interrupted two-rename swap before deciding whether
        // migration is needed. If the encrypted staging file verifies, finish the swap;
        // otherwise restore the untouched plaintext backup. Never discard both copies.
        if (!database.exists() && backup.exists()) {
            val canPromoteStaging = staging.isFile &&
                keyStore.hasWrappedPassphrase() &&
                runCatching {
                    val recoveryKey = keyStore.loadExistingPassphrase()
                    try { isValidEncryptedDatabase(staging, recoveryKey) }
                    finally { recoveryKey.fill(0) }
                }.getOrDefault(false)

            if (canPromoteStaging) {
                check(staging.renameTo(database)) {
                    "Could not finish the encrypted database swap; the plaintext backup remains recoverable."
                }
            } else {
                check(backup.renameTo(database)) {
                    "Could not restore the preserved database after an interrupted encryption swap."
                }
                // Only remove the incomplete staging copy after the original has been restored.
                listOf("", "-wal", "-shm").forEach { suffix ->
                    val incomplete = File(staging.absolutePath + suffix)
                    if (incomplete.exists()) check(incomplete.delete()) {
                        "Could not remove an incomplete staging file after restoring the original database."
                    }
                }
            }
        }

        val passphrase = when {
            !database.exists() && keyStore.hasWrappedPassphrase() && keyStore.hasPendingInitialization() ->
                keyStore.loadExistingPassphrase()
            !database.exists() && keyStore.hasWrappedPassphrase() ->
                error("Database file is missing while a completed database key exists; refusing silent data loss.")
            !database.exists() -> keyStore.createPassphraseForNewDatabase()
            keyStore.hasWrappedPassphrase() -> keyStore.loadExistingPassphrase()
            else -> {
                check(isValidPlaintextDatabase(database)) {
                    "Database exists but its encryption key is missing. Refusing to replace or recreate it."
                }
                keyStore.createPassphraseForNewDatabase()
            }
        }

        try {
            if (database.exists()) {
                if (isValidEncryptedDatabase(database, passphrase)) {
                    deleteVerifiedPlaintextBackup(backup)
                    return roomFactory(passphrase)
                }
                check(isValidPlaintextDatabase(database)) {
                    "Existing database is neither readable plaintext SQLite nor readable with its stored SQLCipher key."
                }
                migratePlaintextDatabase(database, staging, backup, passphrase)
                check(isValidEncryptedDatabase(database, passphrase)) {
                    "Encrypted database failed post-swap verification; the plaintext backup is retained."
                }
                deleteVerifiedPlaintextBackup(backup)
            }
            return roomFactory(passphrase)
        } catch (error: Exception) {
            passphrase.fill(0)
            throw IllegalStateException(
                "Secure database initialization failed. Existing database files and recovery backups were preserved.",
                error
            )
        }
    }

    private fun deleteVerifiedPlaintextBackup(backup: File) {
        if (!backup.exists()) return
        listOf("", "-wal", "-shm").forEach { suffix ->
            val file = File(backup.absolutePath + suffix)
            if (file.exists()) check(file.delete()) { "Plaintext backup could not be removed after encrypted verification." }
        }
    }

    private fun roomFactory(passphrase: ByteArray): SupportOpenHelperFactory {
        val cipherPassphrase = rawPassphraseForCipher(passphrase)
        passphrase.fill(0)
        return SupportOpenHelperFactory(cipherPassphrase, null, true)
    }

    private fun isValidPlaintextDatabase(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return try {
            val db = FrameworkSQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                FrameworkSQLiteDatabase.OPEN_READONLY
            )
            try {
                db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == "ok"
                }
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidEncryptedDatabase(file: File, passphrase: ByteArray): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return try {
            val db = CipherDatabase.openDatabase(
                file.absolutePath,
                rawPassphraseForCipher(passphrase),
                null,
                CipherDatabase.OPEN_READWRITE,
                null
            )
            try {
                db.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == "ok"
                }
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun migratePlaintextDatabase(
        original: File,
        staging: File,
        backup: File,
        rawPassphrase: ByteArray
    ) {
        // Checkpoint and close the framework SQLite handle before exporting the file.
        val source = FrameworkSQLiteDatabase.openDatabase(
            original.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READWRITE
        )
        val oldVersion: Int
        try {
            source.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                    "SQLite WAL checkpoint was busy; encryption migration was not started."
                }
            }
            source.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Plaintext database integrity check failed; migration was not started."
                }
            }
            oldVersion = source.version
        } finally {
            source.close()
        }
        // WAL checkpoint completed and the source handle is closed; do not let plaintext sidecars
        // attach to the encrypted replacement at the original database path.
        listOf("-wal", "-shm").forEach { suffix ->
            val sidecar = File(original.absolutePath + suffix)
            if (sidecar.exists()) check(sidecar.delete()) { "Could not clear a closed SQLite sidecar." }
        }

        // This is an app-owned staging filename. The original remains untouched throughout export.
        listOf("", "-wal", "-shm").forEach { suffix ->
            val stale = File(staging.absolutePath + suffix)
            if (stale.exists()) check(stale.delete()) { "Could not clear an incomplete encryption staging file." }
        }
        val passphrase = Base64.encodeToString(rawPassphrase, Base64.NO_WRAP)
        val cipherSource = CipherDatabase.openDatabase(
            original.absolutePath,
            ByteArray(0),
            null,
            CipherDatabase.OPEN_READWRITE,
            null
        )
        try {
            cipherSource.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf(staging.absolutePath, passphrase)
            )
            // sqlcipher_export is a SELECT function. Consume its result cursor so SQLite
            // actually steps the export statement before the attached database is detached.
            cipherSource.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use { cursor ->
                check(cursor.moveToFirst()) { "SQLCipher export did not return a completion result." }
            }
            cipherSource.execSQL("DETACH DATABASE encrypted")
        } finally {
            cipherSource.close()
        }

        check(staging.isFile && staging.length() > 0L) { "SQLCipher export did not create a staging database." }
        val encrypted = CipherDatabase.openDatabase(
            staging.absolutePath,
            rawPassphraseForCipher(rawPassphrase),
            null,
            CipherDatabase.OPEN_READWRITE,
            null
        )
        try {
            encrypted.version = oldVersion
            verifyIntegrityAndRows(original, encrypted)
        } finally {
            encrypted.close()
        }

        // The original is renamed (same directory/filesystem) only after the encrypted copy verifies.
        check(!backup.exists()) {
            "A previous plaintext recovery backup already exists. It is retained; manual reconciliation is required before another swap."
        }
        check(original.renameTo(backup)) { "Could not preserve the original database before the atomic swap." }
        if (!staging.renameTo(original)) {
            val restored = backup.renameTo(original)
            check(restored) {
                "Encrypted swap failed and automatic restoration failed. The original remains at the plaintext backup path."
            }
            error("Could not commit the encrypted staging database; original database was restored.")
        }
        Log.i(TAG, "Database encryption migration committed; plaintext recovery backup retained.")
    }

    private fun rawPassphraseForCipher(raw: ByteArray): ByteArray =
        Base64.encode(raw, Base64.NO_WRAP)

    private fun verifyIntegrityAndRows(original: File, encrypted: CipherDatabase) {
        val plain = FrameworkSQLiteDatabase.openDatabase(
            original.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READONLY
        )
        try {
            plain.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null
            ).use { sourceTables ->
                while (sourceTables.moveToNext()) {
                    val table = sourceTables.getString(0)
                    val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                    val sourceCount = plain.rawQuery("SELECT COUNT(*) FROM $quoted", null).use {
                        check(it.moveToFirst()); it.getLong(0)
                    }
                    val targetCount = encrypted.rawQuery("SELECT COUNT(*) FROM $quoted", emptyArray()).use {
                        check(it.moveToFirst()); it.getLong(0)
                    }
                    check(sourceCount == targetCount) {
                        "Database migration row-count verification failed for table $table."
                    }
                    val sourceDigest = plain.rawQuery("SELECT * FROM $quoted ORDER BY rowid", null).use(::digestCursor)
                    val encryptedDigest = encrypted.rawQuery("SELECT * FROM $quoted ORDER BY rowid", emptyArray()).use(::digestCursor)
                    check(MessageDigest.isEqual(sourceDigest, encryptedDigest)) {
                        "Database migration content verification failed for table $table."
                    }
                }
            }
            encrypted.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Encrypted database integrity verification failed."
                }
            }
        } finally {
            plain.close()
        }
    }
    /** Streams typed row values into SHA-256 without buffering the full table in memory. */
    private fun digestCursor(cursor: Cursor): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        cursor.columnNames.forEach { name ->
            val bytes = name.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        var rows = 0L
        while (cursor.moveToNext()) {
            for (column in 0 until cursor.columnCount) {
                val type = cursor.getType(column)
                digest.update(type.toByte())
                val value = when (type) {
                    Cursor.FIELD_TYPE_NULL -> ByteArray(0)
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column).toString().toByteArray(Charsets.UTF_8)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toString().toByteArray(Charsets.UTF_8)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column) ?: ByteArray(0)
                    else -> (cursor.getString(column) ?: "").toByteArray(Charsets.UTF_8)
                }
                digest.update(ByteBuffer.allocate(4).putInt(value.size).array())
                digest.update(value)
            }
            digest.update(0xFF.toByte())
            rows++
        }
        digest.update(ByteBuffer.allocate(8).putLong(rows).array())
        return digest.digest()
    }
}
