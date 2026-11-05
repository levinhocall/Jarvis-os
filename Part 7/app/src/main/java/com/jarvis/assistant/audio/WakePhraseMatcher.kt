package com.jarvis.assistant.audio

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/**
 * Offline text-level wake phrase matcher. This does not imply acoustic confidence:
 * call only after Vosk has emitted a partial/final recognition hypothesis.
 *
 * Configuration is intentionally immutable so the audio thread can swap it atomically.
 */
class WakePhraseMatcher(
    initial: Config = Config()
) {
    data class Config(
        val callSign: String = "Jarvis",
        val sensitivity: Int = 65,
        val cooldownMillis: Long = 2_500L
    ) {
        init {
            require(callSign.isNotBlank() && callSign.length <= 40)
            require(sensitivity in 0..100)
            require(cooldownMillis in 0..60_000)
        }
    }

    @Volatile private var config: Config = initial
    private var lastTriggerMs: Long = Long.MIN_VALUE

    @Synchronized
    fun configure(next: Config) {
        config = next
        lastTriggerMs = Long.MIN_VALUE
    }

    fun snapshot(): Config = config

    @Synchronized
    fun matches(hypothesis: String, nowElapsedMs: Long): Boolean {
        val cfg = config
        val name = normalize(cfg.callSign)
        val heard = normalize(hypothesis)
        if (name.isEmpty() || heard.isEmpty()) return false
        val tokens = heard.split(' ').filter(String::isNotBlank)
        val nameTokens = name.split(' ').filter(String::isNotBlank)
        if (tokens.size > 12 || nameTokens.size > 4) return false
        val threshold = when {
            cfg.sensitivity <= 25 -> 1.0
            cfg.sensitivity <= 60 -> 0.90
            cfg.sensitivity <= 85 -> 0.82
            else -> 0.74
        }
        val prefixes = listOf(emptyList(), listOf("hey"), listOf("hi"), listOf("ok"),
            listOf("okay"), listOf("wake", "up"))
        val candidates = prefixes.map { it + nameTokens }
        val detected = candidates.any { candidate ->
            if (candidate.size > tokens.size) return@any false
            tokens.windowed(candidate.size).any { window ->
                val similarity = similarity(window.joinToString(" "), candidate.joinToString(" "))
                similarity >= threshold
            }
        }
        if (!detected) return false
        if (lastTriggerMs != Long.MIN_VALUE &&
            nowElapsedMs >= lastTriggerMs &&
            nowElapsedMs - lastTriggerMs < cfg.cooldownMillis
        ) return false
        lastTriggerMs = nowElapsedMs
        return true
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        val prev = IntArray(b.length + 1) { it }
        val next = IntArray(b.length + 1)
        for (i in a.indices) {
            next[0] = i + 1
            for (j in b.indices) {
                next[j + 1] = minOf(
                    next[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + if (a[i] == b[j]) 0 else 1
                )
            }
            next.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / longest
    }
}
