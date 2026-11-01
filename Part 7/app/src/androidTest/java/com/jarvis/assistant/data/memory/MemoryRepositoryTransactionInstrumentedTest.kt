package com.jarvis.assistant.data.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.data.memory.vector.DeterministicLocalEmbeddingProvider
import com.jarvis.assistant.data.memory.vector.EmbeddingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MemoryRepositoryTransactionInstrumentedTest {

    @Test
    fun failedEmbeddingDoesNotSupersedeExistingMemoryAndReplacementIsAtomic() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = MemoryDatabase.getInstance(context).memoryDao()
        val key = "transaction_${UUID.randomUUID()}"
        val stableRepository = MemoryRepository(dao, DeterministicLocalEmbeddingProvider())

        try {
            stableRepository.remember(
                content = "Original durable value",
                key = key,
                category = MemoryEntity.CATEGORY_NOTES
            )

            val failingProvider = object : EmbeddingProvider {
                override val modelName: String = "failing-test-provider"
                override val dimension: Int = 128
                override fun isAvailable(): Boolean = true
                override suspend fun generateEmbedding(text: String): FloatArray? {
                    throw IllegalStateException("Simulated embedding failure")
                }
            }
            val failingRepository = MemoryRepository(dao, failingProvider)
            var replacementFailed = false
            try {
                failingRepository.remember(
                    content = "Replacement value must not displace the original",
                    key = key,
                    category = MemoryEntity.CATEGORY_NOTES
                )
            } catch (_: IllegalStateException) {
                replacementFailed = true
            }
            assertTrue("The simulated embedding failure must be surfaced.", replacementFailed)

            val afterFailure = dao.getMemoriesByKeyIncludingInactive(key)
            assertEquals(1, afterFailure.size)
            assertEquals(MemoryEntity.STATUS_ACTIVE, afterFailure.single().status)
            assertEquals("Original durable value", afterFailure.single().value)

            stableRepository.remember(
                content = "Committed replacement value",
                key = key,
                category = MemoryEntity.CATEGORY_NOTES
            )
            val afterReplacement = dao.getMemoriesByKeyIncludingInactive(key)
            assertEquals(2, afterReplacement.size)
            assertEquals(1, afterReplacement.count { it.status == MemoryEntity.STATUS_ACTIVE })
            assertEquals(
                "Committed replacement value",
                afterReplacement.single { it.status == MemoryEntity.STATUS_ACTIVE }.value
            )
            assertTrue(afterReplacement.any { it.status == MemoryEntity.STATUS_SUPERSEDED })
        } finally {
            dao.deleteMemoriesByKeyword(key)
        }
    }
}
