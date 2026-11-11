package com.jarvis.assistant.data.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ConversationRepository(
    private val chatDao: ChatDao,
    private val awaitReady: suspend () -> Unit = {}
) {
    private val _currentSessionId = MutableStateFlow<String?>(null)
    private val turnWriteMutex = Mutex()
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private suspend fun readyDao(): ChatDao { awaitReady(); return chatDao }
    private fun <T> readyFlow(source: () -> Flow<T>): Flow<T> = kotlinx.coroutines.flow.flow {
        awaitReady()
        emitAll(source())
    }

    fun getActiveSessionsFlow(): Flow<List<ChatSessionEntity>> = readyFlow { chatDao.getActiveSessionsFlow() }
    fun getArchivedSessionsFlow(): Flow<List<ChatSessionEntity>> = readyFlow { chatDao.getArchivedSessionsFlow() }

    suspend fun getSession(id: String): ChatSessionEntity? = readyDao().getSessionById(id)

    /** Restores the latest non-archived session after process recreation. */
    suspend fun restoreMostRecentActiveSession(): String? {
        val session = readyDao().getMostRecentActiveSession()
        _currentSessionId.value = session?.id
        return session?.id
    }

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
        readyDao().insertSession(session)
        _currentSessionId.value = session.id
        return session
    }

    /** Atomically archives the active session and creates a fresh conversation. */
    suspend fun startNewSession(title: String = "New Chat"): ChatSessionEntity = turnWriteMutex.withLock {
        val currentId = _currentSessionId.value
        val current = currentId?.let { readyDao().getSessionById(it) }?.takeIf { !it.isArchived }
            ?: readyDao().getMostRecentActiveSession()
        if (current != null && !current.isArchived) {
            readyDao().setArchived(current.id, true)
        }
        createNewSession(title)
    }

    suspend fun selectSession(sessionId: String) {
        val session = readyDao().getSessionById(sessionId)
        if (session != null) {
            _currentSessionId.value = sessionId
        }
    }

    suspend fun renameSession(sessionId: String, newTitle: String) {
        val cleanTitle = newTitle.trim().ifEmpty { "Chat" }
        readyDao().renameSession(sessionId, cleanTitle)
    }

    suspend fun archiveSession(sessionId: String, isArchived: Boolean) {
        readyDao().setArchived(sessionId, isArchived)
        if (isArchived && _currentSessionId.value == sessionId) {
            _currentSessionId.value = null
        }
    }

    suspend fun pinSession(sessionId: String, pinned: Boolean) {
        readyDao().setPinned(sessionId, pinned)
    }

    suspend fun deleteSession(sessionId: String) {
        readyDao().deleteMessagesForSession(sessionId)
        readyDao().deleteSession(sessionId)
        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = null
        }
    }

    suspend fun searchSessions(query: String): List<ChatSessionEntity> =
        readyDao().searchSessions(query.trim())

    fun getMessagesFlow(sessionId: String): Flow<List<ChatMessageEntity>> =
        readyFlow { chatDao.getMessagesForSessionFlow(sessionId) }

    fun getRecentMessagesFlow(): Flow<List<ChatMessageEntity>> =
        readyFlow { chatDao.getRecentMessagesFlow() }

    suspend fun clearAllConversationData(clearLegacyHistory: () -> Unit = {}) = turnWriteMutex.withLock {
        readyDao().clearAllConversationData()
        _currentSessionId.value = null
        clearLegacyHistory()
    }

    /**
     * Reads, imports, and clears the legacy history while holding the same mutex used by
     * new-session creation and full-history deletion. This prevents a concurrent clear
     * from being followed by a stale migration that resurrects deleted messages.
     */
    suspend fun migrateLegacyHistory(
        sessionId: String,
        loadLegacyTurns: () -> List<com.jarvis.assistant.data.model.ChatTurn>,
        clearLegacyHistory: () -> Unit
    ) = turnWriteMutex.withLock {
        val legacyTurns = loadLegacyTurns()
        if (legacyTurns.isNotEmpty()) {
            mergeHistoricalTurnsLocked(sessionId, legacyTurns)
            clearLegacyHistory()
        }
    }

    suspend fun getMessages(sessionId: String): List<ChatMessageEntity> =
        readyDao().getMessagesForSession(sessionId)

    suspend fun getMessagesPaged(sessionId: String, limit: Int, offset: Int): List<ChatMessageEntity> =
        readyDao().getMessagesPaged(sessionId, limit, offset)

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
        readyDao().insertMessage(message)
        readyDao().updateSessionTimestamp(sessionId, message.timestamp)

        // Automatically update title from first user message if title is still "New Chat"
        if (role == "USER") {
            val session = readyDao().getSessionById(sessionId)
            if (session != null && (session.title == "New Chat" || session.title.isBlank())) {
                val generatedTitle = generateTitleFromMessage(content)
                readyDao().renameSession(sessionId, generatedTitle, message.timestamp)
            }
        }

        return message
    }

    /**
     * Atomically persists a complete user/assistant turn in the durable Room conversation store.
     * A session is restored when possible and created lazily after process restart or deletion.
     */
    suspend fun appendTurn(userText: String, assistantText: String): String? = turnWriteMutex.withLock {
        val user = com.jarvis.assistant.data.memory.EpisodicVectorMemory.sanitizeForStorage(userText.trim())
        val assistant = com.jarvis.assistant.data.memory.EpisodicVectorMemory.sanitizeForStorage(assistantText.trim())
        if (user.isBlank() && assistant.isBlank()) return@withLock null

        val existingId = _currentSessionId.value
        val existing = existingId?.let { readyDao().getSessionById(it) }?.takeIf { !it.isArchived }
            ?: readyDao().getMostRecentActiveSession()?.also { _currentSessionId.value = it.id }
        val session = if (existing != null) {
            existing
        } else {
            createNewSession(title = user.lineSequence().firstOrNull()?.take(36)?.ifBlank { "Voice Conversation" } ?: "Voice Conversation")
        }

        if (user.isNotBlank()) addMessage(session.id, role = "USER", content = user)
        if (assistant.isNotBlank()) addMessage(session.id, role = "ASSISTANT", content = assistant)
        session.id
    }

    /**
     * Idempotently imports the previous preference-backed transcript into Room.
     * Stable IDs make retries safe if the process stops during migration.
     */
    suspend fun mergeHistoricalTurns(
        sessionId: String,
        turns: List<com.jarvis.assistant.data.model.ChatTurn>
    ) = turnWriteMutex.withLock {
        mergeHistoricalTurnsLocked(sessionId, turns)
    }

    private suspend fun mergeHistoricalTurnsLocked(
        sessionId: String,
        turns: List<com.jarvis.assistant.data.model.ChatTurn>
    ) {
        val session = readyDao().getSessionById(sessionId) ?: return
        val existing = readyDao().getMessagesForSession(sessionId)
        val existingContent = existing.map { it.role.uppercase() to it.content }.toSet()
        val imported = mutableListOf<ChatMessageEntity>()
        turns.forEach { turn ->
            val user = turn.userTranscript.trim()
            val assistant = turn.jarvisResponse.trim()
            if (user.isNotBlank() && ("USER" to user) !in existingContent) {
                imported.add(
                    ChatMessageEntity(
                        id = UUID.nameUUIDFromBytes(("old:" + turn.id + ":user").toByteArray()).toString(),
                        sessionId = sessionId,
                        role = "USER",
                        content = user,
                        timestamp = turn.timestamp
                    )
                )
            }
            if (assistant.isNotBlank() && ("ASSISTANT" to assistant) !in existingContent) {
                imported.add(
                    ChatMessageEntity(
                        id = UUID.nameUUIDFromBytes(("old:" + turn.id + ":assistant").toByteArray()).toString(),
                        sessionId = sessionId,
                        role = "ASSISTANT",
                        content = assistant,
                        timestamp = turn.timestamp + 1L
                    )
                )
            }
        }
        if (imported.isNotEmpty()) {
            readyDao().insertMessages(imported)
            val latest = imported.maxOf { it.timestamp }
            readyDao().updateSessionTimestamp(sessionId, maxOf(session.updatedAt, latest))
        }
    }

    suspend fun deleteMessage(messageId: String) {
        readyDao().deleteMessage(messageId)
    }

    suspend fun searchMessages(query: String): List<ChatMessageEntity> =
        readyDao().searchMessages(query.trim())

    private fun generateTitleFromMessage(userMessage: String): String {
        val trimmed = userMessage.trim().lines().firstOrNull()?.trim() ?: "New Chat"
        return if (trimmed.length > 36) {
            trimmed.substring(0, 33).trim() + "..."
        } else {
            trimmed.ifEmpty { "New Chat" }
        }
    }
}
