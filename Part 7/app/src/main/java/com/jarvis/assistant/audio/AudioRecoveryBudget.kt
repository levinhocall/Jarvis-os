package com.jarvis.assistant.audio

/** Per-capture recovery burst; sustained successful frames replenish it, repeated failure cannot loop forever. */
internal class AudioRecoveryBudget(private val maximumAttempts: Int = 3, private val stableFrames: Int = 50) {
    private var attempts = 0
    private var healthy = 0
    init { require(maximumAttempts > 0 && stableFrames > 0) }
    fun nextAttempt(): Boolean { healthy = 0; return ++attempts <= maximumAttempts }
    fun successfulFrame() { if (++healthy >= stableFrames) { attempts = 0; healthy = stableFrames } }
}

enum class AudioCaptureFailure { PERMISSION_REVOKED, RECOVERY_EXHAUSTED }
