package com.jarvis.assistant.data.chat

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class LegacyChatMigrationTest {
    private val json = """[{"id":"one","userTranscript":"Hello","jarvisResponse":"Hi","timestamp":100},{"id":"two","userTranscript":"Hello","jarvisResponse":"Hi","timestamp":200}]"""

    @Test fun importRetriesAfterProcessStopBeforeClearDoNotDuplicateOrLoseRepeatedText() = runBlocking {
        val dao = FakeChatDao()
        var source: String? = json
        val first = ConversationRepository(dao)
        assertTrue(runCatching {
            first.migrateLegacyHistory({ source }) { error("process stopped before preference clear") }
        }.isFailure)
        assertEquals(json, source)
        val second = ConversationRepository(dao)
        second.migrateLegacyHistory({ source }) { source = null }
        second.migrateLegacyHistory({ source }) { source = null }
        assertNull(source)
        val session = dao.getActiveSessionsFlow().first().single()
        assertEquals(4, dao.getMessagesForSession(session.id).size)
        assertEquals(listOf(100L, 101L, 200L, 201L), dao.getMessagesForSession(session.id).map { it.timestamp })
    }

    @Test fun emptyArrayClearsOnlyAfterSuccessfulImportAndCreatesNoEmptySession() = runBlocking {
        val dao = FakeChatDao()
        var cleared = false
        ConversationRepository(dao).migrateLegacyHistory({ "[]" }) { cleared = true }
        assertTrue(cleared)
        assertTrue(dao.getActiveSessionsFlow().first().isEmpty())
    }

    @Test fun absentSourceDoesNotClearOrCreateAnything() = runBlocking {
        val dao = FakeChatDao()
        ConversationRepository(dao).migrateLegacyHistory({ null }) { fail("must not clear absent source") }
        assertTrue(dao.getActiveSessionsFlow().first().isEmpty())
    }

    @Test fun corruptOrPartiallyValidJsonIsRetainedWithoutPartialImport() = runBlocking {
        for (raw in listOf("", "{", "null", "{}", "[null]", "[{},{}]", "[{\"userTranscript\":5}]",
            "[{\"userTranscript\":\"ok\"},null]")) {
            val dao = FakeChatDao()
            var cleared = false
            assertTrue(runCatching {
                ConversationRepository(dao).migrateLegacyHistory({ raw }) { cleared = true }
            }.isFailure)
            assertFalse(cleared)
            assertTrue(dao.getActiveSessionsFlow().first().isEmpty())
        }
    }

    @Test fun missingIdsAndTimestampsHaveStableImportIdentity() {
        val raw = """[{"userTranscript":"नमस्ते","jarvisResponse":"Hello"}]"""
        assertEquals(LegacyChatImport.plan(raw), LegacyChatImport.plan(raw))
        assertEquals("नमस्ते", LegacyChatImport.plan(raw).messages.first().content)
    }

    @Test fun conflictingExistingRowPreventsSourceClearAndPreservesExistingContent() = runBlocking {
        val dao = FakeChatDao()
        val plan = LegacyChatImport.plan(json)
        dao.insertSession(plan.session)
        val original = plan.messages.first().copy(content = "existing edited record")
        dao.insertLegacyMessages(listOf(original))
        var cleared = false
        assertTrue(runCatching {
            ConversationRepository(dao).migrateLegacyHistory({ json }) { cleared = true }
        }.isFailure)
        assertFalse(cleared)
        assertEquals(original, dao.getMessageById(original.id))
    }

    @Test fun clearHistoryAndSubsequentMigrationCannotResurrectDeletedSource() = runBlocking {
        val dao = FakeChatDao()
        val repository = ConversationRepository(dao)
        var source: String? = json
        repository.migrateLegacyHistory({ source }) { source = null }
        repository.clearAllConversationData { source = null }
        repository.migrateLegacyHistory({ source }) { source = null }
        assertTrue(dao.getActiveSessionsFlow().first().isEmpty())
    }

    @Test fun readinessFailureKeepsLegacySource() = runBlocking {
        val repository = ConversationRepository(FakeChatDao()) { error("key unavailable") }
        var cleared = false
        assertTrue(runCatching { repository.migrateLegacyHistory({ json }) { cleared = true } }.isFailure)
        assertFalse(cleared)
    }
}
