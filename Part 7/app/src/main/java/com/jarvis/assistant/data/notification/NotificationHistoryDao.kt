package com.jarvis.assistant.data.notification

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationHistoryDao {
    @Query("SELECT * FROM notification_history ORDER BY timestamp DESC LIMIT 200")
    fun observeRecent(): Flow<List<NotificationHistoryEntity>>

    @Query("SELECT * FROM notification_history WHERE isRead = 0 ORDER BY timestamp DESC LIMIT 200")
    fun observeUnread(): Flow<List<NotificationHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: NotificationHistoryEntity)

    @Query("UPDATE notification_history SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE notification_history SET isRead = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM notification_history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM notification_history")
    suspend fun clearAll()

    @Query("DELETE FROM notification_history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
