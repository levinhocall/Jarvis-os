package com.jarvis.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerVoiceSecurityTest {
    @Test
    fun acceptsOnlyExtractedVectorsInsideConfiguredThreshold() {
        val accepted = VoiceMatchResult(
            isMatch = true,
            distance = 0.60f,
            similarityPercent = 55,
            threshold = 0.70f,
            hasExtractedVector = true
        )
        val aboveThreshold = VoiceMatchResult(
            isMatch = false,
            distance = 0.80f,
            similarityPercent = 40,
            threshold = 0.70f,
            hasExtractedVector = true
        )
        val extractionFailed = VoiceMatchResult(
            isMatch = false,
            distance = 0.0f,
            similarityPercent = 0,
            threshold = 0.70f,
            hasExtractedVector = false
        )

        assertTrue(OwnerVoiceManager.isAcceptedOwnerVoiceMatch(accepted))
        assertFalse(OwnerVoiceManager.isAcceptedOwnerVoiceMatch(aboveThreshold))
        assertFalse(OwnerVoiceManager.isAcceptedOwnerVoiceMatch(extractionFailed))
    }
}
