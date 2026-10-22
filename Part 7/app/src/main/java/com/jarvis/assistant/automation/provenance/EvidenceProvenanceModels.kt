package com.jarvis.assistant.automation.provenance

import java.util.UUID

enum class SourceAuthority(val baseWeight: Double) {
    OFFICIAL_SYSTEM(1.0),
    EXPLICIT_USER(0.95),
    TOOL_EXECUTION(0.90),
    SCREEN_CAPTURE(0.85),
    EXTERNAL_DOCUMENT(0.70),
    EXTERNAL_WEB(0.60),
    MODEL_INFERENCE(0.40)
}

enum class EpistemicStatus {
    VERIFIED_TRUTH,
    INFERRED_BELIEF,
    EXTERNAL_CLAIM,
    SYNTHETIC_SUMMARY,
    DISPUTED,
    SUPERSEDED
}

data class ProvenanceRecord(
    val id: String = UUID.randomUUID().toString(),
    val claimOrFact: String,
    val subject: String, // entity, key, or topic
    val sourceType: SourceAuthority,
    val sourceUri: String, // e.g. "sys://bluetooth", "screen://1729000", "user://prompt"
    val rawEvidenceExcerpt: String,
    val timestamp: Long = System.currentTimeMillis(),
    val epistemicStatus: EpistemicStatus,
    val confidenceScore: Double
)

data class ConflictResolutionResult(
    val winningRecord: ProvenanceRecord,
    val losingRecord: ProvenanceRecord,
    val resolutionReason: String
)

data class Citation(
    val index: Int,
    val claim: String,
    val sourceUri: String,
    val sourceType: SourceAuthority,
    val confidence: Double
)

data class GroundedSummary(
    val textWithCitations: String,
    val citations: List<Citation>
)
