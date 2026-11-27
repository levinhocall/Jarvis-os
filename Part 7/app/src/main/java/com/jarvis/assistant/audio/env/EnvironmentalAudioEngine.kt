package com.jarvis.assistant.audio.env

import android.content.res.AssetManager
import com.jarvis.assistant.audio.model.NeuralRuntimeKind
import com.jarvis.assistant.core.runtime.InferencePriority
import com.jarvis.assistant.core.runtime.JarvisEvent
import com.jarvis.assistant.core.runtime.JarvisEventBus
import com.jarvis.assistant.core.runtime.NeuralRuntimeManager
import com.jarvis.assistant.neural.LiteRtMultiSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class EnvironmentalSceneCategory {
    QUIET,
    SPEECH,
    MUSIC,
    TRAFFIC,
    FAN_HVAC,
    ALARM_SIREN,
    CROWD,
    OTHER
}

data class EnvironmentalSnapshot(
    val topLabel: String = "Silence",
    val confidence: Float = 0f,
    val category: EnvironmentalSceneCategory = EnvironmentalSceneCategory.QUIET,
    val noiseFloorHint: Float = 0.02f,
    val vadThresholdBoost: Float = 0f,
    val timestampMs: Long = 0L
)

internal interface YamnetDriver {
    fun initialize(): Boolean
    fun classifyWaveform16k(samples: FloatArray): FloatArray?
    fun release()
}

internal class LiteRtYamnetDriver(
    private val assets: AssetManager,
    private val modelAssetPath: String = "yamnet/yamnet.tflite",
    private val threads: Int = 1
) : YamnetDriver {
    private var session: LiteRtMultiSession? = null
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffers: Array<ByteBuffer> = emptyArray()
    private var scoreOutputIndex = 0
    private var requiredSamples = 15_600

    @Synchronized
    override fun initialize(): Boolean {
        release()
        return runCatching {
            val modelBytes = assets.open(modelAssetPath).use { it.readBytes() }
            val modelBuf = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            modelBuf.put(modelBytes)
            modelBuf.rewind()

            val created = LiteRtMultiSession(modelBuf, threads)
            val inElems = created.inputElements(0).coerceAtLeast(15_600)
            requiredSamples = inElems
            inputBuffer = ByteBuffer.allocateDirect(inElems * 4).order(ByteOrder.nativeOrder())

            val outCount = created.outputCount().coerceAtLeast(1)
            val outs = Array(outCount) { idx ->
                val elems = created.outputElements(idx).coerceAtLeast(521)
                if (elems % 521 == 0 && (idx == 0 || created.outputName(idx).contains("score", true))) {
                    scoreOutputIndex = idx
                }
                ByteBuffer.allocateDirect(elems * 4).order(ByteOrder.nativeOrder())
            }
            outputBuffers = outs
            session = created
            true
        }.getOrElse { false }
    }

    @Synchronized
    override fun classifyWaveform16k(samples: FloatArray): FloatArray? {
        val active = session ?: return null
        val inBuf = inputBuffer ?: return null
        if (samples.isEmpty()) return null

        inBuf.clear()
        val limit = minOf(samples.size, requiredSamples)
        for (i in 0 until limit) {
            inBuf.putFloat(samples[i].coerceIn(-1f, 1f))
        }
        for (i in limit until requiredSamples) {
            inBuf.putFloat(0f)
        }
        inBuf.rewind()

        active.runMultiple(arrayOf(inBuf), outputBuffers)
        val outBuf = outputBuffers[scoreOutputIndex]
        outBuf.rewind()
        val floats = FloatArray(outBuf.remaining() / 4)
        outBuf.asFloatBuffer().get(floats)
        if (floats.size < 521) return null

        // Average across frame dimension [frames, 521] when multiple patches are returned.
        val frames = floats.size / 521
        val avg = FloatArray(521)
        for (f in 0 until frames) {
            val base = f * 521
            for (c in 0 until 521) {
                avg[c] += floats[base + c]
            }
        }
        if (frames > 1) {
            val inv = 1f / frames.toFloat()
            for (c in 0 until 521) avg[c] *= inv
        }
        return avg
    }

    @Synchronized
    override fun release() {
        runCatching { session?.close() }
        session = null
        inputBuffer = null
        outputBuffers = emptyArray()
    }
}

object YamnetClassMapParser {
    fun loadLabels(assets: AssetManager): List<String> = runCatching {
        assets.open("yamnet/yamnet_class_map.csv").use(::parseCsv)
    }.getOrDefault(emptyList())

    fun parseCsv(input: InputStream): List<String> {
        val labels = ArrayList<String>(521)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.lineSequence().drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = splitCsvRow(line)
                if (parts.size >= 3) labels.add(parts[2].trim())
            }
        }
        return labels
    }

    private fun splitCsvRow(line: String): List<String> {
        val out = ArrayList<String>(3)
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(ch)
            }
        }
        out.add(cur.toString())
        return out
    }

    fun categorize(label: String): EnvironmentalSceneCategory {
        val lower = label.lowercase(Locale.ROOT)
        return when {
            lower.contains("silence") || lower.contains("quiet") -> EnvironmentalSceneCategory.QUIET
            lower.contains("alarm") || lower.contains("siren") || lower.contains("smoke detector") || lower.contains("buzzer") ->
                EnvironmentalSceneCategory.ALARM_SIREN
            lower.contains("speech") || lower.contains("conversation") || lower.contains("narration") || lower.contains("whisper") ->
                EnvironmentalSceneCategory.SPEECH
            lower.contains("music") || lower.contains("singing") || lower.contains("song") || lower.contains("guitar") || lower.contains("piano") ->
                EnvironmentalSceneCategory.MUSIC
            lower.contains("traffic") || lower.contains("car") || lower.contains("vehicle") || lower.contains("bus") || lower.contains("train") || lower.contains("engine") ->
                EnvironmentalSceneCategory.TRAFFIC
            lower.contains("fan") || lower.contains("air conditioning") || lower.contains("vacuum") || lower.contains("hum") || lower.contains("white noise") ->
                EnvironmentalSceneCategory.FAN_HVAC
            lower.contains("crowd") || lower.contains("applause") || lower.contains("chatter") ->
                EnvironmentalSceneCategory.CROWD
            else -> EnvironmentalSceneCategory.OTHER
        }
    }

    fun recommendVadBoost(category: EnvironmentalSceneCategory, confidence: Float): Float {
        if (confidence < 0.25f) return 0f
        return when (category) {
            EnvironmentalSceneCategory.QUIET, EnvironmentalSceneCategory.SPEECH -> 0f
            EnvironmentalSceneCategory.FAN_HVAC -> 0.06f
            EnvironmentalSceneCategory.MUSIC -> 0.12f
            EnvironmentalSceneCategory.TRAFFIC, EnvironmentalSceneCategory.CROWD -> 0.14f
            EnvironmentalSceneCategory.ALARM_SIREN -> 0.18f
            EnvironmentalSceneCategory.OTHER -> 0.04f
        }
    }
}

/**
 * Low-power duty-cycled environmental sound recognizer built on official Apache-2.0 Google YAMNet.
 * Raw audio frames are classified immediately in memory and never retained on disk.
 */
class EnvironmentalAudioEngine internal constructor(
    private val classLabels: List<String>,
    private val minClassificationIntervalMs: Long = 2_500L,
    private val driverFactory: () -> YamnetDriver
) {
    constructor(
        assets: AssetManager,
        minClassificationIntervalMs: Long = 2_500L
    ) : this(
        classLabels = YamnetClassMapParser.loadLabels(assets),
        minClassificationIntervalMs = minClassificationIntervalMs,
        driverFactory = { LiteRtYamnetDriver(assets) }
    )

    private val _snapshot = MutableStateFlow(EnvironmentalSnapshot())
    val snapshot: StateFlow<EnvironmentalSnapshot> = _snapshot.asStateFlow()

    private var driver: YamnetDriver? = null
    @Volatile private var initialized = false
    private var lastClassifiedAtMs = 0L

    @Synchronized
    fun ensureInitialized(): Boolean {
        if (initialized && driver != null) return true
        if (!NeuralRuntimeManager.canSchedule(InferencePriority.BACKGROUND_VISION_ENV)) return false
        val drv = driverFactory()
        if (!drv.initialize()) {
            runCatching { drv.release() }
            NeuralRuntimeManager.markFailed("yamnet_env_audio", NeuralRuntimeKind.LITERT, "Failed to initialize YAMNet")
            return false
        }
        driver = drv
        initialized = true
        NeuralRuntimeManager.registerLoaded(
            modelId = "yamnet_env_audio",
            runtime = NeuralRuntimeKind.LITERT,
            estimatedMemoryMb = 16,
            priority = InferencePriority.BACKGROUND_VISION_ENV,
            onUnload = { release() }
        )
        return true
    }

    fun analyzePcm16(pcm16kMono: ByteArray, nowMs: Long = System.currentTimeMillis()): EnvironmentalSnapshot? {
        if (pcm16kMono.size < 31_200) return null // require 0.975s (15,600 * 2 bytes)
        if (nowMs - lastClassifiedAtMs < minClassificationIntervalMs) return null
        if (!NeuralRuntimeManager.canSchedule(InferencePriority.BACKGROUND_VISION_ENV)) return null
        if (!ensureInitialized()) return null

        val count = minOf(pcm16kMono.size / 2, 15_600)
        val samples = FloatArray(15_600)
        val bb = ByteBuffer.wrap(pcm16kMono, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        var energySum = 0.0
        for (i in 0 until count) {
            val s = bb.short / 32768f
            samples[i] = s
            energySum += (s * s)
        }
        val rms = kotlin.math.sqrt(energySum / count.toDouble()).toFloat()

        val startNs = System.nanoTime()
        val scores = driver?.classifyWaveform16k(samples) ?: return null
        val elapsedMicros = (System.nanoTime() - startNs) / 1_000L
        NeuralRuntimeManager.recordInference("yamnet_env_audio", elapsedMicros, active = false)

        var bestIdx = 0
        var bestScore = -1f
        for (i in 0 until minOf(scores.size, 521)) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                bestIdx = i
            }
        }
        val label = classLabels.getOrNull(bestIdx) ?: "Class #$bestIdx"
        val category = YamnetClassMapParser.categorize(label)
        val updated = EnvironmentalSnapshot(
            topLabel = label,
            confidence = bestScore.coerceIn(0f, 1f),
            category = category,
            noiseFloorHint = rms.coerceIn(0.01f, 0.40f),
            vadThresholdBoost = YamnetClassMapParser.recommendVadBoost(category, bestScore),
            timestampMs = nowMs
        )
        lastClassifiedAtMs = nowMs
        _snapshot.value = updated
        JarvisEventBus.tryPublish(
            JarvisEvent.EnvironmentChanged(
                label = label,
                confidence = updated.confidence,
                eventId = JarvisEventBus.nextId(),
                timestampMs = nowMs,
                sessionId = 0L,
                sourceEngine = "EnvironmentalAudioEngine"
            )
        )
        return updated
    }

    fun recommendedVadBoost(): Float = _snapshot.value.vadThresholdBoost

    @Synchronized
    fun release() {
        initialized = false
        runCatching { driver?.release() }
        driver = null
    }
}
