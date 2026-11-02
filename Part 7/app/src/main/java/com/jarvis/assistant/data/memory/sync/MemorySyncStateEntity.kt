package com.jarvis.assistant.data.memory.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable remote pull cursor; persisted after each successfully merged page. */
@Entity(tableName = "memory_sync_state")
data class MemorySyncStateEntity(
    @PrimaryKey val syncKey: String = "jarvis-os-personal",
    val cursorUpdatedAt: String = "",
    val cursorId: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
