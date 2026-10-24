package com.jarvis.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationMemoryTest {

    private lateinit var memory: ConversationMemory

    @Before
    fun setUp() {
        memory = ConversationMemory(maxTurns = 12)
    }

    @Test
    fun testRollingWindowEvictsOldestWhenExceeding12Turns() {
        for (i in 1..15) {
            val added = memory.addTurn(role = if (i % 2 == 1) "User" else "Jarvis", content = "Message #$i")
            assertTrue(added)
        }

        assertEquals(12, memory.turnCount())
        val recent = memory.getRecentTurns()
        assertEquals(12, recent.size)
        // Earliest message in memory should be #4 (1, 2, 3 were evicted)
        assertEquals("Message #4", recent.first().content)
        assertEquals("Message #15", recent.last().content)
    }

    @Test
    fun testEmptyOrBlankContentIsIgnored() {
        assertFalse(memory.addTurn("User", ""))
        assertFalse(memory.addTurn("User", "   "))
        assertEquals(0, memory.turnCount())
    }

    @Test
    fun testRecapFormattingContainsPrefixAndRecentTurns() {
        memory.addTurn("User", "What is the capital of India?")
        memory.addTurn("Jarvis", "The capital of India is New Delhi, Sir.")

        val recap = memory.recap()
        assertTrue(recap.startsWith("[Prior Conversation Context — Resume without re-greeting]:"))
        assertTrue(recap.contains("User: What is the capital of India?"))
        assertTrue(recap.contains("Jarvis: The capital of India is New Delhi, Sir."))
    }

    @Test
    fun testRecapLengthCappedAtMaxChars() {
        // Add multiple large turns
        for (i in 1..12) {
            memory.addTurn(
                role = "User",
                content = "This is a very detailed long query number $i with a lot of words to increase length."
            )
            memory.addTurn(
                role = "Jarvis",
                content = "This is a very detailed response number $i that contains multiple sentences to check the character limit behavior."
            )
        }

        val maxCap = 500
        val recap = memory.recap(maxChars = maxCap)
        assertTrue("Recap length (${recap.length}) must be <= $maxCap", recap.length <= maxCap)
        assertTrue(recap.startsWith("[Prior Conversation Context — Resume without re-greeting]:"))
    }

    @Test
    fun testClearRemovesAllTurns() {
        memory.addTurn("User", "Hello")
        memory.addTurn("Jarvis", "Hi there")
        assertEquals(2, memory.turnCount())

        memory.clear()
        assertEquals(0, memory.turnCount())
        assertEquals("", memory.recap())
    }
}
