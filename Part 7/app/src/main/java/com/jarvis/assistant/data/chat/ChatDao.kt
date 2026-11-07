package com.jarvis.assistant.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, updatedAt DESC")
    fun getAllSessionsFlow(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE isArchived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun getActiveSessionsFlow(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun getArchivedSessionsFlow(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE isArchived = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecentActiveSession(): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE title LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchSessions(query: String): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET title = :newTitle, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun renameSession(sessionId: String, newTitle: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET isArchived = :isArchived, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun setArchived(sessionId: String, isArchived: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET pinned = :pinned WHERE id = :sessionId")
    suspend fun setPinned(sessionId: String, pinned: Boolean)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionTimestamp(sessionId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    // --- Messages ---

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSessionFlow(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT 100")
    fun getRecentMessagesFlow(): Flow<List<ChatMessageEntity>>

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()

    @Transaction
    suspend fun clearAllConversationData() {
        deleteAllMessages()
        deleteAllSessions()
    }

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPaged(sessionId: String, limit: Int, offset: Int): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun getMessageCount(sessionId: String): Int

    /** Persist a complete voice turn, session timestamp and first-message title together. */
    @Transaction
    suspend fun insertTurnAtomically(
        sessionId: String,
        messages: List<ChatMessageEntity>,
        firstUserText: String?
    ) {
        if (messages.isEmpty()) return
        insertMessages(messages)
        val timestamp = messages.maxOf { it.timestamp }
        updateSessionTimestamp(sessionId, timestamp)
        if (firstUserText != null) {
            val session = getSessionById(sessionId)
            if (session != null && (session.title == "New Chat" || session.title.isBlank())) {
                val firstLine = firstUserText.trim().lineSequence().firstOrNull()?.trim().orEmpty()
                val title = if (firstLine.length > 36) firstLine.take(33).trim() + "..."
                    else firstLine.ifEmpty { "New Chat" }
                renameSession(sessionId, title, timestamp)
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("SELECT * FROM chat_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMessages(query: String): List<ChatMessageEntity>
}
