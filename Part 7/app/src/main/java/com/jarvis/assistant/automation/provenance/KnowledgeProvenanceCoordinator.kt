package com.jarvis.assistant.automation.provenance

import java.util.concurrent.ConcurrentHashMap

class KnowledgeProvenanceCoordinator {

    private val knowledgeBase = ConcurrentHashMap<String, MutableList<ProvenanceRecord>>()

    @Synchronized
    fun recordEvidence(
        claim: String,
        subject: String,
        source: SourceAuthority,
        uri: String,
        excerpt: String,
        timestamp: Long = System.currentTimeMillis()
    ): ProvenanceRecord {
        val confidence = computeConfidence(source, timestamp)
        val initialStatus = when (source) {
            SourceAuthority.OFFICIAL_SYSTEM,
            SourceAuthority.EXPLICIT_USER,
            SourceAuthority.TOOL_EXECUTION,
            SourceAuthority.SCREEN_CAPTURE -> EpistemicStatus.VERIFIED_TRUTH
            SourceAuthority.EXTERNAL_DOCUMENT,
            SourceAuthority.EXTERNAL_WEB -> EpistemicStatus.EXTERNAL_CLAIM
            SourceAuthority.MODEL_INFERENCE -> EpistemicStatus.INFERRED_BELIEF
        }

        val candidate = ProvenanceRecord(
            claimOrFact = claim.trim(),
            subject = subject.trim().lowercase(),
            sourceType = source,
            sourceUri = uri.trim(),
            rawEvidenceExcerpt = excerpt.trim(),
            timestamp = timestamp,
            epistemicStatus = initialStatus,
            confidenceScore = confidence
        )

        val subjectRecords = knowledgeBase.getOrPut(candidate.subject) { mutableListOf() }
        val conflicting = subjectRecords.firstOrNull {
            it.epistemicStatus != EpistemicStatus.SUPERSEDED &&
            it.claimOrFact.lowercase() != candidate.claimOrFact.lowercase()
        }

        if (conflicting != null) {
            val resolution = resolveConflict(conflicting, candidate)
            subjectRecords.remove(conflicting)
            subjectRecords.add(resolution.winningRecord)
            subjectRecords.add(resolution.losingRecord)
            return resolution.winningRecord
        } else {
            subjectRecords.add(candidate)
            return candidate
        }
    }

    @Synchronized
    fun resolveConflict(
        existing: ProvenanceRecord,
        candidate: ProvenanceRecord
    ): ConflictResolutionResult {
        val existingWeight = existing.sourceType.baseWeight
        val candidateWeight = candidate.sourceType.baseWeight

        return when {
            candidateWeight > existingWeight -> {
                val winning = candidate.copy(epistemicStatus = candidate.epistemicStatus)
                val losing = existing.copy(epistemicStatus = EpistemicStatus.SUPERSEDED)
                ConflictResolutionResult(
                    winningRecord = winning,
                    losingRecord = losing,
                    resolutionReason = "Candidate has higher source authority (${candidate.sourceType} vs ${existing.sourceType})"
                )
            }
            candidateWeight < existingWeight -> {
                val winning = existing
                val losing = candidate.copy(epistemicStatus = EpistemicStatus.SUPERSEDED)
                ConflictResolutionResult(
                    winningRecord = winning,
                    losingRecord = losing,
                    resolutionReason = "Existing record has higher source authority (${existing.sourceType} vs ${candidate.sourceType})"
                )
            }
            else -> {
                // Same authority weight: freshness determines winner
                if (candidate.timestamp >= existing.timestamp) {
                    val winning = candidate
                    val losing = existing.copy(epistemicStatus = EpistemicStatus.SUPERSEDED)
                    ConflictResolutionResult(
                        winningRecord = winning,
                        losingRecord = losing,
                        resolutionReason = "Candidate is newer with identical authority (${candidate.sourceType})"
                    )
                } else {
                    val winning = existing
                    val losing = candidate.copy(epistemicStatus = EpistemicStatus.SUPERSEDED)
                    ConflictResolutionResult(
                        winningRecord = winning,
                        losingRecord = losing,
                        resolutionReason = "Existing record is newer with identical authority (${existing.sourceType})"
                    )
                }
            }
        }
    }

    fun isAdmissibleToPersistentMemory(record: ProvenanceRecord): Boolean {
        if (record.epistemicStatus == EpistemicStatus.SUPERSEDED || record.epistemicStatus == EpistemicStatus.DISPUTED) {
            return false
        }
        if (record.sourceType == SourceAuthority.MODEL_INFERENCE) {
            return false // Synthetic hallucination guard: model inferences cannot enter persistent memory without grounding
        }
        return record.confidenceScore >= 0.70
    }

    fun getRecordsForSubject(subject: String): List<ProvenanceRecord> {
        return knowledgeBase[subject.trim().lowercase()] ?: emptyList()
    }

    fun generateGroundedSummary(claims: List<String>): GroundedSummary {
        val citations = mutableListOf<Citation>()
        val textBuilder = StringBuilder()

        for (claim in claims) {
            val matchingRecord = knowledgeBase.values.flatten().firstOrNull {
                it.epistemicStatus != EpistemicStatus.SUPERSEDED &&
                (it.claimOrFact.contains(claim, ignoreCase = true) || claim.contains(it.claimOrFact, ignoreCase = true))
            }

            if (matchingRecord != null) {
                val citationIndex = citations.size + 1
                citations.add(
                    Citation(
                        index = citationIndex,
                        claim = matchingRecord.claimOrFact,
                        sourceUri = matchingRecord.sourceUri,
                        sourceType = matchingRecord.sourceType,
                        confidence = matchingRecord.confidenceScore
                    )
                )
                textBuilder.append("$claim [$citationIndex] ")
            } else {
                textBuilder.append("$claim [unverified] ")
            }
        }

        return GroundedSummary(
            textWithCitations = textBuilder.toString().trim(),
            citations = citations
        )
    }

    private fun computeConfidence(source: SourceAuthority, timestamp: Long): Double {
        val ageMs = System.currentTimeMillis() - timestamp
        val decay = if (ageMs > 86_400_000L * 30) 0.05 else 0.0 // Slight decay after 30 days
        return (source.baseWeight - decay).coerceIn(0.1, 1.0)
    }
}
