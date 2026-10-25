package com.jarvis.assistant.forge

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Detects progressive build stages, extracts semantic section headers, and computes
 * monotonic build progress from partial streaming HTML.
 */
object ForgeStageDetector {

    private val SECTION_TAG_REGEX = Regex("""<section\b([^>]*)>(.*?)(?=</section>|<section\b|$)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HEADING_REGEX = Regex("""<h[1-4]\b[^>]*>(.*?)</h[1-4]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ID_ATTR_REGEX = Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ARIA_LABEL_REGEX = Regex("""\baria-label\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val STRIP_TAGS_REGEX = Regex("""<[^>]+>""")

    /**
     * Determines current build stage based on markup signatures.
     * Guaranteed monotonic progression: passing previousStage ensures the stage never regresses.
     */
    fun detect(partialHtml: String, finishReason: String? = null, previousStage: ForgeStage = ForgeStage.BLUEPRINT): ForgeStage {
        val lower = partialHtml.lowercase(Locale.ROOT)
        var detected = ForgeStage.BLUEPRINT

        if (finishReason.equals("STOP", ignoreCase = true) || lower.contains("</html>")) {
            detected = ForgeStage.FINALIZING
        } else if (hasVisualsMarkers(lower)) {
            detected = ForgeStage.VISUALS
        } else if (hasMotionMarkers(lower)) {
            detected = ForgeStage.MOTION
        } else if (hasStylingMarkers(lower)) {
            detected = ForgeStage.STYLING
        } else if (hasStructureMarkers(lower)) {
            detected = ForgeStage.STRUCTURE
        }

        // Never regress backwards
        return if (detected.index >= previousStage.index) detected else previousStage
    }

    private fun hasStructureMarkers(lower: String): Boolean {
        val hasBody = lower.contains("<body")
        val hasSemanticTag = lower.contains("<header") ||
                lower.contains("<nav") ||
                lower.contains("<section") ||
                lower.contains("<main")
        return hasBody && hasSemanticTag
    }

    private fun hasStylingMarkers(lower: String): Boolean {
        return lower.contains("<style") ||
                lower.contains("cdn.tailwindcss.com") ||
                lower.contains("tailwindcss") ||
                lower.contains("--tw-") ||
                lower.contains("var(--")
    }

    private fun hasMotionMarkers(lower: String): Boolean {
        return lower.contains("gsap") ||
                lower.contains("scrolltrigger") ||
                lower.contains("@keyframes") ||
                lower.contains("intersectionobserver")
    }

    private fun hasVisualsMarkers(lower: String): Boolean {
        return lower.contains("<canvas") ||
                lower.contains("three.min.js") ||
                lower.contains("three.module") ||
                lower.contains("threejs") ||
                lower.contains("<img")
    }

    /**
     * Extracts readable section names from `<section>` elements via id, aria-label, or first heading.
     */
    fun extractSections(html: String): List<String> {
        val results = mutableListOf<String>()
        val matches = SECTION_TAG_REGEX.findAll(html)

        for (match in matches) {
            val attributes = match.groupValues[1]
            val body = match.groupValues[2]

            // 1. Try aria-label
            val ariaMatch = ARIA_LABEL_REGEX.find(attributes)
            if (ariaMatch != null && ariaMatch.groupValues[1].isNotBlank()) {
                results.add(cleanName(ariaMatch.groupValues[1]))
                continue
            }

            // 2. Try first heading
            val headingMatch = HEADING_REGEX.find(body)
            if (headingMatch != null) {
                val text = STRIP_TAGS_REGEX.replace(headingMatch.groupValues[1], "").trim()
                if (text.isNotBlank()) {
                    results.add(cleanName(text))
                    continue
                }
            }

            // 3. Try id
            val idMatch = ID_ATTR_REGEX.find(attributes)
            if (idMatch != null && idMatch.groupValues[1].isNotBlank()) {
                val rawId = idMatch.groupValues[1].replace('-', ' ').replace('_', ' ')
                results.add(capitalizeWords(cleanName(rawId)))
                continue
            }

            results.add("Section ${results.size + 1}")
        }

        return results.distinct().take(10)
    }

    private fun cleanName(raw: String): String {
        return raw.trim()
            .take(24)
            .replace(Regex("""\s+"""), " ")
    }

    private fun capitalizeWords(raw: String): String {
        return raw.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    /**
     * Computes monotonic progress percentage [0.0f .. 1.0f] combining stage baseline
     * and streamed byte density.
     */
    fun calculateProgress(stage: ForgeStage, byteLength: Int, previousProgress: Float = 0f): Float {
        if (stage == ForgeStage.FINALIZING) return 1.0f

        val stageBaseline = when (stage) {
            ForgeStage.BLUEPRINT -> 0.10f
            ForgeStage.STRUCTURE -> 0.30f
            ForgeStage.STYLING -> 0.50f
            ForgeStage.MOTION -> 0.70f
            ForgeStage.VISUALS -> 0.88f
            ForgeStage.FINALIZING -> 1.00f
        }

        // Expected full size roughly 16KB
        val streamRatio = min(1.0f, byteLength / 16384f) * 0.10f
        val computed = min(0.98f, stageBaseline + streamRatio)

        return max(previousProgress, computed)
    }
}
