package com.jarvis.assistant.data.memory

import com.jarvis.assistant.data.chat.ChatMessageEntity
import com.jarvis.assistant.data.chat.ConversationRepository
import com.jarvis.assistant.data.memory.vector.ScoredMemory

data class MemoryAwarePrompt(
    val systemContext: String,
    val retrievedMemories: List<ScoredMemory>,
    val conversationHistory: List<ChatMessageEntity>,
    val fullAugmentedQuery: String
)

class MemoryAwareContextBuilder(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val maxMemoriesToInject: Int = 5,
    private val maxHistoryTurns: Int = 10,
    private val minMemoryScoreThreshold: Double = 0.35
) {
    /**
     * Builds a grounded, memory-augmented context for the AI model before response generation.
     */
    suspend fun buildPromptContext(
        sessionId: String,
        currentQuery: String
    ): MemoryAwarePrompt {
        // 1. Retrieve relevant memories via hybrid semantic vector search
        val scoredMemories = memoryRepository.searchMemoriesSemantic(currentQuery, limit = maxMemoriesToInject)
            .filter { it.score >= minMemoryScoreThreshold }

        // 2. Load conversation history for current session (strictly isolated)
        val pagedHistory = conversationRepository.getMessagesPaged(
            sessionId = sessionId,
            limit = maxHistoryTurns * 2,
            offset = 0
        ).reversed() // Chronological order

        // 3. Format Memory Context Block
        val systemContextBuilder = StringBuilder()
        if (scoredMemories.isNotEmpty()) {
            systemContextBuilder.append("[USER LONG-TERM MEMORY]\n")
            systemContextBuilder.append("The following verified facts and preferences were retrieved from the user's persistent memory. Use them to tailor your response:\n")

            for (scored in scoredMemories) {
                val mem = scored.memory
                val sourceRef = if (!mem.sourceConversationId.isNullOrBlank()) " [Source: ${mem.sourceConversationId}]" else ""
                systemContextBuilder.append("- (${mem.memoryType}) ${mem.value}$sourceRef\n")
            }
            systemContextBuilder.append("[END MEMORY CONTEXT]\n")
        }

        // 4. Construct Augmented Query
        val memoryHeader = if (systemContextBuilder.isNotEmpty()) systemContextBuilder.toString() + "\n" else ""
        val fullAugmented = "${memoryHeader}User query: $currentQuery"

        return MemoryAwarePrompt(
            systemContext = systemContextBuilder.toString().trim(),
            retrievedMemories = scoredMemories,
            conversationHistory = pagedHistory,
            fullAugmentedQuery = fullAugmented
        )
    }
}
