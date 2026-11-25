package com.jarvis.assistant.audio

import com.jarvis.assistant.data.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDuplexBargeInControllerTest {

    private fun baseInput(
        nowMs: Long = 2000L,
        lastEndMs: Long = 1000L,
        isPlaybackActive: Boolean = true,
        hasPcmReference: Boolean = true,
        isAecActive: Boolean = true,
        isDtlnActive: Boolean = true,
        hasSpeechDetector: Boolean = true,
        speechProb: Float = 0f,
        isSpeechConfirmed: Boolean = false,
        saturatedFrames: Long = 0L
    ) = FullDuplexBargeInController.FrameInput(
        nowMs = nowMs,
        lastPlaybackEndTimeMs = lastEndMs,
        isPlaybackActive = isPlaybackActive,
        hasPcmReference = hasPcmReference,
        playbackSource = PlaybackSource.GEMINI_PCM,
        isAecActive = isAecActive,
        isDtlnActive = isDtlnActive,
        hasSpeechDetector = hasSpeechDetector,
        speechProbability = speechProb,
        isSpeechConfirmed = isSpeechConfirmed,
        aecMetrics = AecMetrics(
            echoReturnLossEnhancementDb = 18.0,
            activeProcessing = isAecActive,
            saturatedCaptureFrames = saturatedFrames
        )
    )

    @Test
    fun removesBlindAedpDelayWhenCleanFullDuplexPipelineIsActive() {
        val controller = FullDuplexBargeInController()

        // Within 50ms of playback stop: clean AEC3+Silero removes blind drop immediately.
        assertFalse(controller.shouldDropPostPlaybackDecay(baseInput(nowMs = 1050L, lastEndMs = 1000L, isPlaybackActive = false)))

        // In hardware fallback mode (no software AEC3), 150ms AEDP decay window stays protective.
        assertTrue(
            controller.shouldDropPostPlaybackDecay(
                baseInput(nowMs = 1050L, lastEndMs = 1000L, isPlaybackActive = false, isAecActive = false)
            )
        )
        assertFalse(
            controller.shouldDropPostPlaybackDecay(
                baseInput(nowMs = 1200L, lastEndMs = 1000L, isPlaybackActive = false, isAecActive = false)
            )
        )
    }

    @Test
    fun neuralBargeInTriggersQuicklyWithDtlnAndResistsSaturationFalseAlarms() {
        val controller = FullDuplexBargeInController()

        // Single mild frame does not fire; 2nd frame with DTLN + Silero triggers cleanly.
        val first = controller.evaluateDuringPlayback(baseInput(speechProb = 0.67f))
        assertEquals(FullDuplexBargeInController.DecisionKind.ALLOW_NORMAL_FLOW, first.kind)
        assertEquals(1, first.consecutiveSpeechFrames)

        val second = controller.evaluateDuringPlayback(baseInput(speechProb = 0.69f))
        assertEquals(FullDuplexBargeInController.DecisionKind.TRIGGER_NEURAL_BARGE_IN, second.kind)
        assertEquals("aec3_dtln_silero_speech", second.reason)

        // Saturation spike raises bar to 4 frames @ >= 0.78, blocking transient 0.72 false trigger.
        val sat1 = controller.evaluateDuringPlayback(baseInput(speechProb = 0.72f, isSpeechConfirmed = true, saturatedFrames = 2L))
        assertTrue(sat1.saturationActive)
        assertEquals(FullDuplexBargeInController.DecisionKind.ALLOW_NORMAL_FLOW, sat1.kind)

        // Strong sustained >= 0.80 across 4 frames breaks through even under saturation.
        repeat(3) {
            assertEquals(
                FullDuplexBargeInController.DecisionKind.ALLOW_NORMAL_FLOW,
                controller.evaluateDuringPlayback(baseInput(speechProb = 0.82f, saturatedFrames = 2L)).kind
            )
        }
        val fourth = controller.evaluateDuringPlayback(baseInput(speechProb = 0.82f, saturatedFrames = 2L))
        assertEquals(FullDuplexBargeInController.DecisionKind.TRIGGER_NEURAL_BARGE_IN, fourth.kind)

        // Cooldown expires cleanly after SATURATION_COOLDOWN_FRAMES quiet/non-clipping calls
        repeat(FullDuplexBargeInController.SATURATION_COOLDOWN_FRAMES + 1) {
            controller.evaluateDuringPlayback(baseInput(speechProb = 0.1f, saturatedFrames = 2L))
        }
        val postCooldown = controller.evaluateDuringPlayback(baseInput(speechProb = 0.1f, saturatedFrames = 2L))
        assertFalse(postCooldown.saturationActive)
    }

    @Test
    fun instantHighConfidenceBurstBreaksThroughWhenDtlnIsClean() {
        val controller = FullDuplexBargeInController()
        val burst = controller.evaluateDuringPlayback(baseInput(speechProb = 0.88f, isSpeechConfirmed = true))
        assertEquals(FullDuplexBargeInController.DecisionKind.TRIGGER_NEURAL_BARGE_IN, burst.kind)
    }
}
