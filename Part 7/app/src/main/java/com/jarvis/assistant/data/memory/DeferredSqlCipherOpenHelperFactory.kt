package com.jarvis.assistant.data.memory

import android.os.Looper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/** Defers native/key/file work. Main-thread Room probes can only reuse an open handle. */
internal class DeferredSqlCipherOpenHelperFactory(
    private val createDelegate: (SupportSQLiteOpenHelper.Configuration) -> SupportSQLiteOpenHelper
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        object : SupportSQLiteOpenHelper {
            @Volatile private var delegate: SupportSQLiteOpenHelper? = null
            @Volatile private var opened: SupportSQLiteDatabase? = null
            @Volatile private var wal = false

            private fun database(readOnly: Boolean): SupportSQLiteDatabase {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    val cached = opened
                    check(cached != null && cached.isOpen) {
                        "JARVIS database must be opened from a background dispatcher."
                    }
                    return cached
                }
                return synchronized(this) {
                    val helper = delegate ?: createDelegate(configuration).also {
                        it.setWriteAheadLoggingEnabled(wal)
                        delegate = it
                    }
                    (if (readOnly) helper.readableDatabase else helper.writableDatabase).also { opened = it }
                }
            }

            override val databaseName get() = configuration.name
            override val writableDatabase get() = database(false)
            override val readableDatabase get() = database(true)
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                synchronized(this) { wal = enabled; delegate?.setWriteAheadLoggingEnabled(enabled) }
            }
            override fun close() {
                synchronized(this) {
                    opened = null
                    delegate?.close()
                    delegate = null
                }
            }
        }
}
