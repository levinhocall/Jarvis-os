package com.jarvis.assistant.audio.stt

import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.audio.model.NeuralRuntimeKind
import com.jarvis.assistant.core.runtime.InferencePriority
import com.jarvis.assistant.core.runtime.JarvisEvent
import com.jarvis.assistant.core.runtime.JarvisEventBus
import com.jarvis.assistant.core.runtime.NeuralRuntimeManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal data class SherpaTransducerModelFiles(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
    val bpeVocab: File? = null
)

/** Driver boundary allowing fast deterministic JVM tests alongside real native Sherpa-ONNX ASR. */
internal interface SherpaSttDriver {
    fun initialize(files: SherpaTransducerModelFiles, sampleRateHz: Int, threads: Int): Boolean
    fun acceptSamples(samples: FloatArray, sampleRateHz: Int)
    fun decodeReadyAndReadText(): Pair<String, Boolean>
    fun resetStream()
    fun release()
}

internal class NativeSherpaSttDriver : SherpaSttDriver {
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var stream: OnlineStream? = null

    @Synchronized
    override fun initialize(
        files: SherpaTransducerModelFiles,
        sampleRateHz: Int,
        threads: Int
    ): Boolean {
        release()
        return runCatching {
            val transducer = OnlineTransducerModelConfig().apply {
                encoder = files.encoder.absolutePath
                decoder = files.decoder.absolutePath
                joiner = files.joiner.absolutePath
            }
            val modelConfig = OnlineModelConfig().apply {
                this.transducer = transducer
                tokens = files.tokens.absolutePath
                numThreads = threads.coerceIn(1, 4)
                debug = false
                provider = "cpu"
                modelType = "zipformer2"
                files.bpeVocab?.let { bpeVocab = it.absolutePath; modelingUnit = "bpe" }
            }
            val endpointConfig = EndpointConfig(
                EndpointRule(false, 2.2f, 0.0f),
                EndpointRule(true, 0.85f, 0.0f),
                EndpointRule(false, 0.0f, 18.0f)
            )
            val config = OnlineRecognizerConfig().apply {
                featConfig = FeatureConfig().apply {
                    sampleRate = sampleRateHz
                    featureDim = 80
                }
                this.modelConfig = modelConfig
                this.endpointConfig = endpointConfig
                enableEndpoint = true
                decodingMethod = "greedy_search"
                maxActivePaths = 4
            }
            val rec = OnlineRecognizer(null, config)
            recognizer = rec
            stream = rec.createStream("")
            true
        }.getOrElse { false }
    }

    @Synchronized
    override fun acceptSamples(samples: FloatArray, sampleRateHz: Int) {
        stream?.acceptWaveform(samples, sampleRateHz)
    }

    @Synchronized
    override fun decodeReadyAndReadText(): Pair<String, Boolean> {
        val rec = recognizer ?: return "" to false
        val st = stream ?: return "" to false
        while (rec.isReady(st)) {
            rec.decode(st)
        }
        val text = rec.getResult(st).text.trim()
        val endpoint = rec.isEndpoint(st)
        if (endpoint) {
            rec.reset(st)
        }
        return text to endpoint
    }

    @Synchronized
    override fun resetStream() {
        val rec = recognizer ?: return
        val st = stream ?: return
        runCatching { rec.reset(st) }
    }

    @Synchronized
    override fun release() {
        runCatching { stream?.release() }
        runCatching { recognizer?.release() }
        stream = null
        recognizer = null
    }
}

/**
 * Clean-room local streaming ASR replacing Maya's proprietary `libhunterstt_jni.so` using
 * Apache-2.0 Sherpa-ONNX streaming Zipformer ONNX models with transparent endpoint detection.
 */
class SherpaStreamingSttEngine internal constructor(
    private val modelDirectory: String,
    private val threads: Int = 2,
    private val driverFactory: () -> SherpaSttDriver = { NativeSherpaSttDriver() }
) : StreamingSttEngine {

    companion object {
        private const val TAG = "SherpaStreamingStt"
        private const val MODEL_ID = "sherpa_zipformer_en"
        private const val EST_RAM_MB = 72

        internal fun findTransducerFiles(dirPath: String): SherpaTransducerModelFiles? {
            val root = File(dirPath)
            if (!root.isDirectory) return null
            val candidates = root.walkTopDown().filter { it.isFile }.toList()
            val encoder = pickBestOnnx(candidates, "encoder") ?: return null
            val decoder = pickBestOnnx(candidates, "decoder") ?: return null
            val joiner = pickBestOnnx(candidates, "joiner") ?: return null
            val tokens = candidates.firstOrNull { it.name == "tokens.txt" && it.length() > 0L } ?: return null
            val bpe = candidates.firstOrNull { it.name == "bpe.model" && it.length() > 0L }
            return SherpaTransducerModelFiles(encoder, decoder, joiner, tokens, bpe)
        }

        private fun pickBestOnnx(files: List<File>, role: String): File? =
            files.filter { it.name.endsWith(".onnx") && it.name.contains(role, ignoreCase = true) && it.length() > 0L }
                .sortedWith(compareByDescending<File> { it.name.contains("int8", ignoreCase = true) }.thenBy { it.length() })
                .firstOrNull()

        internal fun pcm16BytesToFloats(pcm: ByteArray): FloatArray {
            val count = pcm.size / 2
            val out = FloatArray(count)
            val bb = ByteBuffer.wrap(pcm, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                out[i] = bb.short / 32768f
            }
            return out
        }
    }

    private var driver: SherpaSttDriver? = null
    private var config = StreamingSttConfig()
    private val initialized = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var lastPartial = ""
    @Volatile private var lastFinal = ""

    @Synchronized
    override fun initialize(config: StreamingSttConfig): Boolean {
        this.config = config
        cancelled.set(false)
        val files = findTransducerFiles(modelDirectory) ?: return false
        val newDriver = driverFactory()
        val ok = runCatching { newDriver.initialize(files, config.sampleRateHz, threads) }
            .onFailure { err ->
                NeuralRuntimeManager.markFailed(MODEL_ID, NeuralRuntimeKind.ONNX_RUNTIME, err.message ?: "stt_init_failed")
            }
            .getOrDefault(false)
        if (!ok) {
            runCatching { newDriver.release() }
            initialized.set(false)
            return false
        }
        driver?.release()
        driver = newDriver
        initialized.set(true)
        NeuralRuntimeManager.registerLoaded(
            modelId = MODEL_ID,
            runtime = NeuralRuntimeKind.ONNX_RUNTIME,
            estimatedMemoryMb = EST_RAM_MB,
            priority = InferencePriority.CONVERSATION_SPEECH,
            onUnload = { release() }
        )
        return true
    }

    @Synchronized
    override fun acceptAudio(pcm16kMono: ByteArray): TranscriptEvent? {
        if (!initialized.get() || cancelled.get() || pcm16kMono.size < 2) return null
        val drv = driver ?: return null
        val startUs = SystemClock.elapsedRealtimeNanos() / 1000L
        val floats = pcm16BytesToFloats(pcm16kMono)
        drv.acceptSamples(floats, config.sampleRateHz)
        val (text, isFinal) = drv.decodeReadyAndReadText()
        val elapsedUs = (SystemClock.elapsedRealtimeNanos() / 1000L - startUs).coerceAtLeast(1L)
        NeuralRuntimeManager.recordInference(MODEL_ID, elapsedUs, active = true)

        if (isFinal) {
            lastPartial = ""
            if (text.isEmpty()) return null
            lastFinal = text
            val evt = TranscriptEvent(text = text, isFinal = true, confidence = 0.92f, languageTag = config.languageHint)
            publishBusEvent(evt)
            return evt
        }
        if (config.enablePartialResults && text.isNotEmpty() && text != lastPartial) {
            lastPartial = text
            val evt = TranscriptEvent(text = text, isFinal = false, confidence = 0.78f, languageTag = config.languageHint)
            publishBusEvent(evt)
            return evt
        }
        return null
    }

    private fun publishBusEvent(evt: TranscriptEvent) {
        JarvisEventBus.tryPublish(
            JarvisEvent.TranscriptUpdated(
                text = evt.text,
                isFinal = evt.isFinal,
                languageTag = evt.languageTag,
                confidence = evt.confidence,
                eventId = JarvisEventBus.nextId(),
                timestampMs = evt.timestampMs,
                sessionId = 0L,
                sourceEngine = "SherpaStreamingSTT"
            )
        )
    }

    override fun getPartialTranscript(): String = lastPartial
    override fun getFinalTranscript(): String = lastFinal

    @Synchronized
    override fun reset() {
        cancelled.set(false)
        lastPartial = ""
        lastFinal = ""
        runCatching { driver?.resetStream() }
    }

    @Synchronized
    override fun cancel() {
        cancelled.set(true)
        lastPartial = ""
        runCatching { driver?.resetStream() }
    }

    @Synchronized
    override fun release() {
        initialized.set(false)
        cancelled.set(true)
        runCatching { driver?.release() }
        driver = null
    }
}

/**
 * Unified transcription manager choosing Sherpa-ONNX streaming Zipformer when installed and
 * transparently falling back to JARVIS's bundled Vosk recognizer with offline command fast-pathing.
 */
class HybridStreamingSttManager(
    private val sherpaDirectoryProvider: () -> String,
    private val voskTextExtractor: (String?) -> String
) : StreamingSttEngine {

    private var activeEngine: StreamingSttEngine? = null
    @Volatile private var activeBackendName: String = "UNINITIALIZED"

    fun currentBackend(): String = activeBackendName

    @Synchronized
    override fun initialize(config: StreamingSttConfig): Boolean {
        val sherpaDir = sherpaDirectoryProvider()
        val sherpa = SherpaStreamingSttEngine(sherpaDir)
        if (sherpa.initialize(config)) {
            activeEngine?.release()
            activeEngine = sherpa
            activeBackendName = "SHERPA_ZIPFORMER"
            return true
        }
        val vosk = VoskStreamingSttAdapter(voskTextExtractor)
        if (vosk.initialize(config)) {
            activeEngine?.release()
            activeEngine = vosk
            activeBackendName = "VOSK_KALDI_FALLBACK"
            return true
        }
        activeBackendName = "UNAVAILABLE"
        return false
    }

    override fun acceptAudio(pcm16kMono: ByteArray): TranscriptEvent? =
        activeEngine?.acceptAudio(pcm16kMono)

    override fun getPartialTranscript(): String = activeEngine?.getPartialTranscript().orEmpty()
    override fun getFinalTranscript(): String = activeEngine?.getFinalTranscript().orEmpty()
    override fun reset() { activeEngine?.reset() }
    override fun cancel() { activeEngine?.cancel() }

    @Synchronized
    override fun release() {
        activeEngine?.release()
        activeEngine = null
        activeBackendName = "UNINITIALIZED"
    }
}
