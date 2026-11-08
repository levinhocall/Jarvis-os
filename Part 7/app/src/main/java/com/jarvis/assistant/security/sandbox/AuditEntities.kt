package com.jarvis.assistant.security.sandbox

import android.content.Context
import androidx.room.*

@Entity(tableName = "audit_ledger")
data class AuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val actionName: String,
    val invoker: String,
    val threatDetected: String?,
    val isAllowed: Boolean,
    val details: String,
    val prevSignatureHash: String,
    val currentSignatureHash: String
)

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditEntity): Long

    @Query("SELECT * FROM audit_ledger ORDER BY id ASC")
    suspend fun getAllEntries(): List<AuditEntity>

    @Query("SELECT * FROM audit_ledger ORDER BY id DESC LIMIT 1")
    suspend fun getLatestEntry(): AuditEntity?

    @Query("SELECT COUNT(*) FROM audit_ledger")
    suspend fun getCount(): Int

    @Query("DELETE FROM audit_ledger WHERE id IN (SELECT id FROM audit_ledger ORDER BY id ASC LIMIT :count)")
    suspend fun pruneOldest(count: Int): Int
}

// exportSchema stays false: version 1 was never created on any device, so there is no historical
// schema to export; the encrypted successor will export its own schema from its first version.
@Database(entities = [AuditEntity::class], version = 1, exportSchema = false)
abstract class AuditDatabase : RoomDatabase() {
    abstract fun auditDao(): AuditDao

    companion object {
        /**
         * Phase 1.2 F3/F5: audit rows contain action names, invokers and free-form details, so they must
         * never be written to a plaintext SQLite file. No production code opens this database today
         * (CapabilitySandbox is always built with `auditDao = null`, so `jarvis_audit.db` is never
         * created and there are no legacy rows to migrate). The former plaintext builder is therefore
         * disabled and fails closed until an encrypted store with an explicit, tested migration exists
         * (planned for Phase 1.7, reusing DatabaseEncryption/DatabaseKeyStore or MemoryDatabase).
         */
        fun getInstance(@Suppress("UNUSED_PARAMETER") context: Context): AuditDatabase =
            error(
                "Persistent audit storage is disabled until it is encrypted. " +
                    "Do not open jarvis_audit.db as plaintext."
            )
    }
}
