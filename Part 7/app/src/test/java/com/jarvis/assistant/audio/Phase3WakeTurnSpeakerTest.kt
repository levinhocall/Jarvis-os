package com.jarvis.assistant.audio

import com.jarvis.assistant.audio.speaker.AddresseeDetector
import com.jarvis.assistant.audio.speaker.AddresseeSignalInput
import com.jarvis.assistant.audio.speaker.NeuralSpeakerEngine
import com.jarvis.assistant.audio.speaker.SpeakerExtractorDriver
import com.jarvis.assistant.audio.turn.ConversationTurnState
import com.jarvis.assistant.audio.turn.SemanticTurnDetector
import com.jarvis.assistant.audio.turn.TurnDecisionEngine
import com.jarvis.assistant.audio.turn.TurnDecisionInput
import com.jarvis.assistant.audio.wake.WakeCandidateScore
import com.jarvis.assistant.audio.wake.WakePhraseId
import com.jarvis.assistant.audio.wake.WakeWordDecisionManager
import com.jarvis.assistant.core.runtime.NeuralRuntimeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class Phase3WakeTurnSpeakerTest {

    @Test
    fun wakeWordManagerEnforcesWarmupPlaybackGateConsecutiveHitsAndCooldownAcrossAllThreePhrases() {
        val manager = WakeWordDecisionManager(cooldownMs = 2_500L, requiredConfirmations = 2, warmupFrames = 3)
        var now = 10_000L

        // Warmup frames never trigger
        repeat(3) {
            val res = manager.evaluateScores(
                candidates = listOf(WakeCandidateScore(WakePhraseId.HEY_JARVIS, 0.98f, "oww")),
                sensitivity = 70,
                speechRecent = true,
                isPlaybackActive = false,
                hasEchoReference = false,
                nowMs = now
            )
            assertFalse(res.triggered)
            now += 80L
        }

        // Unreferenced assistant playback suppresses triggers completely
        val playbackSuppressed = manager.evaluateScores(
            candidates = listOf(WakeCandidateScore(WakePhraseId.HELLO_JARVIS, 0.99f, "sherpa")),
            sensitivity = 80,
            speechRecent = true,
            isPlaybackActive = true,
            hasEchoReference = false,
            nowMs = now
        )
        assertFalse(playbackSuppressed.triggered)
        assertTrue(playbackSuppressed.reason.contains("Suppressed"))
        now += 80L

        // Closed Silero speech gate suppresses triggers
        val gateClosed = manager.evaluateScores(
            candidates = listOf(WakeCandidateScore(WakePhraseId.WAKE_UP_JARVIS, 0.95f, "sherpa")),
            sensitivity = 80,
            speechRecent = false,
            isPlaybackActive = false,
            hasEchoReference = false,
            nowMs = now
        )
        assertFalse(gateClosed.triggered)
        now += 80L

        // Echo-referenced playback still allows high-confidence 2-frame barge-in wake
        val firstHit = manager.evaluateScores(
            candidates = listOf(WakeCandidateScore(WakePhraseId.WAKE_UP_JARVIS, 0.92f, "sherpa")),
            sensitivity = 70,
            speechRecent = true,
            isPlaybackActive = true,
            hasEchoReference = true,
            nowMs = now
        )
        assertFalse(firstHit.triggered)
        now += 80L

        val secondHit = manager.evaluateScores(
            candidates = listOf(WakeCandidateScore(WakePhraseId.WAKE_UP_JARVIS, 0.94f, "sherpa")),
            sensitivity = 70,
            speechRecent = true,
            isPlaybackActive = true,
            hasEchoReference = true,
            nowMs = now
        )
        assertTrue(secondHit.triggered)
        assertEquals(WakePhraseId.WAKE_UP_JARVIS, secondHit.phrase)
        now += 500L

        // Cooldown prevents double trigger within 2.5s
        val cooldownBlocked = manager.evaluateScores(
            candidates = listOf(WakeCandidateScore(WakePhraseId.HEY_JARVIS, 0.99f, "oww")),
            sensitivity = 90,
            speechRecent = true,
            isPlaybackActive = false,
            hasEchoReference = false,
            nowMs = now
        )
        assertFalse(cooldownBlocked.triggered)
        assertEquals("Cooldown active", cooldownBlocked.reason)

        // All 3 primary phrases + custom alias transcript normalization match cleanly
        assertEquals(WakePhraseId.HEY_JARVIS, WakeWordDecisionManager.matchPhraseFromTranscript("Hey, Jarvis"))
        assertEquals(WakePhraseId.HELLO_JARVIS, WakeWordDecisionManager.matchPhraseFromTranscript("Hello Jarvis open camera"))
        assertEquals(WakePhraseId.WAKE_UP_JARVIS, WakeWordDecisionManager.matchPhraseFromTranscript("wake up jarvis"))
        assertEquals(
            WakePhraseId.CUSTOM,
            WakeWordDecisionManager.matchPhraseFromTranscript("Computer please help", listOf("Computer"))
        )
        assertNull(WakeWordDecisionManager.matchPhraseFromTranscript("tell me a story"))
    }

    @Test
    fun semanticAndHybridTurnDetectionDistinguishBilingualHesitationsFromCompleteTurns() {
        val correction = SemanticTurnDetector.scoreTranscript("Jarvis, open WhatsApp... actually wait...")
        assertTrue(correction.isExplicitHoldOrCorrection)
        assertTrue(correction.suggestedHoldExtensionMs >= 600L)

        val hinglishLinker = SemanticTurnDetector.scoreTranscript("Kal subah mujhe yaad dilana ki")
        assertTrue(hinglishLinker.suggestedHoldExtensionMs >= 400L)
        assertTrue(hinglishLinker.completionScore < 0.45f)

        val completeHindiQuery = SemanticTurnDetector.scoreTranscript("Time kya hua hai?")
        assertFalse(completeHindiQuery.isHesitation)
        assertTrue(completeHindiQuery.completionScore >= 0.85f)
        assertTrue(completeHindiQuery.suggestedHoldExtensionMs < 0L)

        val engine = TurnDecisionEngine()
        engine.onSpeechStarted()
        assertEquals(ConversationTurnState.SPEECH_ACTIVE, engine.state)

        // Short pause during explicit correction must wait (>= 900ms), not cut off user
        val holdDecision = engine.evaluatePause(
            TurnDecisionInput(
                silenceDurationMs = 500L,
                baseVadTimeoutMs = 320L,
                acousticTurnProbability = 0.72f,
                partialTranscript = "Send message... umm... hold on",
                hasActiveSpeech = false
            )
        )
        assertFalse(holdDecision.shouldCompleteTurn)
        assertEquals(ConversationTurnState.POSSIBLE_PAUSE, holdDecision.state)

        // Complete utterance with fast semantic+acoustic endpoint finishes promptly
        val completeDecision = engine.evaluatePause(
            TurnDecisionInput(
                silenceDurationMs = 230L,
                baseVadTimeoutMs = 320L,
                acousticTurnProbability = 0.92f,
                partialTranscript = "Time kya hua hai?",
                hasActiveSpeech = false
            )
        )
        assertTrue(completeDecision.shouldCompleteTurn)
        assertEquals(ConversationTurnState.TURN_COMPLETE, completeDecision.state)
    }

    @Test
    fun neuralSpeakerEngineNormalizesEmbeddingsAndAddresseeDetectorFiltersThirdPersonSpeech() {
        val fakeModel = File.createTempFile("jarvis_speaker_test", ".onnx").apply {
            writeBytes(ByteArray(32) { 1 })
            deleteOnExit()
        }
        var released = false
        val fakeDriver = object : SpeakerExtractorDriver {
            override fun initialize(modelFile: File, threads: Int): Boolean = modelFile.isFile
            override fun embeddingDim(): Int = 3
            override fun computeEmbedding(samples16k: FloatArray): FloatArray? = floatArrayOf(3f, 4f, 0f)
            override fun release() {
                released = true
            }
        }

        val engine = NeuralSpeakerEngine { fakeDriver }
        assertTrue(engine.loadModel(fakeModel))
        assertTrue(engine.isReady())

        // Too short (< 6400 bytes / 200ms) returns null
        assertNull(engine.extractEmbeddingPcm16(ByteArray(1_000)))

        // 250ms PCM16 (8000 bytes) returns L2-normalized embedding [0.6, 0.8, 0.0]
        val emb = engine.extractEmbeddingPcm16(ByteArray(8_000) { 0x10 })
        assertNotNull(emb)
        val norm = sqrt(emb!!.sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1.0f, norm, 0.01f)
        assertEquals(0.6f, emb[0], 0.01f)
        assertEquals(0.8f, emb[1], 0.01f)

        val statsBefore = NeuralRuntimeManager.snapshot.value
        assertTrue(statsBefore.sessions.any { it.modelId == "speaker_3dspeaker_campplus" })

        engine.release()
        assertTrue(released)
        assertFalse(engine.isReady())

        // Third-person reference should not be treated as directed speech
        val thirdPerson = AddresseeDetector.evaluate(
            AddresseeSignalInput(
                wakeConfidence = 0.2f,
                isActiveConversation = true,
                speakerSimilarity = 0.90f,
                hasEnrolledSpeaker = true,
                transcript = "Please tell Jarvis that I am outside"
            )
        )
        assertFalse(thirdPerson.isAddressedToJarvis)
        assertTrue(thirdPerson.requireConfirmationForSensitiveAction)
        assertEquals("third_person_reference", thirdPerson.reason)

        // Enrolled owner issuing direct wake/command passes addressee + sensitive confirmation gate
        val directOwner = AddresseeDetector.evaluate(
            AddresseeSignalInput(
                wakeConfidence = 0.92f,
                isActiveConversation = true,
                speakerSimilarity = 0.84f,
                hasEnrolledSpeaker = true,
                transcript = "Hey Jarvis open Chrome"
            )
        )
        assertTrue(directOwner.isAddressedToJarvis)
        assertFalse(directOwner.requireConfirmationForSensitiveAction)
    }
}
