package com.jarvis.assistant.automation.dev

import java.io.File

class SoftwareEngineeringAgent {

    /**
     * Audits Git operations for destructive commands.
     */
    fun auditGitOperation(command: String): GitOperationRisk {
        val trimmed = command.trim()

        val destructivePatterns = listOf(
            Regex("""\bgit\s+push\b.*(\s+-f\b|\s+--force\b|\+)""", RegexOption.IGNORE_CASE) to "Force push rewrites remote history and can destroy remote branches.",
            Regex("""\bgit\s+branch\b.*(\s+-D\b|\s+--delete\s+--force\b)""") to "Force-deleting a branch can permanently delete unmerged commits.",
            Regex("""\bgit\s+reset\b.*\s+--hard\b""", RegexOption.IGNORE_CASE) to "Hard reset discards uncommitted and unpushed changes permanently.",
            Regex("""\bgit\s+clean\b.*(-[a-zA-Z]*f|--force)""", RegexOption.IGNORE_CASE) to "Git clean with force permanently deletes untracked files."
        )

        for ((pattern, reason) in destructivePatterns) {
            if (pattern.containsMatchIn(trimmed)) {
                return GitOperationRisk(
                    command = trimmed,
                    isDestructive = true,
                    requiresExplicitApproval = true,
                    warningMessage = reason
                )
            }
        }

        return GitOperationRisk(
            command = trimmed,
            isDestructive = false,
            requiresExplicitApproval = false
        )
    }

    /**
     * Extracts code symbols (classes, interfaces, functions, properties) from source code.
     */
    fun extractSymbols(filePath: String, content: String): List<CodeSymbol> {
        val symbols = mutableListOf<CodeSymbol>()
        val lines = content.lines()

        val classRegex = Regex("""^\s*(?:(?:public|private|internal|protected|open|abstract|data|sealed)\s+)*(class|interface)\s+([A-Za-z0-9_]+)""")
        val funRegex = Regex("""^\s*(?:(?:public|private|internal|protected|open|override|suspend)\s+)*fun\s+([A-Za-z0-9_]+)""")
        val propRegex = Regex("""^\s*(?:(?:public|private|internal|protected|override)\s+)*(val|var)\s+([A-Za-z0-9_]+)""")

        for ((index, line) in lines.withIndex()) {
            val lineNumber = index + 1

            classRegex.find(line)?.let { match ->
                val type = match.groupValues[1]
                val name = match.groupValues[2]
                val kind = if (type == "interface") SymbolKind.INTERFACE else SymbolKind.CLASS
                symbols.add(CodeSymbol(name = name, kind = kind, filePath = filePath, lineNumber = lineNumber, signature = line.trim()))
                return@let
            }

            funRegex.find(line)?.let { match ->
                val name = match.groupValues[1]
                symbols.add(CodeSymbol(name = name, kind = SymbolKind.FUNCTION, filePath = filePath, lineNumber = lineNumber, signature = line.trim()))
                return@let
            }

            propRegex.find(line)?.let { match ->
                val name = match.groupValues[2]
                symbols.add(CodeSymbol(name = name, kind = SymbolKind.PROPERTY, filePath = filePath, lineNumber = lineNumber, signature = line.trim()))
            }
        }

        return symbols
    }

    /**
     * Validates and applies a multi-file patch atomically with boundary checks and rollback support.
     */
    fun applyMultiFilePatch(
        workspaceRoot: String,
        fileContents: MutableMap<String, String>,
        patch: MultiFilePatch
    ): PatchApplicationResult {
        val normalizedRoot = File(workspaceRoot).canonicalPath

        // 1. Boundary & Path Traversal Check
        for (hunk in patch.hunks) {
            val resolvedFile = File(normalizedRoot, hunk.targetFilePath)
            val canonicalTarget = resolvedFile.canonicalPath

            if (!canonicalTarget.startsWith(normalizedRoot)) {
                return PatchApplicationResult.RejectedBoundaryViolation(
                    targetPath = hunk.targetFilePath,
                    reason = "Target path traverses outside workspace root '$normalizedRoot'"
                )
            }

            if (isProtectedSystemPath(canonicalTarget)) {
                return PatchApplicationResult.RejectedBoundaryViolation(
                    targetPath = hunk.targetFilePath,
                    reason = "Target path is a protected system directory"
                )
            }
        }

        // 2. Pre-validation: ensure all originalSnippets exist in targets
        val backups = mutableMapOf<String, String>()
        for (hunk in patch.hunks) {
            val existingContent = fileContents[hunk.targetFilePath]
            if (existingContent == null) {
                return PatchApplicationResult.Failed(
                    patchId = patch.patchId,
                    failedFile = hunk.targetFilePath,
                    reason = "Target file not found in workspace",
                    rolledBack = true
                )
            }

            if (!existingContent.contains(hunk.originalSnippet)) {
                return PatchApplicationResult.Failed(
                    patchId = patch.patchId,
                    failedFile = hunk.targetFilePath,
                    reason = "Original snippet not found in target file (drift detected)",
                    rolledBack = true
                )
            }

            if (!backups.containsKey(hunk.targetFilePath)) {
                backups[hunk.targetFilePath] = existingContent
            }
        }

        // 3. Atomic application
        val modifiedFiles = mutableListOf<String>()
        try {
            for (hunk in patch.hunks) {
                val current = fileContents[hunk.targetFilePath]!!
                val updated = current.replace(hunk.originalSnippet, hunk.replacementSnippet)
                fileContents[hunk.targetFilePath] = updated
                if (!modifiedFiles.contains(hunk.targetFilePath)) {
                    modifiedFiles.add(hunk.targetFilePath)
                }
            }
        } catch (e: Exception) {
            // Rollback everything from backup
            for ((path, backupContent) in backups) {
                fileContents[path] = backupContent
            }
            return PatchApplicationResult.Failed(
                patchId = patch.patchId,
                failedFile = modifiedFiles.lastOrNull() ?: "unknown",
                reason = "Exception during modification: ${e.message}",
                rolledBack = true
            )
        }

        return PatchApplicationResult.Success(
            patchId = patch.patchId,
            modifiedFiles = modifiedFiles,
            backups = backups
        )
    }

    /**
     * Generates a plain-English explanation for non-developers.
     */
    fun explainPatch(patch: MultiFilePatch): CodeExplanation {
        val affected = patch.hunks.map { it.targetFilePath }.distinct()
        val plainSummary = "This change updates ${affected.size} file(s) to: ${patch.description}. " +
                "It modifies specific code sections safely without altering unrelated settings."

        val risk = if (affected.size > 3 || patch.hunks.size > 5) "MEDIUM" else "LOW"

        return CodeExplanation(
            technicalSummary = "MultiFilePatch '${patch.patchId}': ${patch.hunks.size} hunk(s) across files: ${affected.joinToString()}",
            plainEnglishSummary = plainSummary,
            affectedFiles = affected,
            riskLevel = risk
        )
    }

    private fun isProtectedSystemPath(path: String): Boolean {
        val sensitivePrefixes = listOf("/proc", "/sys", "/dev", "/system", "/etc", "/data/system")
        return sensitivePrefixes.any { path.startsWith(it) }
    }
}
