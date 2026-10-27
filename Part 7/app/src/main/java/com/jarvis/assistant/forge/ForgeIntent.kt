package com.jarvis.assistant.forge

import java.util.Locale

/**
 * Natural language intent matcher for Website Forge creation commands.
 * Distinguishes explicit creation requests from informational questions or general search.
 */
object ForgeIntent {

    private val SUBJECT_KEYWORDS = setOf(
        "website",
        "web site",
        "webpage",
        "web page",
        "landing page",
        "landingpage",
        "portfolio site",
        "portfolio website",
        "web app"
    )

    private val BUILD_VERBS = setOf(
        "build",
        "create",
        "make",
        "generate",
        "design",
        "develop",
        "bana",
        "banao",
        "banado",
        "taiydar karo",
        "construct"
    )

    private val QUESTION_MARKERS = setOf(
        "what is",
        "what are",
        "how to",
        "how do",
        "why is",
        "explain",
        "difference between",
        "search for",
        "meaning of",
        "kya hota hai",
        "kaise banta hai"
    )

    private val PREFIX_CLEAN_REGEX = Regex(
        """^(?:hey\s+)?(?:jarvis|lia)?\s*[,:]?\s*(?:please\s+)?(?:can\s+you\s+)?(?:could\s+you\s+)?(?:i\s+want\s+to\s+)?(?:build|create|make|generate|design|bana|banao|banado)\s+(?:me\s+)?(?:a\s+|an\s+|the\s+)?(?:website|landing\s+page|webpage)?\s*(?:for\s+|about\s+|on\s+)?\s*(?:a\s+|an\s+|the\s+)?\s*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns true if the query is an actionable instruction to build a website.
     */
    fun matches(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return false

        // 1. Ignore if it is an informational question
        if (lower.endsWith("?") || QUESTION_MARKERS.any { lower.startsWith(it) || lower.contains(it) }) {
            return false
        }

        // 2. Check for website subject
        val hasSubject = SUBJECT_KEYWORDS.any { lower.contains(it) }
        if (!hasSubject) return false

        // 3. Check for build verb
        val hasVerb = BUILD_VERBS.any { verb ->
            val pattern = Regex("""\b${Regex.escape(verb)}\b""", RegexOption.IGNORE_CASE)
            pattern.containsMatchIn(lower)
        }

        return hasVerb
    }

    /**
     * Extracts the core website description / prompt from the user message.
     */
    fun extractPrompt(text: String): String {
        val cleaned = text.trim()
        val stripped = PREFIX_CLEAN_REGEX.replace(cleaned, "").trim()
        return if (stripped.length >= 3) stripped else cleaned
    }
}
