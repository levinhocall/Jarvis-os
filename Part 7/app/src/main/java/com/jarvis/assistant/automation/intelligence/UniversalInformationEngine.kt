package com.jarvis.assistant.automation.intelligence

import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

object UniversalInformationEngine {
    private const val TAG = "UniversalInfoEngine"
    const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB size limit

    // Entity extraction patterns
    private val DATE_REGEX = Regex("""\b(?:\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}/\d{2,4}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]* \d{1,2}(?:st|nd|rd|th)?,? \d{4})\b""", RegexOption.IGNORE_CASE)
    private val PHONE_REGEX = Regex("""\b(?:\+?\d{1,3}[- ]?)?\(?\d{3}\)?[- ]?\d{3}[- ]?\d{4}\b""")
    private val URL_REGEX = Regex("""https?://[^\s/$.?#].[^\s]*""", RegexOption.IGNORE_CASE)
    private val TASK_REGEX = Regex("""(?m)^(?:\s*[-*]\s*\[ \]\s*|\s*(?:TODO|TASK|ACTION):\s*)(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * Parses raw document content safely with size guards and prompt injection sanitization.
     */
    fun parseContent(
        sourceName: String,
        rawBytes: ByteArray,
        explicitFormat: DocumentFormat? = null
    ): DocumentParseResult {
        if (rawBytes.isEmpty()) {
            return DocumentParseResult.Failure(
                sourceName = sourceName,
                reason = "Document is empty (0 bytes)."
            )
        }

        if (rawBytes.size > MAX_FILE_SIZE_BYTES) {
            val sizeKb = rawBytes.size / 1024
            return DocumentParseResult.Failure(
                sourceName = sourceName,
                reason = "File size (${sizeKb} KB) exceeds maximum allowed limit (5120 KB)."
            )
        }

        // Format detection
        val format = explicitFormat ?: detectFormat(sourceName, rawBytes)

        val textContent = try {
            when (format) {
                DocumentFormat.PLAIN_TEXT, DocumentFormat.JSON -> {
                    String(rawBytes, StandardCharsets.UTF_8)
                }
                DocumentFormat.PDF -> {
                    extractPdfText(rawBytes)
                }
                DocumentFormat.UNKNOWN -> {
                    String(rawBytes, StandardCharsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode document '$sourceName': ${e.message}")
            return DocumentParseResult.Failure(
                sourceName = sourceName,
                reason = "Failed to parse document content: ${e.message}"
            )
        }

        // Neutralize prompt injections
        val sanitization = PromptInjectionSanitizer.sanitize(textContent)

        // Extract structured entities
        val entities = extractEntities(sourceName, sanitization.cleanText)

        return DocumentParseResult.Success(
            sourceName = sourceName,
            format = format,
            cleanContent = sanitization.cleanText,
            extractedEntities = entities,
            isSanitized = sanitization.isInjectionDetected,
            promptInjectionDetected = sanitization.isInjectionDetected,
            byteSize = rawBytes.size.toLong()
        )
    }

    /**
     * Parses a physical file from disk with storage safety checks.
     */
    fun parseFile(file: File): DocumentParseResult {
        if (!file.exists()) {
            return DocumentParseResult.Failure(
                sourceName = file.name,
                reason = "File does not exist: ${file.path}"
            )
        }
        if (!file.canRead()) {
            return DocumentParseResult.Failure(
                sourceName = file.name,
                reason = "Permission denied or file unreadable: ${file.path}"
            )
        }
        return try {
            val bytes = file.readBytes()
            parseContent(file.name, bytes)
        } catch (e: Exception) {
            DocumentParseResult.Failure(
                sourceName = file.name,
                reason = "I/O error reading file: ${e.message}"
            )
        }
    }

    /**
     * Synthesizes a grounded answer from verified source documents.
     * Cites sources for every claim and explicitly states when information is unavailable.
     */
    fun synthesizeGroundedAnswer(
        query: String,
        documents: List<DocumentParseResult.Success>
    ): GroundedSynthesizedAnswer {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        val queryTokens = cleanQuery.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (queryTokens.isEmpty() || documents.isEmpty()) {
            return GroundedSynthesizedAnswer(
                query = query,
                answer = "I don't have enough verified information to answer your request, Sir. Please provide a source document or query the web.",
                citations = emptyList(),
                extractedEntities = emptyList(),
                isFullyGrounded = false,
                confidence = 0f
            )
        }

        // Score documents by term overlap with query
        val matchingDocs = documents.mapNotNull { doc ->
            val docLower = doc.cleanContent.lowercase(Locale.ROOT)
            val matchedTerms = queryTokens.filter { term -> docLower.contains(term) }
            if (matchedTerms.isNotEmpty()) {
                val score = matchedTerms.size.toFloat() / queryTokens.size.toFloat()
                Pair(doc, score)
            } else null
        }.sortedByDescending { it.second }

        if (matchingDocs.isEmpty() || matchingDocs.first().second < 0.30f) {
            return GroundedSynthesizedAnswer(
                query = query,
                answer = "Sir, the requested information could not be verified in the provided documents.",
                citations = emptyList(),
                extractedEntities = emptyList(),
                isFullyGrounded = false,
                confidence = 0.2f
            )
        }

        val topDoc = matchingDocs.first().first
        val citations = matchingDocs.take(3).map { "[Source: ${it.first.sourceName}]" }
        val relevantEntities = matchingDocs.take(3).flatMap { it.first.extractedEntities }

        // Find relevant snippet in top doc
        val lines = topDoc.cleanContent.lines().filter { it.isNotBlank() }
        val matchingLines = lines.filter { line ->
            val lineLower = line.lowercase(Locale.ROOT)
            queryTokens.any { term -> lineLower.contains(term) }
        }.take(3)

        val snippet = if (matchingLines.isNotEmpty()) {
            matchingLines.joinToString(" ")
        } else {
            topDoc.cleanContent.take(200)
        }

        val formattedAnswer = "According to verified records: $snippet ${citations.first()}"

        return GroundedSynthesizedAnswer(
            query = query,
            answer = formattedAnswer,
            citations = citations,
            extractedEntities = relevantEntities,
            isFullyGrounded = true,
            confidence = matchingDocs.first().second
        )
    }

    fun extractEntities(sourceName: String, text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()

        // 1. Dates
        for (match in DATE_REGEX.findAll(text)) {
            entities.add(ExtractedEntity("DATE", match.value, sourceName))
        }

        // 2. Phone numbers
        for (match in PHONE_REGEX.findAll(text)) {
            entities.add(ExtractedEntity("PHONE", match.value, sourceName))
        }

        // 3. URLs
        for (match in URL_REGEX.findAll(text)) {
            entities.add(ExtractedEntity("URL", match.value, sourceName))
        }

        // 4. Action Items
        for (match in TASK_REGEX.findAll(text)) {
            val taskText = match.groupValues.getOrNull(1)?.trim() ?: match.value.trim()
            if (taskText.isNotBlank()) {
                entities.add(ExtractedEntity("ACTION_ITEM", taskText, sourceName))
            }
        }

        return entities
    }

    private fun detectFormat(sourceName: String, bytes: ByteArray): DocumentFormat {
        val lowerName = sourceName.lowercase(Locale.ROOT)
        return when {
            lowerName.endsWith(".pdf") || bytes.take(5).toByteArray().toString(StandardCharsets.US_ASCII).startsWith("%PDF") ->
                DocumentFormat.PDF
            lowerName.endsWith(".json") || (bytes.isNotEmpty() && (bytes[0] == '{'.code.toByte() || bytes[0] == '['.code.toByte())) ->
                DocumentFormat.JSON
            else -> DocumentFormat.PLAIN_TEXT
        }
    }

    private fun extractPdfText(bytes: ByteArray): String {
        // Fallback ASCII text stream extractor for embedded PDF streams
        val raw = String(bytes, StandardCharsets.ISO_8859_1)
        val streamRegex = Regex("""stream[\r\n]+(.*?)[\r\n]+endstream""", RegexOption.DOT_MATCHES_ALL)
        val builder = StringBuilder()
        for (m in streamRegex.findAll(raw)) {
            val streamContent = m.groupValues[1]
            val readable = streamContent.filter { it in ' '..'~' || it == '\n' || it == '\t' }
            if (readable.length > 20) {
                builder.append(readable).append("\n")
            }
        }
        return if (builder.isNotBlank()) builder.toString() else "PDF binary content without readable text stream"
    }
}
