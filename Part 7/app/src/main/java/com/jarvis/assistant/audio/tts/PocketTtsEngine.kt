package com.jarvis.assistant.audio.tts

import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.audio.PcmAudioConverter
import com.jarvis.assistant.audio.model.NeuralRuntimeKind
import com.jarvis.assistant.core.runtime.InferencePriority
import com.jarvis.assistant.core.runtime.NeuralRuntimeManager
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal data class PocketModelFileSet(
    val lmFlow: File,
    val lmMain: File,
    val encoder: File,
    val decoder: File,
    val textConditioner: File,
    val vocabJson: File,
    val tokenScoresJson: File
) {
    fun allExist(): Boolean = listOf(
        lmFlow, lmMain, encoder, decoder, textConditioner, vocabJson, tokenScoresJson
    ).all { it.isFile && it.length() > 0L }
}

/** Driver boundary isolating native Sherpa-ONNX PocketTTS calls for clean host JVM verification. */
internal interface PocketTtsDriver {
    fun createSession(files: PocketModelFileSet, threads: Int): Boolean
    fun sampleRateHz(): Int
    fun synthesize(
        text: String,
        speed: Float,
        referenceAudio: FloatArray?,
        referenceSampleRate: Int,
        onFloatChunk: (FloatArray) -> Boolean
    ): Boolean
    fun destroySession()
}

internal class SherpaPocketTtsDriver : PocketTtsDriver {
    @Volatile private var offlineTts: OfflineTts? = null

    @Synchronized
    override fun createSession(files: PocketModelFileSet, threads: Int): Boolean {
        destroySession()
        return runCatching {
            val pocket = OfflineTtsPocketModelConfig().apply {
                lmFlow = files.lmFlow.absolutePath
                lmMain = files.lmMain.absolutePath
                encoder = files.encoder.absolutePath
                decoder = files.decoder.absolutePath
                textConditioner = files.textConditioner.absolutePath
                vocabJson = files.vocabJson.absolutePath
                tokenScoresJson = files.tokenScoresJson.absolutePath
                voiceEmbeddingCacheCapacity = 8
            }
            val modelConfig = OfflineTtsModelConfig().apply {
                this.pocket = pocket
                numThreads = threads.coerceIn(1, 4)
                debug = false
                provider = "cpu"
            }
            val ttsConfig = OfflineTtsConfig().apply {
                model = modelConfig
                maxNumSentences = 2
                silenceScale = 0.2f
            }
            offlineTts = OfflineTts(null, ttsConfig)
            true
        }.getOrElse { false }
    }

    override fun sampleRateHz(): Int = offlineTts?.sampleRate() ?: 24_000

    override fun synthesize(
        text: String,
        speed: Float,
        referenceAudio: FloatArray?,
        referenceSampleRate: Int,
        onFloatChunk: (FloatArray) -> Boolean
    ): Boolean {
        val tts = offlineTts ?: return false
        val genConfig = GenerationConfig().apply {
            this.speed = speed.coerceIn(0.6f, 1.8f)
            this.numSteps = 1
            if (referenceAudio != null && referenceAudio.isNotEmpty() && referenceSampleRate > 0) {
                this.referenceAudio = referenceAudio
                this.referenceSampleRate = referenceSampleRate
            }
        }
        tts.generateWithConfigAndCallback(text, genConfig) { chunk ->
            if (onFloatChunk(chunk)) 1 else 0
        }
        return true
    }

    @Synchronized
    override fun destroySession() {
        runCatching { offlineTts?.release() }
        offlineTts = null
    }
}

/**
 * Clean-room streaming neural TTS engine powered by Kyutai Pocket TTS ONNX graphs via Sherpa-ONNX.
 * Produces 24 kHz mono PCM16 chunks suitable for direct [AudioPlayer] echo-reference playback
 * and immediate sub-chunk barge-in cancellation.
 */
class PocketTtsEngine internal constructor(
    private val driverFactory: () -> PocketTtsDriver
) : PocketTtsBackend {

    constructor() : this({ SherpaPocketTtsDriver() })

    companion object {
        private const val TAG = "PocketTtsEngine"
        private const val MODEL_ID = "pocket_tts_en_int8"
        private const val EST_RAM_MB = 145

        internal fun resolveModelFiles(modelDirectory: String): PocketModelFileSet? {
            val dir = File(modelDirectory)
            if (!dir.isDirectory) return null
            val lmFlow = firstExisting(dir, "lm_flow.int8.onnx", "flow_lm_flow_int8.onnx", "lm_flow.onnx") ?: return null
            val lmMain = firstExisting(dir, "lm_main.int8.onnx", "flow_lm_main_int8.onnx", "lm_main.onnx") ?: return null
            val encoder = firstExisting(dir, "encoder.onnx", "mimi_encoder.onnx") ?: return null
            val decoder = firstExisting(dir, "decoder.int8.onnx", "mimi_decoder_int8.onnx", "decoder.onnx") ?: return null
            val textCond = firstExisting(dir, "text_conditioner.onnx") ?: return null
            val vocab = firstExisting(dir, "vocab.json") ?: return null
            val scores = firstExisting(dir, "token_scores.json") ?: return null
            val set = PocketModelFileSet(lmFlow, lmMain, encoder, decoder, textCond, vocab, scores)
            return if (set.allExist()) set else null
        }

        private fun firstExisting(dir: File, vararg names: String): File? =
            names.asSequence().map { File(dir, it) }.firstOrNull { it.isFile && it.length() > 0L }

        internal fun splitIntoChunks(text: String, maxCharsPerChunk: Int = 220): List<String> {
            val normalized = text.replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) return emptyList()
            val sentences = normalized.split(Regex("(?<=[.!?])\\s+"))
            val result = ArrayList<String>(sentences.size)
            val current = StringBuilder()
            for (s in sentences) {
                if (s.isBlank()) continue
                if (current.isNotEmpty() && current.length + 1 + s.length > maxCharsPerChunk) {
                    result += current.toString().trim()
                    current.clear()
                }
                if (s.length <= maxCharsPerChunk) {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(s)
                } else {
                    if (current.isNotEmpty()) {
                        result += current.toString().trim()
                        current.clear()
                    }
                    result += s.chunked(maxCharsPerChunk)
                }
            }
            if (current.isNotEmpty()) result += current.toString().trim()
            return result
        }

        internal fun floatPcmTo24kPcm16(samples: FloatArray, sourceRateHz: Int): ByteArray {
            if (samples.isEmpty()) return ByteArray(0)
            val raw = ByteArray(samples.size * 2)
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val clamped = (sample * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                bb.putShort(clamped)
            }
            return PcmAudioConverter.to24kMonoPcm16(
                pcm = raw,
                srcRateHz = sourceRateHz.coerceAtLeast(8_000),
                channels = 1
            )
        }

        internal fun decodeWavToMonoFloats(wavFile: File): Pair<FloatArray, Int>? {
            if (!wavFile.isFile || wavFile.length() <= 44L) return null
            val bytes = wavFile.readBytes()
            val pcm24k = PcmAudioConverter.decodeWavToMono24kPcm16(bytes) ?: return null
            val count = pcm24k.size / 2
            val floats = FloatArray(count)
            val bb = ByteBuffer.wrap(pcm24k).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                floats[i] = bb.short / 32768f
            }
            return floats to 24_000
        }
    }

    private var driver: PocketTtsDriver? = null
    @Volatile private var ready = false
    @Volatile private var activeConfig: PocketTtsModelConfig? = null
    @Volatile private var voiceConfig = PocketTtsVoiceConfig()
    @Volatile private var referenceAudio: FloatArray? = null
    @Volatile private var referenceSampleRate = 24_000
    private val cancelled = AtomicBoolean(false)
    private val synthLock = Any()

    @Synchronized
    override fun loadModel(config: PocketTtsModelConfig): Boolean {
        val files = resolveModelFiles(config.modelDirectory) ?: return false
        val newDriver = driverFactory()
        val ok = runCatching { newDriver.createSession(files, config.threads) }
            .onFailure { err ->
                NeuralRuntimeManager.markFailed(MODEL_ID, NeuralRuntimeKind.ONNX_RUNTIME, err.message ?: "init_failed")
            }
            .getOrDefault(false)
        if (!ok) {
            runCatching { newDriver.destroySession() }
            ready = false
            return false
        }
        driver?.destroySession()
        driver = newDriver
        activeConfig = config
        ready = true
        NeuralRuntimeManager.registerLoaded(
            modelId = MODEL_ID,
            runtime = NeuralRuntimeKind.ONNX_RUNTIME,
            estimatedMemoryMb = EST_RAM_MB,
            priority = InferencePriority.CONVERSATION_SPEECH,
            onUnload = { unloadModel() }
        )
        return true
    }

    @Synchronized
    override fun prepareVoice(voiceConfig: PocketTtsVoiceConfig): Boolean {
        this.voiceConfig = voiceConfig
        val path = voiceConfig.clonePromptPath?.takeIf { it.isNotBlank() }
        if (path != null) {
            val decoded = decodeWavToMonoFloats(File(path)) ?: return false
            referenceAudio = decoded.first
            referenceSampleRate = decoded.second
        } else {
            referenceAudio = null
            referenceSampleRate = 24_000
        }
        return true
    }

    override fun synthesizeStreaming(
        text: String,
        onPcm24kChunk: (ByteArray) -> Unit
    ): Boolean {
        val currentDriver = driver
        if (!ready || currentDriver == null) return false
        val chunks = splitIntoChunks(text)
        if (chunks.isEmpty()) return true

        cancelled.set(false)
        synchronized(synthLock) {
            val startUs = SystemClock.elapsedRealtimeNanos() / 1000L
            var emitted = false
            val rate = currentDriver.sampleRateHz().coerceAtLeast(8_000)
            for (chunkText in chunks) {
                if (cancelled.get()) break
                val ok = runCatching {
                    currentDriver.synthesize(
                        text = chunkText,
                        speed = voiceConfig.speedScale,
                        referenceAudio = referenceAudio,
                        referenceSampleRate = referenceSampleRate
                    ) { floats ->
                        if (cancelled.get()) return@synthesize false
                        val pcm24k = floatPcmTo24kPcm16(floats, rate)
                        if (pcm24k.isNotEmpty() && !cancelled.get()) {
                            onPcm24kChunk(pcm24k)
                            emitted = true
                        }
                        !cancelled.get()
                    }
                }.onFailure { err ->
                    Log.w(TAG, "PocketTTS chunk synthesis failed: ${err.message}")
                }.getOrDefault(false)
                if (!ok) return false
            }
            val elapsedUs = (SystemClock.elapsedRealtimeNanos() / 1000L - startUs).coerceAtLeast(1L)
            NeuralRuntimeManager.recordInference(MODEL_ID, elapsedUs, active = false)
            return !cancelled.get() && emitted
        }
    }

    override fun cancelSynthesis() {
        cancelled.set(true)
    }

    override fun reset() {
        cancelled.set(false)
    }

    @Synchronized
    override fun unloadModel() {
        cancelled.set(true)
        ready = false
        runCatching { driver?.destroySession() }
        driver = null
        activeConfig = null
    }

    override fun isReady(): Boolean = ready

    override fun close() {
        unloadModel()
    }
}
