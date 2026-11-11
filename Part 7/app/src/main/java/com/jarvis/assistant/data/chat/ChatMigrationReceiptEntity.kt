package com.jarvis.assistant.data.chat

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Additive storage for the staged importer. No receipt is synthesized by a schema upgrade.
 * Future batch/completion/deletion transactions own these fields; this stage adds no writer.
 * The receipt contains progress/retention metadata, never a transcript or encryption key.
 */
@Entity(tableName = "chat_migration_receipts")
data class ChatMigrationReceiptEntity(
    @PrimaryKey val key: String,
    val sourceSha256: String?,
    val payloadVersion: Int,
    val status: String,
    val committedCursor: Long,
    val uniqueMessageCount: Long,
    val importSessionId: String?,
    val doneAtMs: Long?,
    val backupVersionCode: Int?,
    val cleanupDueAtMs: Long?,
    val cleanupOfferedAtMs: Long?,
    val updatedAtMs: Long
)
