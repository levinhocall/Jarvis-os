package com.jarvis.assistant.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PersistentMemoryTest {

    @Before
    fun setUp() {
        PersonalMemoryGraph.clearAll()
        ConversationContextMemory.clear()
    }

    @Test
    fun testPersonalMemoryGraph_recordAndContradictionDetection() = runBlocking {
        // Record initial fact
        val (node1, report1) = PersonalMemoryGraph.recordMemory(
            key = "favorite_city",
            value = "Tokyo",
            category = "Preferences",
            provenance = MemoryProvenance.EXPLICIT_USER_STATEMENT,
            sensitivity = MemorySensitivity.PREFERENCE
        )

        assertNotNull(node1)
        assertEquals("favorite_city", node1.key)
        assertEquals("Tokyo", node1.value)
        assertFalse(report1.hasContradiction)

        // Record conflicting fact for the same key
        val (node2, report2) = PersonalMemoryGraph.recordMemory(
            key = "favorite_city",
            value = "Paris",
            category = "Preferences",
            provenance = MemoryProvenance.EXPLICIT_USER_STATEMENT,
            sensitivity = MemorySensitivity.PREFERENCE
        )

        assertNotNull(node2)
        assertEquals("Paris", node2.value)
        assertTrue(report2.hasContradiction)
        assertEquals("Tokyo", report2.existingNode?.value)
        assertEquals("Paris", report2.proposedValue)

        // Verify active memories has only 1 entry (no stale duplicates)
        val allMemories = PersonalMemoryGraph.getAllActiveMemories()
        assertEquals(1, allMemories.size)
        assertEquals("Paris", allMemories.first().value)
    }

    @Test
    fun testPersonalMemoryGraph_provenanceAndSensitivityPreserved() = runBlocking {
        val (node, _) = PersonalMemoryGraph.recordMemory(
            key = "project_deadline",
            value = "Friday 5 PM",
            category = "Routines",
            provenance = MemoryProvenance.MISSION_OUTCOME,
            sensitivity = MemorySensitivity.WORKFLOW_REFERENCE
        )

        assertEquals(MemoryProvenance.MISSION_OUTCOME, node.provenance)
        assertEquals(MemorySensitivity.WORKFLOW_REFERENCE, node.sensitivity)
        assertTrue(node.createdAt > 0)
    }

    @Test
    fun testPersonalMemoryGraph_expiryFiltering() = runBlocking {
        // Record memory that expired yesterday
        PersonalMemoryGraph.recordMemory(
            key = "temp_passcode",
            value = "1234",
            category = "Notes",
            ttlDays = -1 // Already expired
        )

        // Record valid memory
        PersonalMemoryGraph.recordMemory(
            key = "home_wifi",
            value = "FiberNet",
            category = "Preferences",
            ttlDays = 30
        )

        val active = PersonalMemoryGraph.getAllActiveMemories()
        assertEquals(1, active.size)
        assertEquals("home_wifi", active.first().key)

        val query = PersonalMemoryGraph.queryRelevantMemories(null, "passcode")
        assertTrue("Expired passcode must not be retrieved", query.isEmpty())
    }

    @Test
    fun testConversationContextMemory_isolationFromPersistentGraph() {
        // Record transient context in conversation memory
        ConversationContextMemory.recordTurn("Google me jao voltas ka 500 liter deep freezer dekho")

        assertEquals("Google", ConversationContextMemory.targetPlatform)
        assertEquals("Voltas", ConversationContextMemory.brand)
        assertEquals("deep freezer", ConversationContextMemory.baseProductCategory)

        // Verify persistent memory graph remains completely empty (transient state is NOT polluted into long-term graph)
        val persistentMemories = PersonalMemoryGraph.getAllActiveMemories()
        assertTrue("Conversation context turns must not pollute persistent graph directly", persistentMemories.isEmpty())
    }

    @Test
    fun testMemoryEntity_backwardCompatibilityAndDefaults() {
        val entity = MemoryEntity(
            id = 10L,
            category = MemoryEntity.CATEGORY_PERSONAL,
            key = "user_name",
            value = "Stark",
            rawText = "My name is Stark"
        )

        assertEquals("EXPLICIT_USER_STATEMENT", entity.provenance)
        assertEquals("PREFERENCE", entity.sensitivity)
        assertNull(entity.expiryTimeMs)
        assertFalse(entity.isExpired)
    }

    @Test
    fun testMemoryDatabase_migration1to2Definition() {
        assertNotNull(MemoryDatabase.MIGRATION_1_2)
        assertEquals(1, MemoryDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, MemoryDatabase.MIGRATION_1_2.endVersion)
    }
}
