package com.jarvis.assistant.data.memory

import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe multi-turn conversational entity memory.
 * Preserves active product category, brands, specifications, and platforms
 * across conversational turns (e.g. "Google open karo best fridge dekho" ->
 * "Yeh wala nahi, ice cream wala" -> "500 liter wala" -> "Voltas ka dekho" -> "Flipkart me jao").
 */
object ConversationContextMemory {

    private val lock = Any()

    @Volatile var activeApp: String? = null
        private set

    @Volatile var baseProductCategory: String? = null
        private set

    @Volatile var brand: String? = null
        private set

    @Volatile var targetPlatform: String? = null
        private set

    private val modifiers = CopyOnWriteArrayList<String>()
    private val recentTurns = CopyOnWriteArrayList<String>()

    fun recordTurn(userUtterance: String) {
        synchronized(lock) {
            val clean = userUtterance.trim().lowercase(Locale.ROOT)
            if (clean.isBlank()) return

            recentTurns.add(clean)
            if (recentTurns.size > 5) {
                recentTurns.removeAt(0)
            }

            // Platform tracking
            when {
                clean.contains("flipkart") -> targetPlatform = "Flipkart"
                clean.contains("amazon") -> targetPlatform = "Amazon"
                clean.contains("google") || clean.contains("chrome") -> targetPlatform = "Google"
                clean.contains("youtube") -> targetPlatform = "YouTube"
                clean.contains("whatsapp") -> targetPlatform = "WhatsApp"
            }

            // Brand tracking
            val brands = listOf("voltas", "godrej", "rockwell", "samsung", "lg", "whirlpool", "haier", "blue star", "apple", "sony")
            for (b in brands) {
                if (clean.contains(b)) {
                    brand = b.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    break
                }
            }

            // Category & specification tracking
            when {
                clean.contains("ice cream") -> {
                    baseProductCategory = "commercial ice cream deep freezer"
                    if (!modifiers.contains("ice cream")) modifiers.add("ice cream")
                }
                clean.contains("deep freezer") || clean.contains("freezer") -> {
                    if (baseProductCategory == null) baseProductCategory = "deep freezer"
                }
                clean.contains("fridge") || clean.contains("refrigerator") -> {
                    if (baseProductCategory == null) baseProductCategory = "refrigerator"
                }
            }

            // Capacity & doors
            val capacityRegex = Regex("\\b(\\d{2,4})\\s*(?:liter|litre|l|ltr|lit)\\b")
            val capMatch = capacityRegex.find(clean)
            if (capMatch != null) {
                val cap = "${capMatch.groupValues[1]}L"
                modifiers.removeAll { it.endsWith("L") || it.contains("liter") || it.contains("litre") }
                modifiers.add(cap)
            }

            if (clean.contains("double door") || clean.contains("2 door")) {
                if (!modifiers.contains("double door")) modifiers.add("double door")
            } else if (clean.contains("single door") || clean.contains("1 door")) {
                if (!modifiers.contains("single door")) modifiers.add("single door")
            }

            if (clean.contains("glass top")) {
                if (!modifiers.contains("glass top")) modifiers.add("glass top")
            }
        }
    }

    /**
     * Resolves a potentially ambiguous follow-up instruction into a complete search query.
     */
    fun resolveContextualQuery(rawInput: String): String {
        synchronized(lock) {
            recordTurn(rawInput)

            val queryParts = mutableListOf<String>()
            brand?.let { queryParts.add(it) }
            modifiers.forEach { queryParts.add(it) }
            baseProductCategory?.let { queryParts.add(it) }

            if (queryParts.isEmpty()) {
                return rawInput
            }

            return queryParts.joinToString(" ")
        }
    }

    fun hasContext(): Boolean = baseProductCategory != null || brand != null || modifiers.isNotEmpty()

    fun clear() = reset()

    fun updateContext(product: String, platform: String? = null) {
        synchronized(lock) {
            recordTurn(product)
            if (platform != null) targetPlatform = platform
        }
    }

    fun reset() {
        synchronized(lock) {
            activeApp = null
            baseProductCategory = null
            brand = null
            targetPlatform = null
            modifiers.clear()
            recentTurns.clear()
        }
    }
}
