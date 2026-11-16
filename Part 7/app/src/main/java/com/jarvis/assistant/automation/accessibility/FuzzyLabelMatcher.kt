package com.jarvis.assistant.automation.accessibility

import java.text.Normalizer
import java.util.Locale

/** Bounded original matcher. Numeric identifiers require exact digit sequences. */
object FuzzyLabelMatcher {
    private val latinMarks=Regex("(?<=\\p{IsLatin})\\p{M}+")
    private val spaces=Regex("\\s+")
    fun normalize(value: String): String = Normalizer.normalize(value.take(256), Normalizer.Form.NFD)
        .replace(latinMarks, "").lowercase(Locale.ROOT)
        .replace('\u00a0', ' ').trim().replace(spaces, " ")

    fun matches(query: String, label: String): Boolean {
        if(query.length > 256 || label.length > 256) return false
        val a = normalize(query)
        val b = normalize(label)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        if (a.any(Char::isDigit) || b.any(Char::isDigit)) {
            // Never fuzzy-match a contact number, amount or account identifier.
            return a.count(Char::isDigit) >= 5 && a.filter(Char::isDigit) == b.filter(Char::isDigit) &&
                a.filterNot { it.isDigit() || it.isWhitespace() || it in "+-()" }.isEmpty() &&
                b.filterNot { it.isDigit() || it.isWhitespace() || it in "+-()" }.isEmpty()
        }
        if (a.length < 5 || b.length < 5) return false
        if(kotlin.math.abs(a.length-b.length) > maxOf(a.length,b.length)*.1) return false
        return similarity(a, b) >= 0.90
    }

    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1.0 else 0.0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1,
                previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            val swap = previous; previous = current; current = swap
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
