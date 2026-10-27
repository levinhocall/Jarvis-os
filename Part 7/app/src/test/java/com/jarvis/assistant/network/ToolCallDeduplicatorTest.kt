package com.jarvis.assistant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallDeduplicatorTest {

    @Test
    fun `test deduplicator allows new call ids and rejects duplicates`() {
        val deduplicator = ToolCallDeduplicator(maxCapacity = 10)

        assertTrue(deduplicator.shouldProcess("call_001"))
        assertFalse("Duplicate call_001 must be rejected", deduplicator.shouldProcess("call_001"))

        assertTrue(deduplicator.shouldProcess("call_002"))
        assertFalse(deduplicator.shouldProcess("call_002"))
        assertFalse(deduplicator.shouldProcess("call_001"))
    }

    @Test
    fun `test deduplicator bounds capacity and evicts eldest entry`() {
        val capacity = 3
        val deduplicator = ToolCallDeduplicator(maxCapacity = capacity)

        assertTrue(deduplicator.shouldProcess("call_1"))
        assertTrue(deduplicator.shouldProcess("call_2"))
        assertTrue(deduplicator.shouldProcess("call_3"))
        assertEquals(3, deduplicator.size())

        // Adding 4th should evict the eldest ("call_1")
        assertTrue(deduplicator.shouldProcess("call_4"))
        assertEquals(3, deduplicator.size())

        // "call_1" was evicted, so it can be accepted again if re-issued after eviction
        assertTrue("Evicted call_1 should be accepted again", deduplicator.shouldProcess("call_1"))
        // "call_2" was the next eldest, should now have been evicted
        assertFalse(deduplicator.shouldProcess("call_4"))
    }

    @Test
    fun `test blank or empty call ids are rejected`() {
        val deduplicator = ToolCallDeduplicator()
        assertFalse(deduplicator.shouldProcess(""))
        assertFalse(deduplicator.shouldProcess("   "))
    }

    @Test
    fun `test clear resets tracked calls`() {
        val deduplicator = ToolCallDeduplicator()
        assertTrue(deduplicator.shouldProcess("call_abc"))
        deduplicator.clear()
        assertEquals(0, deduplicator.size())
        assertTrue(deduplicator.shouldProcess("call_abc"))
    }
}
