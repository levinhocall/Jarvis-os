package com.jarvis.assistant.data.directives

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LearningPolicyTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 3)
    private fun attempt(day: LocalDate, audio: Boolean = false, pronunciation: Int? = null) = LearningAttemptEntity(
        createdAt = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), trainer = "Aisha", level = "Starter", goal = "Confidence",
        userText = "Practice", reply = "Feedback", correction = "", grammar = 60, fluency = 80, pronunciation = pronunciation, audioAssessed = audio)
    @Test fun supportsTheSpecifiedTrainerLevelAndGoalMatrix() {
        assertEquals(setOf("Aisha", "Alex", "Maya", "Ryan"), LearningPolicy.trainers.keys)
        assertEquals(6, LearningPolicy.levels.size); assertEquals(6, LearningPolicy.goals.size)
        LearningPolicy.trainers.keys.forEach { trainer -> LearningPolicy.levels.forEach { level ->
            LearningPolicy.goals.forEach { goal -> LearningPolicy.validate(LearningProfileEntity(trainer = trainer, level = level, goal = goal)) }
        } }
    }
    @Test fun emptyHistoryDoesNotInventScoresOrStreaks() {
        val m = LearningPolicy.metrics(emptyList(), today, zone)
        assertEquals(0, m.attempts); assertEquals(0, m.streak)
        assertNull(m.overall); assertNull(m.pronunciation); assertNull(m.fluency)
    }
    @Test fun countsDistinctConsecutivePracticeDays() {
        val m = LearningPolicy.metrics(listOf(attempt(today), attempt(today), attempt(today.minusDays(1)), attempt(today.minusDays(3))), today, zone)
        assertEquals(2, m.streak); assertEquals(4, m.attempts)
    }
    @Test fun yesterdayStreakSurvivesUntilEndOfToday() {
        assertEquals(2, LearningPolicy.metrics(listOf(attempt(today.minusDays(1)), attempt(today.minusDays(2))), today, zone).streak)
        assertEquals(0, LearningPolicy.metrics(listOf(attempt(today.minusDays(2))), today, zone).streak)
    }
    @Test fun textAttemptsCannotCreatePronunciationMetrics() {
        val m = LearningPolicy.metrics(listOf(attempt(today, pronunciation = 100)), today, zone)
        assertNull(m.pronunciation); assertEquals(70, m.overall)
    }
    @Test fun audioMetricsComeOnlyFromAssessedAudio() {
        val m = LearningPolicy.metrics(listOf(attempt(today, true, 50), attempt(today, false, 100)), today, zone)
        assertEquals(50, m.pronunciation); assertEquals(80, m.fluency)
    }
    @Test fun parserDropsPronunciationForTextEvenIfProviderSuppliesIt() {
        val response = JsonParser.parseString("""{"reply":"Try this sentence.","grammar":70,"fluency":80,"pronunciation":99}""").asJsonObject
        assertNull(LearningAssessment.parse(response, false).pronunciation)
    }
    @Test fun rejectsOutOfRangeAndFractionalScores() {
        for (score in listOf("101", "-1", "55.5", "\"perfect\"")) {
            val json = JsonParser.parseString("""{"reply":"Feedback","grammar":$score}""").asJsonObject
            assertThrows(IllegalArgumentException::class.java) { LearningAssessment.parse(json, false) }
        }
    }
    @Test fun audioNeedsARealTranscriptBeforeProgressIsSaved() {
        val json = JsonParser.parseString("""{"reply":"Good work","pronunciation":80}""").asJsonObject
        assertThrows(IllegalArgumentException::class.java) { LearningAssessment.parse(json, true) }
    }
}
