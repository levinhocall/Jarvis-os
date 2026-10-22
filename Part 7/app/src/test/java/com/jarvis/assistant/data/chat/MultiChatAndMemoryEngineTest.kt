package com.jarvis.assistant.data.chat

import com.jarvis.assistant.data.memory.MemoryDao
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.data.memory.MemoryAwareContextBuilder
import com.jarvis.assistant.data.memory.vector.DeterministicLocalEmbeddingProvider
import com.jarvis.assistant.data.memory.vector.MatchType
import com.jarvis.assistant.data.memory.vector.VectorSearchEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MultiChatAndMemoryEngineTest {

    private lateinit var fakeChatDao: FakeChatDao
    private lateinit var fakeMemoryDao: FakeMemoryDao
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var memoryRepo: MemoryRepository
    private lateinit var embeddingProvider: DeterministicLocalEmbeddingProvider
    private lateinit var vectorSearchEngine: VectorSearchEngine
    private lateinit var contextBuilder: MemoryAwareContextBuilder

    @Before
    fun setUp() {
        fakeChatDao = FakeChatDao()
        fakeMemoryDao = FakeMemoryDao()
        conversationRepo = ConversationRepository(fakeChatDao)
        embeddingProvider = DeterministicLocalEmbeddingProvider(128)
        vectorSearchEngine = VectorSearchEngine()
        memoryRepo = MemoryRepository(fakeMemoryDao, embeddingProvider, vectorSearchEngine)
        contextBuilder = MemoryAwareContextBuilder(memoryRepo, conversationRepo)
    }

    // =========================================================================
    // PHASE A TESTS: MULTI-CHAT SESSIONS & MESSAGE ISOLATION
    // =========================================================================

    @Test
    fun `test creating multiple independent chat sessions`() = runBlocking {
        val session1 = conversationRepo.createNewSession("Researching Jetpack Compose")
        val session2 = conversationRepo.createNewSession("Android Architecture Review")

        assertNotNull(session1.id)
        assertNotNull(session2.id)
        assertTrue(session1.id != session2.id)
        assertEquals("Researching Jetpack Compose", session1.title)
        assertEquals("Android Architecture Review", session2.title)

        val retrieved1 = conversationRepo.getSession(session1.id)
        val retrieved2 = conversationRepo.getSession(session2.id)
        assertEquals("Researching Jetpack Compose", retrieved1?.title)
        assertEquals("Android Architecture Review", retrieved2?.title)
    }

    @Test
    fun `test message isolation between sessions`() = runBlocking {
        val sessionA = conversationRepo.createNewSession("Chat A")
        val sessionB = conversationRepo.createNewSession("Chat B")

        // Add 3 messages to Session A
        conversationRepo.addMessage(sessionA.id, "USER", "Hello in Chat A")
        conversationRepo.addMessage(sessionA.id, "ASSISTANT", "Response in Chat A")
        conversationRepo.addMessage(sessionA.id, "USER", "Follow up in Chat A")

        // Add 2 messages to Session B
        conversationRepo.addMessage(sessionB.id, "USER", "Hello in Chat B")
        conversationRepo.addMessage(sessionB.id, "ASSISTANT", "Response in Chat B")

        val messagesA = conversationRepo.getMessages(sessionA.id)
        val messagesB = conversationRepo.getMessages(sessionB.id)

        assertEquals(3, messagesA.size)
        assertEquals(2, messagesB.size)

        // Strict verification: Chat A messages must never leak into Chat B
        assertTrue(messagesA.all { it.sessionId == sessionA.id })
        assertTrue(messagesB.all { it.sessionId == sessionB.id })
        assertEquals("Hello in Chat A", messagesA[0].content)
        assertEquals("Hello in Chat B", messagesB[0].content)
    }

    @Test
    fun `test session renaming, archiving, and deletion cascade`() = runBlocking {
        val session = conversationRepo.createNewSession("Temporary Chat")
        conversationRepo.addMessage(session.id, "USER", "Message 1")
        conversationRepo.addMessage(session.id, "ASSISTANT", "Response 1")

        // Rename
        conversationRepo.renameSession(session.id, "Renamed Project Chat")
        assertEquals("Renamed Project Chat", conversationRepo.getSession(session.id)?.title)

        // Archive
        conversationRepo.archiveSession(session.id, true)
        assertTrue(conversationRepo.getSession(session.id)?.isArchived == true)

        // Delete cascade: deleting session removes session AND its messages
        conversationRepo.deleteSession(session.id)
        assertNull(conversationRepo.getSession(session.id))
        assertTrue(conversationRepo.getMessages(session.id).isEmpty())
    }

    @Test
    fun `test message pagination for long histories`() = runBlocking {
        val session = conversationRepo.createNewSession("Long Chat")
        for (i in 1..25) {
            conversationRepo.addMessage(session.id, if (i % 2 != 0) "USER" else "ASSISTANT", "Message #$i")
        }

        // Paged fetch: limit 10, offset 0 (most recent 10 messages)
        val page1 = conversationRepo.getMessagesPaged(session.id, limit = 10, offset = 0)
        assertEquals(10, page1.size)
        assertEquals("Message #25", page1[0].content) // Ordered by timestamp desc

        // Paged fetch: limit 10, offset 10 (next 10 messages)
        val page2 = conversationRepo.getMessagesPaged(session.id, limit = 10, offset = 10)
        assertEquals(10, page2.size)
        assertEquals("Message #15", page2[0].content)
    }

    // =========================================================================
    // PHASE B TESTS: LONG-TERM PERSONAL MEMORY & CONTRADICTION HANDLING
    // =========================================================================

    @Test
    fun `test explicit remember command and secret scrubbing`() = runBlocking {
        // Normal personal fact
        val id = memoryRepo.remember(
            content = "User prefers dark mode and high contrast",
            type = MemoryEntity.TYPE_PREFERENCE,
            key = "theme_preference",
            importance = 0.9f
        )
        assertTrue(id > 0)

        val memory = memoryRepo.getAllMemories().find { it.id == id }
        assertNotNull(memory)
        assertEquals("User prefers dark mode and high contrast", memory?.value)
        assertEquals(MemoryEntity.TYPE_PREFERENCE, memory?.memoryType)
        assertEquals(0.9f, memory?.importance ?: 0f, 0.01f)

        // Security check: Storing credentials must be sanitized
        val secretId = memoryRepo.remember(
            content = "My api key is AIzaSyD1234567890123456789012345678901",
            type = MemoryEntity.TYPE_PERSONAL_FACT,
            key = "api_key"
        )
        val secretMemory = memoryRepo.getAllMemories().find { it.id == secretId }
        assertTrue(secretMemory?.value?.contains("[PROTECTED_CREDENTIAL]") == true)
        assertFalse(secretMemory?.value?.contains("AIzaSyD") == true)
    }

    @Test
    fun `test contradiction detection supersedes older conflicting memories`() = runBlocking {
        // Initial preference
        val id1 = memoryRepo.remember(
            content = "User prefers light theme",
            type = MemoryEntity.TYPE_PREFERENCE,
            key = "fav_theme"
        )

        // Updated preference arrives for the same key
        val id2 = memoryRepo.remember(
            content = "User switched preference to dark theme",
            type = MemoryEntity.TYPE_PREFERENCE,
            key = "fav_theme"
        )

        val oldMem = fakeMemoryDao.allRawMemories.find { it.id == id1 }
        val newMem = fakeMemoryDao.allRawMemories.find { it.id == id2 }

        assertEquals(MemoryEntity.STATUS_SUPERSEDED, oldMem?.status)
        assertEquals(MemoryEntity.STATUS_ACTIVE, newMem?.status)
        assertEquals("User switched preference to dark theme", newMem?.value)
    }

    @Test
    fun `test conservative automatic fact extraction from conversation`() = runBlocking {
        val extracted1 = memoryRepo.autoExtractFacts("I prefer dark mode in all apps")
        assertEquals(1, extracted1.size)

        val extracted2 = memoryRepo.autoExtractFacts("I am working on project Falcon")
        assertEquals(1, extracted2.size)

        val extracted3 = memoryRepo.autoExtractFacts("We decided to migrate database to Room v3")
        assertEquals(1, extracted3.size)

        // Should NOT extract sensitive passwords
        val sensitiveExtract = memoryRepo.autoExtractFacts("My password is supersecret123")
        assertTrue(sensitiveExtract.isEmpty())

        val allActive = memoryRepo.getAllMemories()
        assertTrue(allActive.any { it.memoryType == MemoryEntity.TYPE_PREFERENCE && it.value.contains("dark mode") })
        assertTrue(allActive.any { it.memoryType == MemoryEntity.TYPE_PROJECT && it.value.contains("Falcon") })
        assertTrue(allActive.any { it.memoryType == MemoryEntity.TYPE_DECISION && it.value.contains("Room v3") })
    }

    // =========================================================================
    // PHASE C TESTS: REAL SEMANTIC EMBEDDINGS & VECTOR SEARCH
    // =========================================================================

    @Test
    fun `test deterministic embedding generation and cosine similarity`() = runBlocking {
        val v1 = embeddingProvider.generateEmbedding("User prefers dark theme")
        val v2 = embeddingProvider.generateEmbedding("User likes night mode UI")
        val v3 = embeddingProvider.generateEmbedding("Voltas deep freezer 500 liter cooling")

        assertNotNull(v1)
        assertNotNull(v2)
        assertNotNull(v3)
        assertEquals(128, v1!!.size)
        assertEquals(128, v2!!.size)
        assertEquals(128, v3!!.size)

        // Semantic similarity: "dark theme" and "night mode" must be higher than unrelated "freezer"
        val simRelated = vectorSearchEngine.cosineSimilarity(v1, v2)
        val simUnrelated = vectorSearchEngine.cosineSimilarity(v1, v3)

        assertTrue("Related phrases must have positive similarity: $simRelated", simRelated > 0.40)
        assertTrue("Unrelated phrases must have lower similarity: $simUnrelated vs $simRelated", simRelated > simUnrelated)
    }

    @Test
    fun `test semantic vector search retrieves relevant memories`() = runBlocking {
        memoryRepo.remember(
            content = "User primary phone is Vivo 1920",
            type = MemoryEntity.TYPE_PERSONAL_FACT,
            key = "user_device",
            importance = 0.8f
        )
        memoryRepo.remember(
            content = "User prefers Python for data analysis",
            type = MemoryEntity.TYPE_PREFERENCE,
            key = "coding_preference",
            importance = 0.9f
        )

        val results = memoryRepo.searchMemoriesSemantic("Which smartphone am I using?", limit = 5)
        assertTrue("Must find matching device memory", results.isNotEmpty())
        assertEquals("user_device", results[0].memory.key)
        assertTrue(results[0].matchType == MatchType.SEMANTIC_VECTOR || results[0].matchType == MatchType.HYBRID)
    }

    // =========================================================================
    // PHASE D TESTS: MEMORY-AWARE AI CONTEXT BUILDING
    // =========================================================================

    @Test
    fun `test memory aware prompt context builder separates facts from history`() = runBlocking {
        val session = conversationRepo.createNewSession("Dev Session")
        conversationRepo.addMessage(session.id, "USER", "Let's work on our app")
        conversationRepo.addMessage(session.id, "ASSISTANT", "Ready, what are we building?")

        memoryRepo.remember(
            content = "Project deadline is November 1st",
            type = MemoryEntity.TYPE_PROJECT,
            key = "project_deadline",
            importance = 1.0f
        )

        val promptContext = contextBuilder.buildPromptContext(session.id, "When is our project deadline?")

        assertTrue(promptContext.systemContext.contains("[USER LONG-TERM MEMORY]"))
        assertTrue(promptContext.systemContext.contains("Project deadline is November 1st"))
        assertEquals(2, promptContext.conversationHistory.size)
        assertTrue(promptContext.fullAugmentedQuery.contains("User query: When is our project deadline?"))
    }

    @Test
    fun `test deleted memories are excluded from semantic search and context`() = runBlocking {
        val memId = memoryRepo.remember(
            content = "Secret project code is Chimera",
            type = MemoryEntity.TYPE_PROJECT,
            key = "project_code"
        )

        // Soft delete memory
        memoryRepo.forget(memId, permanent = false)

        val searchResults = memoryRepo.searchMemoriesSemantic("Chimera project code", limit = 5)
        assertTrue("Soft-deleted memory must be excluded from search", searchResults.isEmpty())
    }
}

// =============================================================================
// IN-MEMORY FAKE DAOS FOR TEST VERIFICATION
// =============================================================================

class FakeChatDao : ChatDao {
    private val sessions = ConcurrentHashMap<String, ChatSessionEntity>()
    private val messages = mutableListOf<ChatMessageEntity>()

    override fun getAllSessionsFlow(): Flow<List<ChatSessionEntity>> =
        flowOf(sessions.values.sortedByDescending { it.updatedAt })

    override fun getActiveSessionsFlow(): Flow<List<ChatSessionEntity>> =
        flowOf(sessions.values.filter { !it.isArchived }.sortedByDescending { it.updatedAt })

    override fun getArchivedSessionsFlow(): Flow<List<ChatSessionEntity>> =
        flowOf(sessions.values.filter { it.isArchived }.sortedByDescending { it.updatedAt })

    override suspend fun getSessionById(id: String): ChatSessionEntity? = sessions[id]

    override suspend fun searchSessions(query: String): List<ChatSessionEntity> =
        sessions.values.filter { it.title.contains(query, ignoreCase = true) }

    override suspend fun insertSession(session: ChatSessionEntity) {
        sessions[session.id] = session
    }

    override suspend fun updateSession(session: ChatSessionEntity) {
        sessions[session.id] = session
    }

    override suspend fun renameSession(sessionId: String, newTitle: String, updatedAt: Long) {
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(title = newTitle, updatedAt = updatedAt) }
    }

    override suspend fun setArchived(sessionId: String, isArchived: Boolean, updatedAt: Long) {
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(isArchived = isArchived, updatedAt = updatedAt) }
    }

    override suspend fun setPinned(sessionId: String, pinned: Boolean) {
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(pinned = pinned) }
    }

    override suspend fun updateSessionTimestamp(sessionId: String, updatedAt: Long) {
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(updatedAt = updatedAt) }
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    override fun getMessagesForSessionFlow(sessionId: String): Flow<List<ChatMessageEntity>> =
        flowOf(messages.filter { it.sessionId == sessionId }.sortedBy { it.timestamp })

    override suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> =
        messages.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }

    override suspend fun getMessagesPaged(sessionId: String, limit: Int, offset: Int): List<ChatMessageEntity> {
        val filtered = messages.filter { it.sessionId == sessionId }
            .sortedWith(compareByDescending<ChatMessageEntity> { it.timestamp }.thenByDescending { messages.indexOf(it) })
        val start = minOf(offset, filtered.size)
        val end = minOf(start + limit, filtered.size)
        return filtered.subList(start, end)
    }

    override suspend fun getMessageCount(sessionId: String): Int =
        messages.count { it.sessionId == sessionId }

    override suspend fun insertMessage(message: ChatMessageEntity) {
        val maxTs = messages.maxOfOrNull { it.timestamp } ?: 0L
        val assignedTs = if (message.timestamp <= maxTs) maxTs + 1L else message.timestamp
        messages.add(message.copy(timestamp = assignedTs))
    }

    override suspend fun insertMessages(messages: List<ChatMessageEntity>) {
        this.messages.addAll(messages)
    }

    override suspend fun updateMessage(message: ChatMessageEntity) {
        val idx = messages.indexOfFirst { it.id == message.id }
        if (idx >= 0) messages[idx] = message
    }

    override suspend fun deleteMessage(messageId: String) {
        messages.removeAll { it.id == messageId }
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        messages.removeAll { it.sessionId == sessionId }
    }

    override suspend fun searchMessages(query: String): List<ChatMessageEntity> =
        messages.filter { it.content.contains(query, ignoreCase = true) }
}

class FakeMemoryDao : MemoryDao {
    val allRawMemories = mutableListOf<MemoryEntity>()
    private val idCounter = AtomicLong(1L)

    override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> =
        flowOf(allRawMemories.filter { it.status != MemoryEntity.STATUS_DELETED })

    override fun getActiveMemoriesFlow(): Flow<List<MemoryEntity>> =
        flowOf(allRawMemories.filter { it.status == MemoryEntity.STATUS_ACTIVE })

    override suspend fun getAllMemories(): List<MemoryEntity> =
        allRawMemories.filter { it.status == MemoryEntity.STATUS_ACTIVE }

    override fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> =
        flowOf(allRawMemories.filter { it.category == category && it.status != MemoryEntity.STATUS_DELETED })

    override fun getMemoriesByTypeFlow(memoryType: String): Flow<List<MemoryEntity>> =
        flowOf(allRawMemories.filter { it.memoryType == memoryType && it.status == MemoryEntity.STATUS_ACTIVE })

    override fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>> =
        flowOf(allRawMemories.filter { (it.rawText.contains(query, ignoreCase = true) || it.key.contains(query, ignoreCase = true)) && it.status != MemoryEntity.STATUS_DELETED })

    override suspend fun searchMemories(query: String): List<MemoryEntity> =
        allRawMemories.filter { (it.rawText.contains(query, ignoreCase = true) || it.key.contains(query, ignoreCase = true)) && it.status == MemoryEntity.STATUS_ACTIVE }

    override suspend fun insertMemory(memory: MemoryEntity): Long {
        val assignedId = if (memory.id == 0L) idCounter.getAndIncrement() else memory.id
        val entity = memory.copy(id = assignedId)
        allRawMemories.add(entity)
        return assignedId
    }

    override suspend fun updateMemory(memory: MemoryEntity) {
        val idx = allRawMemories.indexOfFirst { it.id == memory.id }
        if (idx >= 0) allRawMemories[idx] = memory
    }

    override suspend fun deleteMemory(memory: MemoryEntity) {
        allRawMemories.removeAll { it.id == memory.id }
    }

    override suspend fun deleteMemoryById(id: Long) {
        allRawMemories.removeAll { it.id == id }
    }

    override suspend fun updateMemoryStatus(id: Long, status: String) {
        val idx = allRawMemories.indexOfFirst { it.id == id }
        if (idx >= 0) allRawMemories[idx] = allRawMemories[idx].copy(status = status)
    }

    override suspend fun softDeleteMemory(id: Long) {
        updateMemoryStatus(id, MemoryEntity.STATUS_DELETED)
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        val idx = allRawMemories.indexOfFirst { it.id == id }
        if (idx >= 0) allRawMemories[idx] = allRawMemories[idx].copy(pinned = pinned)
    }

    override suspend fun updateEmbedding(id: Long, embeddingJson: String, model: String, dimension: Int) {
        val idx = allRawMemories.indexOfFirst { it.id == id }
        if (idx >= 0) allRawMemories[idx] = allRawMemories[idx].copy(embeddingJson = embeddingJson, embeddingModel = model, embeddingDimension = dimension)
    }

    override suspend fun getActiveMemoriesWithEmbeddings(): List<MemoryEntity> =
        allRawMemories.filter { it.status == MemoryEntity.STATUS_ACTIVE && !it.embeddingJson.isNullOrBlank() }

    override suspend fun deleteMemoriesByKeyword(keyword: String): Int {
        val toRemove = allRawMemories.filter { it.rawText.contains(keyword, ignoreCase = true) }
        allRawMemories.removeAll(toRemove)
        return toRemove.size
    }

    override suspend fun getMemoryByKeyAndCategory(key: String, category: String): MemoryEntity? =
        allRawMemories.firstOrNull { it.key == key && it.category == category && it.status == MemoryEntity.STATUS_ACTIVE }

    override suspend fun getActiveMemoryByKey(key: String): MemoryEntity? =
        allRawMemories.firstOrNull { it.key == key && it.status == MemoryEntity.STATUS_ACTIVE }

    override suspend fun deleteExpiredMemories(currentTime: Long): Int {
        val expired = allRawMemories.filter { it.expiryTimeMs != null && it.expiryTimeMs < currentTime }
        allRawMemories.removeAll(expired)
        return expired.size
    }

    override suspend fun clearAllMemories() {
        allRawMemories.clear()
    }
}
