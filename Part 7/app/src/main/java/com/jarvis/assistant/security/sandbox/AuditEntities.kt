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

@Database(entities = [AuditEntity::class], version = 1, exportSchema = false)
abstract class AuditDatabase : RoomDatabase() {
    abstract fun auditDao(): AuditDao

    companion object {
        @Volatile
        private var instance: AuditDatabase? = null

        fun getInstance(context: Context): AuditDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuditDatabase::class.java,
                    "jarvis_audit.db"
                ).build().also { instance = it }
            }
        }
    }
}
