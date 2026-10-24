package com.jarvis.assistant.data.model

enum class DetectedLanguage {
    ENGLISH,
    HINDI,
    HINGLISH,
    UNKNOWN
}

/**
 * Pure-logic language detector for user utterances:
 * - Devanagari script (\u0900..\u097F) -> HINDI
 * - Romanized conversational Hindi markers and ratios -> HINGLISH
 * - Standard Latin alphabet -> ENGLISH
 * - Symbols / non-alphabetic / blank -> UNKNOWN
 */
object LanguageDetector {

    private val HINGLISH_MARKERS = setOf(
        "kya", "hai", "hain", "karo", "karna", "kar", "karun", "karunga", "karenge",
        "bhai", "haan", "han", "nahi", "nahin", "accha", "achha", "achhi", "kaise", "kaisa", "kaisi",
        "bolo", "batao", "bata", "batayein", "shukriya", "theek", "thik", "mera", "meri", "mere",
        "aap", "aapka", "aapki", "aapke", "tum", "tumhara", "tumhari", "hum", "hume", "humara",
        "yaar", "chalo", "sun", "suno", "suniye", "mujhe", "tujhe", "wala", "wali", "wale",
        "kuch", "kuchh", "bahut", "bohot", "dhanyawad", "namaste", "shukran", "kaun", "kab",
        "kyun", "kyu", "kahan", "kitna", "kitni", "kitne", "lekin", "magar", "par", "aur",
        "toh", "to", "mat", "raha", "rahi", "rahe", "hoga", "hogi", "honge", "dena", "do",
        "dijiye", "bhi", "sirf", "zaroor", "zarur", "samajh", "aaj", "kal", "ab", "abhi",
        "paas", "dur", "saath", "liye", "baat", "waqt", "samay", "bilkul",
        "ye", "yeh", "woh", "vo", "gaana", "gana", "gaane", "na", "jaa", "jao", "aao",
        "dekho", "pe", "se", "ko", "ka", "ki", "ke", "mein", "me", "khol", "kholo",
        "lagao", "chalao", "sunao", "bhejo", "ruk", "ruko"
    )

    fun detect(text: String?): DetectedLanguage {
        if (text.isNullOrBlank()) return DetectedLanguage.UNKNOWN

        var devanagariCount = 0
        var latinCount = 0

        for (ch in text) {
            when {
                ch in '\u0900'..'\u097F' -> devanagariCount++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latinCount++
            }
        }

        if (devanagariCount == 0 && latinCount == 0) {
            return DetectedLanguage.UNKNOWN
        }

        // If Devanagari characters are present and significant
        if (devanagariCount > 0) {
            val totalLetters = devanagariCount + latinCount
            if (devanagariCount.toDouble() / totalLetters.toDouble() >= 0.20 || latinCount == 0) {
                return DetectedLanguage.HINDI
            }
        }

        // Tokenize Latin words
        val words = text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it.any { c -> c.isLetter() } }

        if (words.isEmpty()) {
            return DetectedLanguage.UNKNOWN
        }

        val totalWords = words.size
        var markerMatches = 0
        for (w in words) {
            if (w in HINGLISH_MARKERS) {
                markerMatches++
            }
        }

        val markerRatio = markerMatches.toDouble() / totalWords.toDouble()

        if (markerMatches >= 2 || (markerMatches >= 1 && totalWords <= 5) || markerRatio >= 0.15) {
            return DetectedLanguage.HINGLISH
        }

        return DetectedLanguage.ENGLISH
    }
}
