package com.jarvis.assistant.data.memory

import com.jarvis.assistant.data.memory.vector.DeterministicLocalEmbeddingProvider
import com.jarvis.assistant.data.memory.vector.EmbeddingProvider
import com.jarvis.assistant.data.memory.vector.GeminiEmbeddingProvider
import com.jarvis.assistant.data.memory.vector.RemoteNeuralEmbeddingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalOnlyEmbeddingTest {
    @Test fun `legacy Gemini adapter never reads provider credentials`() = runBlocking {
        val provider = GeminiEmbeddingProvider({ error("Cloud credentials must not be requested") })
        assertTrue(provider.isAvailable())
        assertArrayEquals(DeterministicLocalEmbeddingProvider(768).generateEmbedding("private fact")!!,
            provider.generateEmbedding("private fact")!!, 0f)
        assertEquals("local-deterministic-l2-768", provider.modelName)
    }

    @Test fun `legacy remote adapter ignores endpoint and untrusted fallback`() = runBlocking {
        val forbidden = object : EmbeddingProvider {
            override val modelName = "remote"
            override val dimension = 384
            override fun isAvailable() = true
            override suspend fun generateEmbedding(text: String): FloatArray? =
                error("An injected remote fallback must not receive memory")
        }
        val provider = RemoteNeuralEmbeddingProvider("https://example.invalid", forbidden)
        val vectors = provider.generateEmbeddings(listOf("private fact", ""))
        assertArrayEquals(DeterministicLocalEmbeddingProvider(384).generateEmbedding("private fact")!!,
            vectors[0]!!, 0f)
        assertNull(vectors[1])
        assertEquals("local-deterministic-l2-384", provider.modelName)
    }
}
