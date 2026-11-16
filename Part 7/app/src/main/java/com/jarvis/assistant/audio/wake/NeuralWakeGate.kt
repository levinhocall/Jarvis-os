package com.jarvis.assistant.audio.wake

/** Pure decision policy; probabilities come only from the trained classifier. */
class NeuralWakeGate {
    companion object {
        /** LiteRT 2.3.2 ships these Android ABIs; plain x86 keeps the existing Vosk path. */
        fun supportsRuntime(abis: List<String>): Boolean = abis.any { it in setOf("arm64-v8a","armeabi-v7a","x86_64") }
    }
    private var warmup = 0
    private var positives = 0
    private var lastDetection = Long.MIN_VALUE
    fun accept(score: Float, sensitivity: Int, nowMs: Long, speechRecent: Boolean = true): Boolean {
        if (++warmup < 25 || !score.isFinite() || !speechRecent) { positives = 0; return false }
        val threshold = 0.90f - sensitivity.coerceIn(0,100) * 0.0055f
        positives = if (score >= threshold) positives + 1 else 0
        if (positives < 2 || (lastDetection != Long.MIN_VALUE && nowMs - lastDetection < 2500)) return false
        positives = 0; lastDetection = nowMs
        return true
    }
}
