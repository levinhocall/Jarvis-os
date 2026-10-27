package com.jarvis.assistant.agent

class TargetResolver {

    fun resolve(
        observation: ScreenObservation,
        query: String,
        requireClickable: Boolean = false
    ): UiElement? {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return null

        val candidates = if (requireClickable) {
            observation.elements.filter { it.isClickable }
        } else {
            observation.elements
        }

        // 1. Exact ID match
        candidates.firstOrNull { it.id.equals(query, ignoreCase = true) }?.let { return it }

        // 2. Exact text or contentDescription match
        candidates.firstOrNull {
            it.text.trim().equals(cleanQuery, ignoreCase = true) ||
                    it.contentDescription.trim().equals(cleanQuery, ignoreCase = true)
        }?.let { return it }

        // 3. Word-prefix match (>= 3 chars)
        if (cleanQuery.length >= 3) {
            candidates.firstOrNull {
                it.text.lowercase().split(" ").any { w -> w.startsWith(cleanQuery) } ||
                        it.contentDescription.lowercase().split(" ").any { w -> w.startsWith(cleanQuery) }
            }?.let { return it }
        }

        // 4. Substring contains match
        return candidates.firstOrNull {
            it.text.contains(cleanQuery, ignoreCase = true) ||
                    it.contentDescription.contains(cleanQuery, ignoreCase = true)
        }
    }
}
