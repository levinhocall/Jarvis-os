package com.jarvis.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodicVectorMemoryTest {

    @Test
    fun testSensitiveCredentialsSanitizedBeforeStorage() {
        val rawWithPassword = "My WiFi password is SecretPassword123 for HomeNet"
        val sanitizedPwd = EpisodicVectorMemory.sanitizeForStorage(rawWithPassword)
        assertFalse(sanitizedPwd.contains("SecretPassword123"))
        assertTrue(sanitizedPwd.contains("[PROTECTED_CREDENTIAL]"))

        val rawWithCard = "Save my card number 4111 2222 3333 4444 for shopping"
        val sanitizedCard = EpisodicVectorMemory.sanitizeForStorage(rawWithCard)
        assertFalse(sanitizedCard.contains("4111 2222 3333 4444"))
        assertTrue(sanitizedCard.contains("[PROTECTED_CREDENTIAL]"))

        val rawWithApiKey = "My Gemini key is api_key=AIzaSyD-1234567890abcdef"
        val sanitizedApiKey = EpisodicVectorMemory.sanitizeForStorage(rawWithApiKey)
        assertFalse(sanitizedApiKey.contains("AIzaSyD-1234567890abcdef"))
    }

    @Test
    fun testCosineSimilarityCalculations() {
        val v1 = mapOf("voltas" to 0.707f, "ac" to 0.707f)
        val v2 = mapOf("voltas" to 0.707f, "ac" to 0.707f)
        val v3 = mapOf("pizza" to 0.707f, "order" to 0.707f)

        val identicalSim = EpisodicVectorMemory.computeCosineSimilarity(v1, v2)
        assertTrue("Identical term vectors should have similarity ~1.0", identicalSim > 0.99f)

        val orthogonalSim = EpisodicVectorMemory.computeCosineSimilarity(v1, v3)
        assertEquals("Completely disjoint vectors must have 0.0 similarity", 0.0f, orthogonalSim, 0.001f)
    }

    @Test
    fun testVectorMemoryRankingRanksRelevantMemoryHighest() {
        val now = System.currentTimeMillis()
        val memories = listOf(
            EpisodicVectorMemory.MemoryRecord(
                id = 1,
                scope = EpisodicVectorMemory.MemoryScope.EPISODIC_MEMORY,
                category = MemoryEntity.CATEGORY_NOTES,
                key = "voltas split ac",
                value = "Rs. 32,990 on Amazon",
                text = "Product: Voltas 1.5 Ton Split AC | Price: Rs. 32,990 | Platform: Amazon",
                timestamp = now - 10_000L
            ),
            EpisodicVectorMemory.MemoryRecord(
                id = 2,
                scope = EpisodicVectorMemory.MemoryScope.EPISODIC_MEMORY,
                category = MemoryEntity.CATEGORY_NOTES,
                key = "grocery list",
                value = "Milk, Eggs, Bread",
                text = "Grocery items to buy from supermarket: Milk, Eggs, Bread",
                timestamp = now - 20_000L
            ),
            EpisodicVectorMemory.MemoryRecord(
                id = 3,
                scope = EpisodicVectorMemory.MemoryScope.USER_PREFERENCE,
                category = MemoryEntity.CATEGORY_PREFERENCES,
                key = "favorite song",
                value = "Tadipaar",
                text = "User favorite track is Tadipaar by MC Stan",
                timestamp = now - 50_000L
            )
        )

        // Query specifically for the air conditioner price
        val rankedAC = EpisodicVectorMemory.rankMemories("voltas ac price on amazon", memories, now)
        assertTrue("Should return relevant matches", rankedAC.isNotEmpty())
        assertEquals("Voltas AC memory must be ranked first", 1L, rankedAC.first().record.id)
        assertTrue("Top score should be high", rankedAC.first().score > 0.5f)

        // Query specifically for groceries
        val rankedGrocery = EpisodicVectorMemory.rankMemories("what groceries to buy", memories, now)
        assertTrue(rankedGrocery.isNotEmpty())
        assertEquals("Grocery memory must be ranked first", 2L, rankedGrocery.first().record.id)
    }

    @Test
    fun testIrrelevantQueryReturnsEmptyListBelowCutoff() {
        val now = System.currentTimeMillis()
        val memories = listOf(
            EpisodicVectorMemory.MemoryRecord(
                id = 1,
                scope = EpisodicVectorMemory.MemoryScope.EPISODIC_MEMORY,
                category = MemoryEntity.CATEGORY_NOTES,
                key = "flight to tokyo",
                value = "Departure at 10 AM",
                text = "Flight to Tokyo departing at 10 AM terminal 3",
                timestamp = now
            )
        )

        // Query with completely unrelated terms
        val results = EpisodicVectorMemory.rankMemories("deep learning quantum mechanics equations", memories, now)
        assertTrue("Irrelevant query must not return unrelated memories", results.isEmpty())
    }

    @Test
    fun testRecencyDecayWeightsRecentMemoriesHigher() {
        val now = System.currentTimeMillis()
        val recentTime = now - (1000L * 3600L) // 1 hour ago
        val oldTime = now - (1000L * 3600L * 24L * 90L) // 90 days ago

        val recentFactor = EpisodicVectorMemory.computeRecencyFactor(recentTime, now)
        val oldFactor = EpisodicVectorMemory.computeRecencyFactor(oldTime, now)

        assertTrue("Recent memory factor must be significantly higher than 90-day old memory",
            recentFactor > oldFactor)
        assertTrue("Recent memory factor should be near 1.0", recentFactor > 0.95f)
        assertTrue("Old memory factor should decay towards 0.1", oldFactor < 0.25f)
    }
}
