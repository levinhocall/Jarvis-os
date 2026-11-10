package com.jarvis.assistant.automation.mission

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.security.MessageDigest

/** Stable identity for an invocation, without persisting message/contact text as its dedup key. */
object MissionActionIdentity {
    fun key(tool: String, parameters: Map<String, Any?>): String {
        var nodes = 0
        fun canonical(value: Any?, depth: Int): JsonElement {
            require(depth <= 16 && ++nodes <= 4096) { "Action arguments exceed identity bounds" }
            return when (value) {
                null -> JsonNull.INSTANCE
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(BigDecimal(value.toString()).stripTrailingZeros())
                is List<*> -> JsonArray().apply { value.forEach { add(canonical(it, depth + 1)) } }
                is Map<*, *> -> JsonObject().apply {
                    require(value.keys.all { it is String }) { "Action object keys must be strings" }
                    value.entries.sortedBy { it.key as String }.forEach { (name, entry) -> add(name as String, canonical(entry, depth + 1)) }
                }
                else -> throw IllegalArgumentException("Unsupported action argument type")
            }
        }
        val payload = JsonObject().apply {
            addProperty("tool", ToolRegistry.resolveToolName(tool))
            // Registry semantics treat a null optional top-level parameter as absent.
            add("arguments", canonical(parameters.filterValues { it != null }, 0))
        }.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return "mission-v1-sha256:" + digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    fun previousTimestamp(tool: String, parameters: Map<String, Any?>, history: Map<String, Long>): Long =
        maxOf(history[key(tool, parameters)] ?: 0L, history["$tool:$parameters"] ?: 0L)
}
