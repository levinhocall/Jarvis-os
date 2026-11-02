package com.jarvis.assistant.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTurnMapperTest {
    @Test
    fun mapsUserAndAssistantMessagesIntoSingleTurn() {
        val turns = ConversationTurnMapper.toTurns(
            listOf(
                ChatMessageEntity(id = "u1", sessionId = "s", role = "USER", content = "Hello", timestamp = 10),
                ChatMessageEntity(id = "a1", sessionId = "s", role = "ASSISTANT", content = "Hi", timestamp = 11)
            )
        )
        assertEquals(1, turns.size)
        assertEquals("Hello", turns.single().userTranscript)
        assertEquals("Hi", turns.single().jarvisResponse)
    }

    @Test
    fun preservesPendingUserTurnAndAssistantOnlyMessages() {
        val turns = ConversationTurnMapper.toTurns(
            listOf(
                ChatMessageEntity(id = "u1", sessionId = "s", role = "USER", content = "Pending", timestamp = 10),
                ChatMessageEntity(id = "a1", sessionId = "s", role = "ASSISTANT", content = "Unpaired", timestamp = 20)
            )
        )
        assertEquals(2, turns.size)
        assertEquals("", turns[0].jarvisResponse)
        assertEquals("", turns[1].userTranscript)
        assertEquals("Unpaired", turns[1].jarvisResponse)
    }

    @Test
    fun doesNotPairMessagesAcrossDifferentSessions() {
        val turns = ConversationTurnMapper.toTurns(
            listOf(
                ChatMessageEntity(id = "u1", sessionId = "old", role = "USER", content = "Old pending", timestamp = 10),
                ChatMessageEntity(id = "a2", sessionId = "new", role = "ASSISTANT", content = "New answer", timestamp = 11)
            )
        )
        assertEquals(2, turns.size)
        assertEquals("Old pending", turns[0].userTranscript)
        assertEquals("", turns[0].jarvisResponse)
        assertEquals("", turns[1].userTranscript)
        assertEquals("New answer", turns[1].jarvisResponse)
    }

    @Test
    fun ignoresSystemMessagesAndSortsByTimestamp() {
        val turns = ConversationTurnMapper.toTurns(
            listOf(
                ChatMessageEntity(id = "a", sessionId = "s", role = "ASSISTANT", content = "Answer", timestamp = 20),
                ChatMessageEntity(id = "sys", sessionId = "s", role = "SYSTEM", content = "system", timestamp = 5),
                ChatMessageEntity(id = "u", sessionId = "s", role = "USER", content = "Question", timestamp = 10)
            )
        )
        assertEquals(1, turns.size)
        assertEquals("Question", turns.single().userTranscript)
        assertEquals("Answer", turns.single().jarvisResponse)
    }
}
