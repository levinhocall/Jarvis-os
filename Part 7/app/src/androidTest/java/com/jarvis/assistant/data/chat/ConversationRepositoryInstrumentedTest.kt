package com.jarvis.assistant.data.chat

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ConversationRepositoryTestDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}

@RunWith(AndroidJUnit4::class)
class ConversationRepositoryInstrumentedTest {
    private lateinit var database: ConversationRepositoryTestDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(
            context,
            ConversationRepositoryTestDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun appendTurnPersistsUserAndAssistantMessagesInOneSession() = runBlocking {
        val repository = ConversationRepository(database.chatDao())

        val sessionId = repository.appendTurn("Hello JARVIS", "Hello. How can I help?")

        assertNotNull(sessionId)
        val messages = repository.getMessages(sessionId!!)
        assertEquals(2, messages.size)
        assertEquals("USER", messages[0].role)
        assertEquals("Hello JARVIS", messages[0].content)
        assertEquals("ASSISTANT", messages[1].role)
        assertEquals("Hello. How can I help?", messages[1].content)
    }

    @Test
    fun repositoryRestoresLatestActiveSessionAfterRecreation() = runBlocking {
        val firstRepository = ConversationRepository(database.chatDao())
        val originalId = firstRepository.appendTurn("First question", "First answer")

        val recreatedRepository = ConversationRepository(database.chatDao())
        val restoredId = recreatedRepository.restoreMostRecentActiveSession()

        assertEquals(originalId, restoredId)
        val nextId = recreatedRepository.appendTurn("Second question", "Second answer")
        assertEquals(originalId, nextId)
        assertEquals(4, recreatedRepository.getMessages(originalId!!).size)
    }

    @Test
    fun blankTurnDoesNotCreateAnEmptySession() = runBlocking {
        val repository = ConversationRepository(database.chatDao())

        assertEquals(null, repository.appendTurn("  ", "  "))
        assertTrue(database.chatDao().getActiveSessionsFlow() != null)
    }
}
