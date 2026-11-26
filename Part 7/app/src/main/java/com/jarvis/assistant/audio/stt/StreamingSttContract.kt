package com.jarvis.assistant.audio.stt

import com.jarvis.assistant.audio.VoskModelManager
import org.vosk.Recognizer

data class StreamingSttConfig(
    val sampleRateHz: Int = 16_000,
    val languageHint: String = "en-IN",
    val enablePartialResults: Boolean = true,
    val grammarJson: String? = null
)

data class TranscriptEvent(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 1.0f,
    val timestampMs: Long = System.currentTimeMillis(),
    val languageTag: String = "unknown"
)

/**
 * Clean-room local streaming STT interface matching Part 1 §8 (`initialize`, `acceptAudio`,
 * `getPartialTranscript`, `getFinalTranscript`, `reset`, `cancel`, `release`), preparing uniform
 * hot-swap between bundled Vosk/Kaldi and Phase 2 Apache-2.0 Sherpa-ONNX streaming Zipformer.
 */
interface StreamingSttEngine {
    fun initialize(config: StreamingSttConfig): Boolean
    fun acceptAudio(pcm16kMono: ByteArray): TranscriptEvent?
    fun getPartialTranscript(): String
    fun getFinalTranscript(): String
    fun reset()
    fun cancel()
    fun release()
}

/**
 * Clean adapter wrapping JARVIS's existing offline Vosk/Kaldi recognizer into [StreamingSttEngine].
 */
class VoskStreamingSttAdapter(
    private val textExtractor: (String?) -> String
) : StreamingSttEngine {

    private var recognizer: Recognizer? = null
    private var config = StreamingSttConfig()
    @Volatile private var lastPartial = ""
    @Volatile private var lastFinal = ""
    @Volatile private var cancelled = false

    @Synchronized
    override fun initialize(config: StreamingSttConfig): Boolean {
        val model = VoskModelManager.model ?: return false
        this.config = config
        cancelled = false
        releaseRecognizerOnly()
        recognizer = if (!config.grammarJson.isNullOrBlank()) {
            Recognizer(model, config.sampleRateHz.toFloat(), config.grammarJson)
        } else {
            Recognizer(model, config.sampleRateHz.toFloat())
        }
        return true
    }

    @Synchronized
    override fun acceptAudio(pcm16kMono: ByteArray): TranscriptEvent? {
        if (cancelled || pcm16kMono.isEmpty()) return null
        val rec = recognizer ?: return null
        val isFinal = rec.acceptWaveForm(pcm16kMono, pcm16kMono.size)
        val json = if (isFinal) rec.result else if (config.enablePartialResults) rec.partialResult else return null
        val text = textExtractor(json).trim()
        if (isFinal) {
            if (text.isNotEmpty()) lastFinal = text
            lastPartial = ""
            return if (text.isEmpty()) null else TranscriptEvent(text = text, isFinal = true, languageTag = config.languageHint)
        }
        if (text.isNotEmpty() && text != lastPartial) {
            lastPartial = text
            return TranscriptEvent(text = text, isFinal = false, languageTag = config.languageHint)
        }
        return null
    }

    override fun getPartialTranscript(): String = lastPartial
    override fun getFinalTranscript(): String = lastFinal

    @Synchronized
    override fun reset() {
        cancelled = false
        lastPartial = ""
        lastFinal = ""
        runCatching { recognizer?.reset() }
    }

    @Synchronized
    override fun cancel() {
        cancelled = true
        lastPartial = ""
        runCatching { recognizer?.reset() }
    }

    @Synchronized
    override fun release() {
        cancelled = true
        lastPartial = ""
        lastFinal = ""
        releaseRecognizerOnly()
    }

    private fun releaseRecognizerOnly() {
        val old = recognizer.also { recognizer = null }
        if (old != null) runCatching { old.close() }
    }
}
