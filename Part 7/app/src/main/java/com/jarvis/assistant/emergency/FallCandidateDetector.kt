package com.jarvis.assistant.emergency

/** A sensor candidate, never a medical diagnosis or proof of an accident. */
class FallCandidateDetector {
    private var freeFallAt = Long.MIN_VALUE
    private var impactAt = Long.MIN_VALUE
    private var quietSince = Long.MIN_VALUE
    fun sample(nowMs: Long, acceleration: Float): Boolean {
        if (!acceleration.isFinite() || acceleration < 0) return false
        if (acceleration < 3f) freeFallAt = nowMs
        if (freeFallAt != Long.MIN_VALUE && nowMs-freeFallAt in 0..1500 && acceleration > 28f) {
            impactAt=nowMs;freeFallAt=Long.MIN_VALUE;quietSince=Long.MIN_VALUE
        }
        if (impactAt == Long.MIN_VALUE) return false
        if (nowMs-impactAt > 8000) { reset();return false }
        if (kotlin.math.abs(acceleration-9.81f) < 2.5f) {
            if(quietSince == Long.MIN_VALUE) quietSince=nowMs
            if(nowMs-quietSince >= 2500) {reset();return true}
        } else quietSince=Long.MIN_VALUE
        return false
    }
    fun reset() {freeFallAt=Long.MIN_VALUE;impactAt=Long.MIN_VALUE;quietSince=Long.MIN_VALUE}
}
