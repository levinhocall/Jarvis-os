package com.jarvis.assistant.forge

import com.google.gson.JsonParser

/**
 * Robust SSE parser for Google Gemini `streamGenerateContent?alt=sse` response streams.
 * Uses Gson for seamless cross-platform execution on both Android device runtime and JVM unit tests.
 */
object SseParser {

    private const val DONE_TOKEN = "[DONE]"

    fun isDone(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed == DONE_TOKEN || trimmed == "data: $DONE_TOKEN" || trimmed == "data:$DONE_TOKEN"
    }

    /**
     * Extracts text chunks from `candidates[0].content.parts[*].text`.
     */
    fun extractText(dataJson: String): String? {
        val cleaned = cleanDataPayload(dataJson) ?: return null
        return try {
            val jsonElement = JsonParser.parseString(cleaned)
            if (!jsonElement.isJsonObject) return null
            val root = jsonElement.asJsonObject

            val candidates = root.getAsJsonArray("candidates") ?: return null
            if (candidates.size() == 0) return null

            val firstCandidate = candidates.get(0).asJsonObject
            val content = firstCandidate.getAsJsonObject("content") ?: return null
            val parts = content.getAsJsonArray("parts") ?: return null

            val sb = StringBuilder()
            for (i in 0 until parts.size()) {
                val part = parts.get(i).asJsonObject
                if (part.has("text") && !part.get("text").isJsonNull) {
                    sb.append(part.get("text").asString)
                }
            }
            if (sb.isNotEmpty()) sb.toString() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts error message if the payload contains an error object.
     */
    fun extractError(dataJson: String): String? {
        val cleaned = cleanDataPayload(dataJson) ?: return null
        return try {
            val jsonElement = JsonParser.parseString(cleaned)
            if (!jsonElement.isJsonObject) return null
            val root = jsonElement.asJsonObject

            if (root.has("error") && root.get("error").isJsonObject) {
                val errorObj = root.getAsJsonObject("error")
                val message = if (errorObj.has("message") && !errorObj.get("message").isJsonNull) {
                    errorObj.get("message").asString
                } else ""
                val status = if (errorObj.has("status") && !errorObj.get("status").isJsonNull) {
                    errorObj.get("status").asString
                } else ""

                if (message.isNotEmpty()) message else status.ifEmpty { "Gemini API error" }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts finishReason from `candidates[0].finishReason`.
     */
    fun extractFinishReason(dataJson: String): String? {
        val cleaned = cleanDataPayload(dataJson) ?: return null
        return try {
            val jsonElement = JsonParser.parseString(cleaned)
            if (!jsonElement.isJsonObject) return null
            val root = jsonElement.asJsonObject

            val candidates = root.getAsJsonArray("candidates") ?: return null
            if (candidates.size() == 0) return null

            val firstCandidate = candidates.get(0).asJsonObject
            if (firstCandidate.has("finishReason") && !firstCandidate.get("finishReason").isJsonNull) {
                val reason = firstCandidate.get("finishReason").asString
                if (reason.isNotEmpty()) reason else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanDataPayload(line: String): String? {
        var trimmed = line.trim()
        if (trimmed.isEmpty() || isDone(trimmed)) return null
        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.substring(5).trim()
        }
        return if (trimmed.startsWith("{") && trimmed.endsWith("}")) trimmed else null
    }
}
