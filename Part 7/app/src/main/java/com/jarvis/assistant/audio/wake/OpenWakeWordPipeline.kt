package com.jarvis.assistant.audio.wake

import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Public OpenWakeWord v0.5.1 models, original bounded Kotlin streaming adapter.
 * Input: existing 16 kHz little-endian PCM16. No audio capture or network ownership.
 * Model provenance and noncommercial weight license: assets/licenses/openwakeword-NOTICE.txt.
 */
class OpenWakeWordPipeline(assets: AssetManager) : AutoCloseable {
    private val interpreters = mutableListOf<Interpreter>()
    private val mel: Interpreter
    private val embedding: Interpreter
    private val classifier: Interpreter
    private var speech: SileroSpeechGate? = null
    val hasRecentSpeech: Boolean get() = speech?.speechRecent == true
    private fun buffer(floats: Int) = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())
    private val raw = FloatArray(1760)
    private var pending = 0
    private val spectrum = FloatArray(76 * 32) { 1f }
    private val features = FloatArray(16 * 96)
    private val rawIn = buffer(1760)
    private val melOut = buffer(8 * 32)
    private val spectrumIn = buffer(76 * 32)
    private val embeddingOut = buffer(96)
    private val classifierIn = buffer(16 * 96)
    private val scoreOut = buffer(1)
    private var closed = false

    init {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Load wake models off-main." }
        try {
            fun load(name: String, sha: String): Interpreter {
                val bytes = assets.open("openwakeword/$name").use { it.readBytes() }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                check(digest == sha) { "Wake model integrity failed: $name" }
                val data = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
                return Interpreter(data, Interpreter.Options().setNumThreads(1)).also(interpreters::add)
            }
            mel = load("melspectrogram.tflite", "96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8")
            mel.resizeInput(0, intArrayOf(1,1760)); mel.allocateTensors()
            embedding = load("embedding_model.tflite", "c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8")
            classifier = load("hey_jarvis_v0.1.tflite", "14bff778604985e1b5c19f0f7bbe477a69cf281d8db34b232b3b972411f710e2")
            check(mel.getOutputTensor(0).numElements() == 256)
            check(embedding.getInputTensor(0).shape().contentEquals(intArrayOf(1,76,32,1)))
            check(embedding.getOutputTensor(0).numElements() == 96)
            check(classifier.getInputTensor(0).shape().contentEquals(intArrayOf(1,16,96)))
            speech = SileroSpeechGate(assets)
        } catch (error: Throwable) {
            interpreters.forEach { runCatching { it.close() } }
            throw error
        }
    }

    /** Returns null until an 80 ms frame is complete; input chunks must be <= 80 ms. */
    @Synchronized fun accept(pcm: ByteArray): Float? {
        if (closed) return null
        require(pcm.size % 2 == 0 && pcm.size <= 2560)
        speech?.accept(pcm)
        var score: Float? = null
        for (i in pcm.indices step 2) {
            raw[480 + pending++] = (((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort()).toFloat()
            if (pending == 1280) {
                rawIn.rewind(); for (v in raw) rawIn.putFloat(v); rawIn.rewind(); melOut.rewind()
                mel.run(rawIn, melOut)
                System.arraycopy(spectrum, 256, spectrum, 0, spectrum.size - 256)
                for (j in 0 until 256) spectrum[spectrum.size-256+j] = melOut.getFloat(j*4) / 10f + 2f
                spectrumIn.rewind(); for (v in spectrum) spectrumIn.putFloat(v); spectrumIn.rewind(); embeddingOut.rewind()
                embedding.run(spectrumIn, embeddingOut)
                System.arraycopy(features, 96, features, 0, features.size - 96)
                for (j in 0 until 96) features[features.size-96+j] = embeddingOut.getFloat(j*4)
                classifierIn.rewind(); for (v in features) classifierIn.putFloat(v); classifierIn.rewind(); scoreOut.rewind()
                classifier.run(classifierIn, scoreOut)
                score = scoreOut.getFloat(0).takeIf { it.isFinite() }?.coerceIn(0f,1f)
                System.arraycopy(raw, 1280, raw, 0, 480)
                pending = 0
            }
        }
        return score
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        speech?.close(); speech = null
        interpreters.forEach { it.close() }
    }
}
