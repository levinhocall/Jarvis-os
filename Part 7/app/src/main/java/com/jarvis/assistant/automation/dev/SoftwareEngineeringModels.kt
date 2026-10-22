package com.jarvis.assistant.automation.dev

import java.util.UUID

enum class SymbolKind {
    CLASS,
    FUNCTION,
    INTERFACE,
    PROPERTY,
    UNKNOWN
}

data class CodeSymbol(
    val name: String,
    val kind: SymbolKind,
    val filePath: String,
    val lineNumber: Int,
    val signature: String? = null
)

data class PatchHunk(
    val targetFilePath: String,
    val originalSnippet: String,
    val replacementSnippet: String
)

data class MultiFilePatch(
    val patchId: String = UUID.randomUUID().toString(),
    val description: String,
    val hunks: List<PatchHunk>,
    val authorNotes: String = ""
)

sealed class PatchApplicationResult {
    data class Success(
        val patchId: String,
        val modifiedFiles: List<String>,
        val backups: Map<String, String> // filePath to original content
    ) : PatchApplicationResult()

    data class Failed(
        val patchId: String,
        val failedFile: String,
        val reason: String,
        val rolledBack: Boolean
    ) : PatchApplicationResult()

    data class RejectedBoundaryViolation(
        val targetPath: String,
        val reason: String
    ) : PatchApplicationResult()
}

data class GitOperationRisk(
    val command: String,
    val isDestructive: Boolean,
    val requiresExplicitApproval: Boolean,
    val warningMessage: String? = null
)

data class CodeExplanation(
    val technicalSummary: String,
    val plainEnglishSummary: String,
    val affectedFiles: List<String>,
    val riskLevel: String // LOW, MEDIUM, HIGH
)
