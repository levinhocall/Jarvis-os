package com.jarvis.assistant.audio.wake

import java.util.Locale

/** Pure policy shared by the live listener and tests. Never matches arbitrary substrings. */
object WakeMatcher {
    data class Word(val text: String, val confidence: Double, val start: Double, val end: Double)
    data class Decision(val accepted: Boolean, val reason: String, val confidence: Double = 0.0)
    fun normalize(text: String): String = text.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    fun validName(text: String): Boolean = normalize(text).matches(Regex("[a-z]{2,20}( [a-z]{2,20})?"))
    fun evaluate(words: List<Word>, names: List<String>, sensitivity: Int): Decision {
        if (words.isEmpty() || words.size > 6) return Decision(false, "Say the wake phrase on its own")
        if (words.any { !it.confidence.isFinite() || it.confidence !in 0.0..1.0 || !it.start.isFinite() || !it.end.isFinite() || it.end < it.start }) return Decision(false, "Invalid acoustic result")
        val duration = words.last().end - words.first().start
        if (duration !in 0.12..4.0) return Decision(false, "Speech duration rejected")
        val phrase = words.joinToString(" ") { normalize(it.text) }
        val prefixes = listOf("", "hey ", "hi ", "hello ", "ok ", "okay ", "wake up ", "sun ", "suno ")
        val sensitivityValue = sensitivity.coerceIn(0,100)
        val threshold = .95 - .30 * sensitivityValue / 100.0
        val confidence = words.minOf { it.confidence }
        if (confidence < threshold) return Decision(false, "Below sensitivity threshold", confidence)
        for (name in names.filter(::validName).map(::normalize)) {
            for (prefix in prefixes) {
                val candidate = prefix + name
                if (phrase == candidate) return Decision(true, "Exact wake phrase", confidence)
                // Fuzzy single-word names require a spoken prefix and high sensitivity.
                if (sensitivityValue >= 75 && prefix.isNotEmpty() && !name.contains(' ') && name.length >= 4 && phrase.startsWith(prefix)) {
                    val heard = phrase.removePrefix(prefix)
                    if (!heard.contains(' ') && oneEditApart(heard,name)) return Decision(true, "Fuzzy wake phrase", confidence)
                }
            }
        }
        return Decision(false, "No wake phrase match", confidence)
    }
    private fun oneEditApart(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length-b.length)>1) return false
        var i=0; var j=0; var edits=0
        while(i<a.length && j<b.length) {
            if(a[i]==b[j]) {i++;j++;continue}
            if(++edits>1) return false
            if(a.length>=b.length)i++
            if(b.length>=a.length)j++
        }
        return edits+(a.length-i)+(b.length-j)==1
    }
}
