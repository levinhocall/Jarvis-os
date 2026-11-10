package com.jarvis.assistant.data.memory

import com.jarvis.assistant.data.memory.vector.DeterministicLocalEmbeddingProvider
import com.jarvis.assistant.data.memory.vector.MatchType
import com.jarvis.assistant.data.memory.vector.VectorSearchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MultilingualMemoryRetrievalTest {
    @Test fun hindiVowelMarksAndCanonicalEquivalentTextRemainIntact() {
        val text = "मेरा पसंदीदा रंग नीला है"
        assertTrue(MemoryTextTokens.terms(text).containsAll(listOf("पसंदीदा", "नीला")))
        assertTrue(EpisodicVectorMemory.tokenize(text).contains("नीला"))
        assertEquals(MemoryTextTokens.terms("क़िला"), MemoryTextTokens.terms("क़िला"))
        assertEquals(MemoryTextTokens.terms("क्षेत्र"), MemoryTextTokens.terms("क्‍षेत्र"))
    }

    @Test fun hindiAndHinglishProduceFiniteLocalVectors() = runBlocking {
        val provider = DeterministicLocalEmbeddingProvider()
        for (text in listOf("मेरा पसंदीदा रंग नीला है", "mera favourite rang blue hai", "my favourite colour is blue")) {
            val vector = provider.generateEmbedding(text)!!
            assertEquals(128, vector.size)
            assertTrue(vector.all { it.isFinite() })
            assertTrue(vector.any { it > 0f })
        }
    }

    @Test fun hindiKeywordFallbackFindsExistingUnindexedMemories() = runBlocking {
        val provider = DeterministicLocalEmbeddingProvider()
        val relevant = memory(1, "मेरा पसंदीदा रंग नीला है")
        val unrelated = memory(2, "कल जिम जाना है")
        val found = VectorSearchEngine().search("नीला", listOf(unrelated, relevant), provider)
        assertEquals(listOf(1L), found.map { it.memory.id })
        assertEquals(MatchType.KEYWORD_FALLBACK, found.single().matchType)
    }

    @Test fun sameDimensionIsNotEnoughToCompareDifferentEmbeddingVersions() = runBlocking {
        val provider = DeterministicLocalEmbeddingProvider()
        val engine = VectorSearchEngine()
        val stale = memory(1, "कल जिम जाना है").copy(
            embeddingJson = engine.serializeEmbedding(provider.generateEmbedding("नीला")!!),
            embeddingDimension = provider.dimension, embeddingModel = "obsolete-ascii-model")
        assertTrue(engine.search("नीला", listOf(stale), provider).isEmpty())
    }

    @Test fun corruptNonFiniteVectorFallsBackToKeywords() = runBlocking {
        val provider = DeterministicLocalEmbeddingProvider()
        val relevant = memory(1, "नीला रंग").copy(embeddingJson = "[NaN]",
            embeddingDimension = provider.dimension, embeddingModel = provider.modelName)
        val result = VectorSearchEngine().search("नीला", listOf(relevant), provider)
        assertEquals(MatchType.KEYWORD_FALLBACK, result.single().matchType)
    }

    private fun memory(id: Long, text: String) = MemoryEntity(id = id, category = "Notes", key = "note-$id", value = text, rawText = text)
}
