package com.jarvis.assistant.data.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ConversationRepository(
    private val chatDao: ChatDao
) {
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    fun getActiveSessionsFlow(): Flow<List<ChatSessionEntity>> = chatDao.getActiveSessionsFlow()
    fun getArchivedSessionsFlow(): Flow<List<ChatSessionEntity>> = chatDao.getArchivedSessionsFlow()

    suspend fun getSession(id: String): ChatSessionEntity? = chatDao.getSessionById(id)

    suspend fun createNewSession(title: String = "New Chat", systemPrompt: String? = null): ChatSessionEntity {
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isArchived = false,
            pinned = false,
            systemPrompt = systemPrompt
        )
        chatDao.insertSession(session)
        _currentSessionId.value = session.id
        return session
    }

    suspend fun selectSession(sessionId: String) {
        val session = chatDao.getSessionById(sessionId)
        if (session != null) {
            _currentSessionId.value = sessionId
        }
    }

    suspend fun renameSession(sessionId: String, newTitle: String) {
        val cleanTitle = newTitle.trim().ifEmpty { "Chat" }
        chatDao.renameSession(sessionId, cleanTitle)
    }

    suspend fun archiveSession(sessionId: String, isArchived: Boolean) {
        chatDao.setArchived(sessionId, isArchived)
        if (isArchived && _currentSessionId.value == sessionId) {
            _currentSessionId.value = null
        }
    }

    suspend fun pinSession(sessionId: String, pinned: Boolean) {
        chatDao.setPinned(sessionId, pinned)
    }

    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteMessagesForSession(sessionId)
        chatDao.deleteSession(sessionId)
        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = null
        }
    }

    suspend fun searchSessions(query: String): List<ChatSessionEntity> =
        chatDao.searchSessions(query.trim())

    fun getMessagesFlow(sessionId: String): Flow<List<ChatMessageEntity>> =
        chatDao.getMessagesForSessionFlow(sessionId)

    suspend fun getMessages(sessionId: String): List<ChatMessageEntity> =
        chatDao.getMessagesForSession(sessionId)

    suspend fun getMessagesPaged(sessionId: String, limit: Int, offset: Int): List<ChatMessageEntity> =
        chatDao.getMessagesPaged(sessionId, limit, offset)

    suspend fun addMessage(
        sessionId: String,
        role: String,
        content: String,
        status: String = "SENT",
        metadata: String = "{}"
    ): ChatMessageEntity {
        val message = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            content = content.trim(),
            timestamp = System.currentTimeMillis(),
            status = status,
            metadata = metadata
        )
        chatDao.insertMessage(message)
        chatDao.updateSessionTimestamp(sessionId, message.timestamp)

        // Automatically update title from first user message if title is still "New Chat"
        if (role == "USER") {
            val session = chatDao.getSessionById(sessionId)
            if (session != null && (session.title == "New Chat" || session.title.isBlank())) {
                val generatedTitle = generateTitleFromMessage(content)
                chatDao.renameSession(sessionId, generatedTitle, message.timestamp)
            }
        }

        return message
    }

    suspend fun deleteMessage(messageId: String) {
        chatDao.deleteMessage(messageId)
    }

    suspend fun searchMessages(query: String): List<ChatMessageEntity> =
        chatDao.searchMessages(query.trim())

    private fun generateTitleFromMessage(userMessage: String): String {
        val trimmed = userMessage.trim().lines().firstOrNull()?.trim() ?: "New Chat"
        return if (trimmed.length > 36) {
            trimmed.substring(0, 33).trim() + "..."
        } else {
            trimmed.ifEmpty { "New Chat" }
        }
    }
}
