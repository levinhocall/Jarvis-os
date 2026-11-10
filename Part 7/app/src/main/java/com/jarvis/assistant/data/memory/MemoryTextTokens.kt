package com.jarvis.assistant.data.memory

import java.text.Normalizer
import java.util.Locale

/** Unicode letters AND combining marks: Hindi vowel signs must stay with their words. */
object MemoryTextTokens {
    private val term = Regex("[\\p{L}\\p{M}\\p{N}_]+")

    fun terms(text: String, minimumCodePoints: Int = 2): List<String> {
        require(minimumCodePoints >= 1)
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace("\u200C", "").replace("\u200D", "")
        return term.findAll(normalized).map { it.value }
            .filter { it.codePointCount(0, it.length) >= minimumCodePoints }.toList()
    }
}
