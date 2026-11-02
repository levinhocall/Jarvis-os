package com.jarvis.assistant.data.chat

import com.jarvis.assistant.data.model.ChatTurn

/** Maps the canonical Room transcript into the legacy UI turn shape. */
object ConversationTurnMapper {
    fun toTurns(messages: List<ChatMessageEntity>): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        messages.sortedBy { it.timestamp }.forEach { message ->
            when (message.role.uppercase()) {
                "USER" -> turns.add(
                    ChatTurn(
                        id = message.id,
                        userTranscript = message.content,
                        jarvisResponse = "",
                        timestamp = message.timestamp
                    )
                )
                "ASSISTANT" -> {
                    val lastIndex = turns.lastIndex
                    if (lastIndex >= 0 && turns[lastIndex].jarvisResponse.isBlank()) {
                        turns[lastIndex] = turns[lastIndex].copy(
                            jarvisResponse = message.content,
                            timestamp = maxOf(turns[lastIndex].timestamp, message.timestamp)
                        )
                    } else {
                        turns.add(
                            ChatTurn(
                                id = message.id,
                                userTranscript = "",
                                jarvisResponse = message.content,
                                timestamp = message.timestamp
                            )
                        )
                    }
                }
            }
        }
        return turns
    }
}
