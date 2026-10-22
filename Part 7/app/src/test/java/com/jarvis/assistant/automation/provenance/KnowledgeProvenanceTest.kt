package com.jarvis.assistant.automation.provenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KnowledgeProvenanceTest {

    private lateinit var coordinator: KnowledgeProvenanceCoordinator

    @Before
    fun setUp() {
        coordinator = KnowledgeProvenanceCoordinator()
    }

    @Test
    fun `official system source produces verified truth with high confidence`() {
        val record = coordinator.recordEvidence(
            claim = "Bluetooth is enabled",
            subject = "bluetooth_status",
            source = SourceAuthority.OFFICIAL_SYSTEM,
            uri = "sys://settings/bluetooth",
            excerpt = "BluetoothAdapter.STATE_ON"
        )

        assertEquals(EpistemicStatus.VERIFIED_TRUTH, record.epistemicStatus)
        assertTrue(record.confidenceScore >= 0.95)
        assertTrue(coordinator.isAdmissibleToPersistentMemory(record))
    }

    @Test
    fun `higher authority source wins over lower authority in conflict`() {
        // First record from web
        val webRecord = coordinator.recordEvidence(
            claim = "Store closes at 9 PM",
            subject = "store_hours",
            source = SourceAuthority.EXTERNAL_WEB,
            uri = "https://example.com/hours",
            excerpt = "Hours: 9am - 9pm"
        )
        assertEquals(EpistemicStatus.EXTERNAL_CLAIM, webRecord.epistemicStatus)

        // Then official system or user statement arrives stating 10 PM
        val userRecord = coordinator.recordEvidence(
            claim = "Store closes at 10 PM",
            subject = "store_hours",
            source = SourceAuthority.EXPLICIT_USER,
            uri = "user://prompt",
            excerpt = "I work there, we close at 10 PM tonight"
        )

        val subjectRecords = coordinator.getRecordsForSubject("store_hours")
        val activeRecord = subjectRecords.first { it.epistemicStatus != EpistemicStatus.SUPERSEDED }
        val supersededRecord = subjectRecords.first { it.epistemicStatus == EpistemicStatus.SUPERSEDED }

        assertEquals("Store closes at 10 PM", activeRecord.claimOrFact)
        assertEquals("Store closes at 9 PM", supersededRecord.claimOrFact)
    }

    @Test
    fun `equal authority conflict resolved by freshness`() {
        val older = coordinator.recordEvidence(
            claim = "Battery level 80%",
            subject = "battery",
            source = SourceAuthority.SCREEN_CAPTURE,
            uri = "screen://1000",
            excerpt = "80%",
            timestamp = 1000L
        )

        val newer = coordinator.recordEvidence(
            claim = "Battery level 75%",
            subject = "battery",
            source = SourceAuthority.SCREEN_CAPTURE,
            uri = "screen://2000",
            excerpt = "75%",
            timestamp = 2000L
        )

        val records = coordinator.getRecordsForSubject("battery")
        val active = records.first { it.epistemicStatus != EpistemicStatus.SUPERSEDED }
        assertEquals("Battery level 75%", active.claimOrFact)
    }

    @Test
    fun `synthetic model inference is rejected from persistent memory`() {
        val hallucination = coordinator.recordEvidence(
            claim = "User prefers Italian food on Tuesdays",
            subject = "dining_preference",
            source = SourceAuthority.MODEL_INFERENCE,
            uri = "llm://thought",
            excerpt = "Inferred from prior Italian recipe query"
        )

        assertFalse(coordinator.isAdmissibleToPersistentMemory(hallucination))
    }

    @Test
    fun `grounded summary generates inline citations and flags unverified claims`() {
        coordinator.recordEvidence(
            claim = "Flight DL123 is on time",
            subject = "flight_status",
            source = SourceAuthority.TOOL_EXECUTION,
            uri = "tool://airline_api",
            excerpt = "DL123: ON_TIME"
        )

        val summary = coordinator.generateGroundedSummary(
            listOf("Flight DL123 is on time", "Gate departs from Terminal 2")
        )

        assertTrue(summary.textWithCitations.contains("Flight DL123 is on time [1]"))
        assertTrue(summary.textWithCitations.contains("Gate departs from Terminal 2 [unverified]"))
        assertEquals(1, summary.citations.size)
        assertEquals("tool://airline_api", summary.citations[0].sourceUri)
    }
}
