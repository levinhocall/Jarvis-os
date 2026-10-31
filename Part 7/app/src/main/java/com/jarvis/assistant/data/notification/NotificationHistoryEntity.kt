package com.jarvis.assistant.data.notification

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persisted notification content is stored only after the user opts in. Reply intents are never persisted. */
@Entity(
    tableName = "notification_history",
    indices = [Index(value = ["timestamp"]), Index(value = ["packageName"])]
)
data class NotificationHistoryEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val sender: String,
    val message: String,
    val timestamp: Long,
    val isRead: Boolean = false
)
