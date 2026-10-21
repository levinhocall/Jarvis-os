package com.jarvis.assistant.automation.intelligence

enum class DocumentFormat {
    PLAIN_TEXT,
    JSON,
    PDF,
    UNKNOWN
}

data class ExtractedEntity(
    val type: String, // "DATE", "PHONE", "URL", "ACTION_ITEM"
    val value: String,
    val sourceDocument: String,
    val confidence: Float = 1.0f
)

sealed class DocumentParseResult {
    data class Success(
        val sourceName: String,
        val format: DocumentFormat,
        val cleanContent: String,
        val extractedEntities: List<ExtractedEntity>,
        val isSanitized: Boolean,
        val promptInjectionDetected: Boolean = false,
        val byteSize: Long,
        val parseTimestamp: Long = System.currentTimeMillis()
    ) : DocumentParseResult()

    data class Failure(
        val sourceName: String,
        val reason: String,
        val isRecoverable: Boolean = false
    ) : DocumentParseResult()
}

data class GroundedSynthesizedAnswer(
    val query: String,
    val answer: String,
    val citations: List<String>,
    val extractedEntities: List<ExtractedEntity>,
    val isFullyGrounded: Boolean,
    val confidence: Float
)
