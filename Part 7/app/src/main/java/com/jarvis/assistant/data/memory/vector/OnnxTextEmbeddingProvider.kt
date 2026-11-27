package com.jarvis.assistant.data.memory.vector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.jarvis.assistant.audio.model.NeuralRuntimeKind
import com.jarvis.assistant.core.runtime.InferencePriority
import com.jarvis.assistant.core.runtime.NeuralRuntimeManager
import java.io.File
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.sqrt

/**
 * Clean-room BERT/MiniLM WordPiece tokenizer built directly over an open vocab.txt lexicon,
 * requiring zero native tokenizers or proprietary MTK1 binaries.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    val maxSequenceLength: Int = 128
) {
    private val clsId = vocab["[CLS]"] ?: 101
    private val sepId = vocab["[SEP]"] ?: 102
    private val unkId = vocab["[UNK]"] ?: 100
    private val padId = vocab["[PAD]"] ?: 0

    data class EncodedSequence(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    )

    companion object {
        fun fromVocabFile(vocabFile: File, maxSequenceLength: Int = 128): WordPieceTokenizer {
            val map = LinkedHashMap<String, Int>(32_768)
            vocabFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { idx, token ->
                    val clean = token.trimEnd('\r', '\n')
                    if (clean.isNotEmpty() && !map.containsKey(clean)) {
                        map[clean] = idx
                    }
                }
            }
            return WordPieceTokenizer(map, maxSequenceLength)
        }
    }

    fun encode(text: String): EncodedSequence {
        val ids = ArrayList<Int>(maxSequenceLength)
        ids.add(clsId)

        val basicTokens = basicTokenize(text)
        for (word in basicTokens) {
            if (ids.size >= maxSequenceLength - 1) break
            val subTokens = wordPieceSplit(word)
            for (subId in subTokens) {
                if (ids.size >= maxSequenceLength - 1) break
                ids.add(subId)
            }
        }
        ids.add(sepId)

        val len = ids.size
        val inputIds = LongArray(len) { ids[it].toLong() }
        val attentionMask = LongArray(len) { 1L }
        val tokenTypeIds = LongArray(len) { 0L }
        return EncodedSequence(inputIds, attentionMask, tokenTypeIds)
    }

    private fun basicTokenize(text: String): List<String> {
        val lower = text.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in lower) {
            when {
                ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) {
                        out.add(cur.toString())
                        cur.clear()
                    }
                }
                isPunctuation(ch) -> {
                    if (cur.isNotEmpty()) {
                        out.add(cur.toString())
                        cur.clear()
                    }
                    out.add(ch.toString())
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun wordPieceSplit(word: String): List<Int> {
        if (word.length > 64) return listOf(unkId)
        val exact = vocab[word]
        if (exact != null) return listOf(exact)

        val subIds = ArrayList<Int>()
        var start = 0
        var bad = false
        while (start < word.length) {
            var end = word.length
            var foundId: Int? = null
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) {
                    foundId = id
                    break
                }
                end--
            }
            if (foundId == null) {
                bad = true
                break
            }
            subIds.add(foundId)
            start = end
        }
        return if (bad || subIds.isEmpty()) listOf(unkId) else subIds
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}

internal interface OnnxEmbeddingDriver {
    fun initialize(modelFile: File): Boolean
    fun encodeTokens(encoded: WordPieceTokenizer.EncodedSequence): FloatArray?
    fun embeddingDimension(): Int
    fun release()
}

internal class NativeOnnxEmbeddingDriver(
    private val expectedDim: Int = 384
) : OnnxEmbeddingDriver {
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    private var inputNames: Set<String> = emptySet()

    @Synchronized
    override fun initialize(modelFile: File): Boolean {
        release()
        if (!modelFile.isFile || modelFile.length() <= 0L) return false
        return runCatching {
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
            }
            val created = ortEnv.createSession(modelFile.absolutePath, opts)
            inputNames = created.inputNames
            env = ortEnv
            session = created
            true
        }.getOrElse { false }
    }

    override fun embeddingDimension(): Int = expectedDim

    @Synchronized
    override fun encodeTokens(encoded: WordPieceTokenizer.EncodedSequence): FloatArray? {
        val ortEnv = env ?: return null
        val ortSession = session ?: return null
        val seqLen = encoded.inputIds.size
        if (seqLen == 0) return null
        val shape = longArrayOf(1L, seqLen.toLong())

        var idTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null
        var typeTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        return try {
            idTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(encoded.inputIds), shape)
            maskTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(encoded.attentionMask), shape)
            val feeds = LinkedHashMap<String, OnnxTensor>(3)
            feeds["input_ids"] = idTensor
            if ("attention_mask" in inputNames) feeds["attention_mask"] = maskTensor
            if ("token_type_ids" in inputNames) {
                typeTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(encoded.tokenTypeIds), shape)
                feeds["token_type_ids"] = typeTensor
            }

            result = ortSession.run(feeds)
            val firstOutput = result.get(0).value
            extractMeanPooled(firstOutput, encoded.attentionMask)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { result?.close() }
            runCatching { idTensor?.close() }
            runCatching { maskTensor?.close() }
            runCatching { typeTensor?.close() }
        }
    }

    private fun extractMeanPooled(rawValue: Any?, mask: LongArray): FloatArray? {
        @Suppress("UNCHECKED_CAST")
        when (rawValue) {
            is Array<*> -> {
                val first = rawValue.firstOrNull() ?: return null
                // Shape [1, seq, hidden] -> Array<Array<FloatArray>>
                if (first is Array<*> && first.firstOrNull() is FloatArray) {
                    val tokenVectors = first as Array<FloatArray>
                    if (tokenVectors.isEmpty()) return null
                    val dim = tokenVectors[0].size
                    val pooled = FloatArray(dim)
                    var count = 0f
                    for (t in tokenVectors.indices) {
                        if (t < mask.size && mask[t] == 0L) continue
                        val vec = tokenVectors[t]
                        for (d in 0 until dim) pooled[d] += vec[d]
                        count += 1f
                    }
                    if (count <= 0f) return null
                    for (d in 0 until dim) pooled[d] /= count
                    return l2Normalize(pooled)
                }
                // Pre-pooled [1, hidden] -> Array<FloatArray>
                if (first is FloatArray) {
                    return l2Normalize(first.copyOf())
                }
                return null
            }
            else -> return null
        }
    }

    private fun l2Normalize(v: FloatArray): FloatArray? {
        var sq = 0.0
        for (x in v) sq += x * x
        val norm = sqrt(sq).toFloat()
        if (norm <= 1e-8f) return null
        return FloatArray(v.size) { v[it] / norm }
    }

    @Synchronized
    override fun release() {
        runCatching { session?.close() }
        session = null
        env = null
        inputNames = emptySet()
    }
}

/**
 * On-device ONNX dense semantic memory embedding provider (e.g. MiniLM-L6-v2 INT8)
 * integrated with [NeuralRuntimeManager] RAM governance.
 */
class OnnxTextEmbeddingProvider internal constructor(
    override val dimension: Int = 384,
    override val modelName: String = "minilm-l6-v2-int8-onnx",
    private val driverFactory: () -> OnnxEmbeddingDriver = { NativeOnnxEmbeddingDriver(dimension) }
) : EmbeddingProvider, AutoCloseable {

    private var tokenizer: WordPieceTokenizer? = null
    private var driver: OnnxEmbeddingDriver? = null
    @Volatile private var ready = false

    @Synchronized
    fun loadModel(modelFile: File, vocabFile: File, maxSeqLen: Int = 128): Boolean {
        if (!vocabFile.isFile || vocabFile.length() <= 0L) return false
        val tok = WordPieceTokenizer.fromVocabFile(vocabFile, maxSeqLen)
        val drv = driverFactory()
        if (!drv.initialize(modelFile)) {
            runCatching { drv.release() }
            return false
        }
        driver?.release()
        tokenizer = tok
        driver = drv
        ready = true
        NeuralRuntimeManager.registerLoaded(
            modelId = "memory_minilm_int8",
            runtime = NeuralRuntimeKind.ONNX_RUNTIME,
            estimatedMemoryMb = 32,
            priority = InferencePriority.MEMORY_INDEXING,
            onUnload = { release() }
        )
        return true
    }

    override fun isAvailable(): Boolean = ready && NeuralRuntimeManager.canSchedule(InferencePriority.MEMORY_INDEXING)

    override suspend fun generateEmbedding(text: String): FloatArray? {
        if (!isAvailable()) return null
        val tok = tokenizer ?: return null
        val drv = driver ?: return null
        val encoded = tok.encode(text)
        val startNs = System.nanoTime()
        val vec = drv.encodeTokens(encoded)
        val micros = (System.nanoTime() - startNs) / 1_000L
        NeuralRuntimeManager.recordInference("memory_minilm_int8", micros, active = false)
        return vec
    }

    @Synchronized
    fun release() {
        ready = false
        runCatching { driver?.release() }
        driver = null
        tokenizer = null
    }

    override fun close() = release()
}

/**
 * Selects ONNX dense neural embeddings when downloaded and within 6 GB memory/thermal headroom,
 * degrading gracefully to local Unicode subword hashing without throwing or blocking.
 */
class HybridNeuralEmbeddingProvider(
    private val neuralProvider: OnnxTextEmbeddingProvider,
    private val fallbackProvider: DeterministicLocalEmbeddingProvider = DeterministicLocalEmbeddingProvider(384)
) : EmbeddingProvider {
    override val dimension: Int get() = if (neuralProvider.isAvailable()) neuralProvider.dimension else fallbackProvider.dimension
    override val modelName: String get() = if (neuralProvider.isAvailable()) neuralProvider.modelName else fallbackProvider.modelName
    override fun isAvailable(): Boolean = true

    override suspend fun generateEmbedding(text: String): FloatArray? {
        if (neuralProvider.isAvailable()) {
            val neural = neuralProvider.generateEmbedding(text)
            if (neural != null) return neural
        }
        return fallbackProvider.generateEmbedding(text)
    }
}
