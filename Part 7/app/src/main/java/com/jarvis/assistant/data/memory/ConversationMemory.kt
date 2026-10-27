package com.jarvis.assistant.data.memory

import java.util.ArrayDeque

data class Turn(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * In-memory rolling window of recent conversation turns.
 * Thread-safe, bounded to 12 turns max, and generates a recap
 * capped to 1600 characters for session resume without re-greeting.
 */
class ConversationMemory(private val maxTurns: Int = MAX_TURNS) {

    companion object {
        const val MAX_TURNS = 12
        const val DEFAULT_MAX_RECAP_CHARS = 1600

        val instance: ConversationMemory by lazy { ConversationMemory() }
    }

    private val turns = ArrayDeque<Turn>()

    @Synchronized
    fun addTurn(role: String, content: String): Boolean {
        val trimmedRole = role.trim()
        val trimmedContent = content.trim()
        if (trimmedContent.isEmpty()) return false

        while (turns.size >= maxTurns) {
            turns.pollFirst()
        }
        turns.addLast(Turn(trimmedRole, trimmedContent))
        return true
    }

    @Synchronized
    fun getRecentTurns(): List<Turn> {
        return turns.toList()
    }

    @Synchronized
    fun turnCount(): Int = turns.size

    @Synchronized
    fun clear() {
        turns.clear()
    }

    @Synchronized
    fun recap(maxChars: Int = DEFAULT_MAX_RECAP_CHARS): String {
        if (turns.isEmpty()) return ""

        val prefix = "[Prior Conversation Context — Resume without re-greeting]:\n"
        val turnList = turns.toList()

        // Build from the newest turns backwards to prioritize the most recent context
        val collectedLines = mutableListOf<String>()
        var currentLen = prefix.length

        for (i in turnList.indices.reversed()) {
            val turn = turnList[i]
            val line = "${turn.role}: ${turn.content}\n"
            if (currentLen + line.length > maxChars) {
                // If even the first line would exceed, truncate that single line
                if (collectedLines.isEmpty()) {
                    val available = maxChars - currentLen - 4
                    if (available > 10) {
                        collectedLines.add("${line.take(available)}...\n")
                    }
                }
                break
            }
            collectedLines.add(line)
            currentLen += line.length
        }

        if (collectedLines.isEmpty()) return ""

        val sb = StringBuilder(prefix)
        for (i in collectedLines.indices.reversed()) {
            sb.append(collectedLines[i])
        }

        return sb.toString().trimEnd()
    }
}
