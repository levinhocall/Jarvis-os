package com.jarvis.assistant.audio.speaker

import com.jarvis.assistant.audio.OwnerVoiceManager
import com.jarvis.assistant.audio.model.NeuralRuntimeKind
import com.jarvis.assistant.core.runtime.InferencePriority
import com.jarvis.assistant.core.runtime.NeuralRuntimeManager
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

internal interface SpeakerExtractorDriver {
    fun initialize(modelFile: File, threads: Int): Boolean
    fun embeddingDim(): Int
    fun computeEmbedding(samples16k: FloatArray): FloatArray?
    fun release()
}

internal class SherpaSpeakerExtractorDriver : SpeakerExtractorDriver {
    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null

    @Synchronized
    override fun initialize(modelFile: File, threads: Int): Boolean {
        release()
        if (!modelFile.isFile || modelFile.length() <= 0L) return false
        return runCatching {
            val cfg = SpeakerEmbeddingExtractorConfig().apply {
                numThreads = threads.coerceIn(1, 2)
                debug = false
                provider = "cpu"
            }.copy(model = modelFile.absolutePath)
            extractor = SpeakerEmbeddingExtractor(null, cfg)
            true
        }.getOrElse { false }
    }

    override fun embeddingDim(): Int = extractor?.dim() ?: 0

    @Synchronized
    override fun computeEmbedding(samples16k: FloatArray): FloatArray? {
        val ext = extractor ?: return null
        if (samples16k.size < 3_200) return null
        val stream: OnlineStream = ext.createStream()
        return try {
            stream.acceptWaveform(samples16k, 16_000)
            stream.inputFinished()
            if (!ext.isReady(stream)) return null
            ext.compute(stream)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { stream.release() }
        }
    }

    @Synchronized
    override fun release() {
        runCatching { extractor?.release() }
        extractor = null
    }
}

/**
 * Clean-room neural speaker verification engine (3D-Speaker CAM++ / ECAPA-TDNN / ReDimNet ONNX
 * via Sherpa-ONNX) paired transparently with JARVIS's existing Vosk x-vector verification.
 */
class NeuralSpeakerEngine internal constructor(
    private val driverFactory: () -> SpeakerExtractorDriver = { SherpaSpeakerExtractorDriver() }
) {
    private var driver: SpeakerExtractorDriver? = null
    @Volatile private var ready = false

    @Synchronized
    fun loadModel(modelFile: File, threads: Int = 1): Boolean {
        val drv = driverFactory()
        if (!drv.initialize(modelFile, threads)) {
            runCatching { drv.release() }
            ready = false
            return false
        }
        driver?.release()
        driver = drv
        ready = true
        NeuralRuntimeManager.registerLoaded(
            modelId = "speaker_3dspeaker_campplus",
            runtime = NeuralRuntimeKind.ONNX_RUNTIME,
            estimatedMemoryMb = 36,
            priority = InferencePriority.CONVERSATION_SPEECH,
            onUnload = { release() }
        )
        return true
    }

    fun extractEmbeddingPcm16(pcm16kMono: ByteArray): FloatArray? {
        if (!ready || pcm16kMono.size < 6_400) return null
        val drv = driver ?: return null
        val count = pcm16kMono.size / 2
        val samples = FloatArray(count)
        val bb = ByteBuffer.wrap(pcm16kMono, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) samples[i] = bb.short / 32768f
        val vec = drv.computeEmbedding(samples) ?: return null
        return OwnerVoiceManager.averageVectors(listOf(vec))
    }

    fun isReady(): Boolean = ready

    @Synchronized
    fun release() {
        ready = false
        runCatching { driver?.release() }
        driver = null
    }
}

data class AddresseeSignalInput(
    val wakeConfidence: Float = 0f,
    val isActiveConversation: Boolean = false,
    val speakerSimilarity: Float = 1f,
    val hasEnrolledSpeaker: Boolean = false,
    val transcript: String = "",
    val isPlaybackActive: Boolean = false,
    val ambientLabel: String = "Speech"
)

data class AddresseeDecision(
    val isAddressedToJarvis: Boolean,
    val attentionScore: Float,
    val requireConfirmationForSensitiveAction: Boolean,
    val reason: String
)

/**
 * Determines whether detected speech is directed to JARVIS versus ambient third-party chatter
 * or playback audio, combining wake cues, speaker verification, conversation state, and phrasing.
 */
object AddresseeDetector {

    private val THIRD_PERSON_REFERENCES = listOf(
        "tell jarvis", "ask jarvis", "jarvis is ", "jarvis isn't", "my assistant jarvis"
    )

    private val DIRECT_VOCATIVES_OR_IMPERATIVES = listOf(
        "jarvis", "hey jarvis", "hello jarvis", "open ", "close ", "send ", "play ",
        "what ", "how ", "why ", "stop", "ruko", "batao", "karo", "kholo"
    )

    fun evaluate(input: AddresseeSignalInput): AddresseeDecision {
        val norm = input.transcript.lowercase(Locale.ROOT).trim()
        if (norm.isNotEmpty() && THIRD_PERSON_REFERENCES.any { norm.contains(it) }) {
            return AddresseeDecision(
                isAddressedToJarvis = false,
                attentionScore = 0.15f,
                requireConfirmationForSensitiveAction = true,
                reason = "third_person_reference"
            )
        }

        var score = 0.0f
        if (input.isActiveConversation) score += 0.42f
        score += (input.wakeConfidence.coerceIn(0f, 1f) * 0.45f)

        val directCue = norm.isNotEmpty() && DIRECT_VOCATIVES_OR_IMPERATIVES.any {
            norm.startsWith(it) || norm.contains("jarvis")
        }
        if (directCue) score += 0.30f

        if (input.hasEnrolledSpeaker) {
            if (input.speakerSimilarity >= 0.48f) {
                score += 0.22f
            } else {
                score -= 0.35f
            }
        }

        if (input.isPlaybackActive && input.wakeConfidence < 0.5f && !directCue) {
            score -= 0.20f
        }
        if (input.ambientLabel.equals("Television", ignoreCase = true) ||
            input.ambientLabel.equals("Music", ignoreCase = true)
        ) {
            score -= 0.15f
        }

        val finalScore = score.coerceIn(0f, 1f)
        val addressed = finalScore >= 0.52f
        val requireConfirm = !input.hasEnrolledSpeaker || input.speakerSimilarity < 0.65f || finalScore < 0.78f

        return AddresseeDecision(
            isAddressedToJarvis = addressed,
            attentionScore = finalScore,
            requireConfirmationForSensitiveAction = requireConfirm,
            reason = if (addressed) "directed_speech" else "background_or_non_addressed"
        )
    }
}
