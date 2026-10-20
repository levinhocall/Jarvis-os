package com.jarvis.assistant.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipInputStream

data class ProcessedAttachment(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val extractedText: String,
    val summary: String,
    val isTruncated: Boolean = false,
    val hasPromptInjection: Boolean = false,
    val injectionWarning: String? = null
)

object FileIntelligenceEngine {
    private const val TAG = "FileIntelligence"
    private const val MAX_TEXT_BYTES = 256 * 1024 // 256 KB text extraction limit to prevent OOM
    private const val MAX_SUMMARY_CHARS = 1200

    private val PROMPT_INJECTION_PATTERNS = listOf(
        Regex("(?i)\\bignore\\s+(?:all\\s+)?(?:previous|prior)\\s+instructions?\\b"),
        Regex("(?i)\\byou\\s+are\\s+now\\s+in\\s+(?:developer|unrestricted|god)\\s+mode\\b"),
        Regex("(?i)\\bsystem\\s+prompt\\s*:\\s*reveal\\b"),
        Regex("(?i)\\bdelete\\s+(?:all\\s+)?(?:data|database|files|photos)\\b"),
        Regex("(?i)\\bsend\\s+(?:all\\s+)?(?:sms|contacts|otp|passwords)\\s+to\\b")
    )

    /**
     * Inspects a scoped content URI, identifies format, extracts text safely,
     * checks for prompt injection, and generates a concise summary.
     */
    suspend fun processFile(context: Context, contentUri: Uri): ProcessedAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (fileName, fileSize) = queryMetadata(context, contentUri)
        val mimeType = resolver.getType(contentUri) ?: guessMimeType(fileName)

        Log.i(TAG, "Processing file: '$fileName', type: $mimeType, size: $fileSize bytes")

        val (rawText, truncated) = when {
            mimeType.startsWith("image/") -> {
                extractImageSummary(context, contentUri, fileName, fileSize)
            }
            mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true) -> {
                extractPdfText(context, contentUri)
            }
            mimeType.contains("wordprocessingml") || fileName.endsWith(".docx", ignoreCase = true) -> {
                extractDocxText(context, contentUri)
            }
            mimeType.startsWith("text/") || isReadableTextFile(fileName) -> {
                extractPlainText(context, contentUri)
            }
            else -> {
                Pair("Unsupported file format: '$mimeType'. File attached safely.", false)
            }
        }

        // Prompt injection inspection
        var hasInjection = false
        var injectionWarning: String? = null
        for (pattern in PROMPT_INJECTION_PATTERNS) {
            val match = pattern.find(rawText)
            if (match != null) {
                hasInjection = true
                injectionWarning = "Document contains untrusted instruction: '${match.value}'"
                Log.w(TAG, "Prompt injection in document $fileName: $injectionWarning")
                break
            }
        }

        // Summary generation
        val summary = generateSummary(fileName, mimeType, rawText, truncated, hasInjection)

        ProcessedAttachment(
            uri = contentUri.toString(),
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = fileSize,
            extractedText = rawText,
            summary = summary,
            isTruncated = truncated,
            hasPromptInjection = hasInjection,
            injectionWarning = injectionWarning
        )
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var name = "attachment"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}
        return Pair(name, size)
    }

    private fun extractPlainText(context: Context, uri: Uri): Pair<String, Boolean> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream))
                val sb = java.lang.StringBuilder()
                var bytesRead = 0
                var truncated = false
                var line = reader.readLine()
                while (line != null) {
                    val lineBytes = line.toByteArray().size
                    if (bytesRead + lineBytes > MAX_TEXT_BYTES) {
                        truncated = true
                        break
                    }
                    sb.appendLine(line)
                    bytesRead += lineBytes
                    line = reader.readLine()
                }
                Pair(sb.toString().trim(), truncated)
            } ?: Pair("Unable to open file stream", false)
        } catch (e: Exception) {
            Pair("Error reading plain text: ${e.message}", false)
        }
    }

    /**
     * Extracts text from DOCX (ZIP archive containing word/document.xml) with zero external dependencies.
     */
    private fun extractDocxText(context: Context, uri: Uri): Pair<String, Boolean> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val zis = ZipInputStream(stream)
                var entry = zis.nextEntry
                var docXmlText = ""
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val baos = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var len = zis.read(buffer)
                        while (len > 0) {
                            baos.write(buffer, 0, len)
                            len = zis.read(buffer)
                        }
                        docXmlText = baos.toString("UTF-8")
                        break
                    }
                    entry = zis.nextEntry
                }
                zis.close()

                if (docXmlText.isBlank()) {
                    return Pair("Empty DOCX document or unreadable body.", false)
                }

                // Strip XML tags and extract text paragraphs (<w:t> contents)
                val extracted = docXmlText
                    .replace(Regex("<w:p(?:\\s[^>]*)?>"), "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .trim()

                Pair(extracted.take(MAX_TEXT_BYTES), extracted.length > MAX_TEXT_BYTES)
            } ?: Pair("Could not read DOCX stream", false)
        } catch (e: Exception) {
            Pair("DOCX extraction error: ${e.message}", false)
        }
    }

    /**
     * Extracts text from PDF via stream inspection and page count via PdfRenderer.
     */
    private fun extractPdfText(context: Context, uri: Uri): Pair<String, Boolean> {
        val pageCountSummary = try {
            val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val renderer = PdfRenderer(pfd)
                val count = renderer.pageCount
                renderer.close()
                pfd.close()
                "PDF contains $count page(s).\n"
            } else ""
        } catch (_: Exception) {
            ""
        }

        // Stream text scanner
        val textSnippet = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(128 * 1024)
                val read = stream.read(buffer)
                if (read > 0) {
                    val rawStr = String(buffer, 0, read)
                    val textBlocks = mutableListOf<String>()
                    val matcher = Regex("\\(([^\\)]+)\\)\\s*Tj").findAll(rawStr)
                    for (m in matcher) {
                        textBlocks.add(m.groupValues[1])
                    }
                    if (textBlocks.isNotEmpty()) {
                        textBlocks.joinToString(" ")
                    } else {
                        "Binary PDF document format."
                    }
                } else "Empty PDF file"
            } ?: ""
        } catch (_: Exception) {
            ""
        }

        val fullText = "$pageCountSummary$textSnippet".trim()
        return Pair(fullText, false)
    }

    private fun extractImageSummary(context: Context, uri: Uri, fileName: String, fileSize: Long): Pair<String, Boolean> {
        var dimensions = ""
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
                dimensions = "${options.outWidth}x${options.outHeight}"
            }
        } catch (_: Exception) {}

        val info = "Image '$fileName' ($dimensions, ${fileSize / 1024} KB). Ready for multimodal vision inspection."
        return Pair(info, false)
    }

    private fun isReadableTextFile(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json") ||
            lower.endsWith(".csv") || lower.endsWith(".log") || lower.endsWith(".xml") || lower.endsWith(".kt")
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".csv") -> "text/csv"
            else -> "text/plain"
        }
    }

    private fun generateSummary(
        fileName: String,
        mimeType: String,
        text: String,
        truncated: Boolean,
        hasInjection: Boolean
    ): String {
        if (hasInjection) {
            return "File '$fileName' loaded with caution: untrusted prompt injection patterns detected and isolated."
        }
        val cleanPreview = text.replace(Regex("\\s+"), " ").trim()
        val truncatedNotice = if (truncated) " [Truncated to 256KB]" else ""
        return if (cleanPreview.length <= MAX_SUMMARY_CHARS) {
            "File '$fileName': $cleanPreview$truncatedNotice"
        } else {
            "File '$fileName' (${cleanPreview.length} chars): ${cleanPreview.take(MAX_SUMMARY_CHARS)}...$truncatedNotice"
        }
    }
}
