package com.jarvis.assistant.data.directives

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object LearningPolicy {
    val trainers = linkedMapOf(
        "Aisha" to "Be patient, encouraging and explain one small improvement at a time.",
        "Alex" to "Prioritize precise grammar, with concise rules and corrected examples.",
        "Maya" to "Practice relaxed everyday English, natural phrasing and conversational turn taking.",
        "Ryan" to "Run realistic interviews; assess structure, clarity and professional language."
    )
    val levels = listOf("Starter", "Beginner", "Elementary", "Intermediate", "Upper Intermediate", "Advanced")
    val goals = listOf("Confidence", "Grammar", "Interviews", "Academia", "Travel", "Total fluency")

    fun validate(profile: LearningProfileEntity) {
        require(profile.trainer in trainers && profile.level in levels && profile.goal in goals)
    }

    data class Metrics(val attempts: Int, val streak: Int, val overall: Int?, val pronunciation: Int?, val fluency: Int?)

    fun metrics(attempts: List<ScoredPractice>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Metrics {
        val dates = attempts.map { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }.filter { !it.isAfter(today) }.toSet()
        var day = if (today in dates) today else today.minusDays(1)
        var streak = 0
        while (day in dates) { streak++; day = day.minusDays(1) }
        fun average(values: List<Int>) = values.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val available = attempts.flatMap { a -> listOfNotNull(a.grammar, a.fluency, a.pronunciation.takeIf { a.audioAssessed }) }
        return Metrics(attempts.size, streak, average(available),
            average(attempts.filter { it.audioAssessed }.mapNotNull { it.pronunciation }),
            average(attempts.mapNotNull { it.fluency }))
    }
}
