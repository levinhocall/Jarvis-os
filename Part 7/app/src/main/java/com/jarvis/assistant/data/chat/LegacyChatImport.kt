package com.jarvis.assistant.data.chat

import com.google.gson.JsonParser
import com.jarvis.assistant.data.model.ChatTurn
import java.util.UUID

/** Pure import planning. Absent IDs/timestamps get deterministic values, never random defaults. */
internal object LegacyChatImport {
    data class Plan(val session: ChatSessionEntity, val messages: List<ChatMessageEntity>)
    private const val SESSION_ID = "jarvis-legacy-chat-import"

    fun plan(raw: String): Plan {
        val root = JsonParser.parseString(raw)
        require(root.isJsonArray) { "Legacy chat is not an array; source retained." }
        val turns = root.asJsonArray.mapIndexed { index, element ->
            require(element.isJsonObject) { "Invalid legacy chat turn; source retained." }
            val item = element.asJsonObject
            fun text(key: String): String {
                val value = item.get(key) ?: return ""
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "Invalid legacy chat field; source retained."
                }
                return value.asString
            }
            val user = text("userTranscript")
            val assistant = text("jarvisResponse")
            require(item.has("userTranscript") || item.has("jarvisResponse")) {
                "Unknown legacy chat record; source retained."
            }
            val id = text("id").ifBlank {
                stable("legacy-missing-id:$index:$user\u0000$assistant")
            }
            val time = item.get("timestamp")?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "Invalid legacy timestamp; source retained." }
                it.asBigDecimal.longValueExact().also { value -> require(value >= 0 && value < Long.MAX_VALUE) }
            } ?: index * 2L
            ChatTurn(id, user, assistant, time)
        }
        val messages = messages(SESSION_ID, turns)
        val first = messages.minOfOrNull { it.timestamp } ?: 0L
        val last = messages.maxOfOrNull { it.timestamp } ?: 0L
        return Plan(ChatSessionEntity(SESSION_ID, "Imported conversation", first, last), messages)
    }

    fun messages(sessionId: String, turns: List<ChatTurn>): List<ChatMessageEntity> {
        val result = LinkedHashMap<String, ChatMessageEntity>()
        turns.forEach { turn ->
            listOf("user" to turn.userTranscript, "assistant" to turn.jarvisResponse).forEach { (role, text) ->
                val content = text.trim()
                if (content.isNotBlank()) {
                    val id = stable("old:${turn.id}:$role") // Same IDs as the pre-existing Room importer.
                    val message = ChatMessageEntity(
                        id = id, sessionId = sessionId, role = role.uppercase(java.util.Locale.ROOT),
                        content = content, timestamp = turn.timestamp + if (role == "assistant") 1L else 0L
                    )
                    val previous = result.put(id, message)
                    require(previous == null || previous == message) { "Conflicting legacy turn IDs; source retained." }
                }
            }
        }
        return result.values.toList()
    }

    private fun stable(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
}
