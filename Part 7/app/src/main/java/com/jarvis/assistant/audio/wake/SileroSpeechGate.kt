package com.jarvis.assistant.audio.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.security.MessageDigest

/** Official Silero v4 model distributed by OpenWakeWord v0.5.1; 30 ms @ 16 kHz.
 * This verifies speech before neural wake acceptance, not speaker identity or echo removal.
 */
internal class SileroSpeechGate(assets: AssetManager) : com.jarvis.assistant.audio.PcmSpeechDetector {
    private val owned = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> own(value: T): T = value.also { owned.add(it) }
    private val environment = OrtEnvironment.getEnvironment()
    private fun floats(count: Int) = ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val audio = floats(480)
    private val hidden = floats(128)
    private val cell = floats(128)
    private val session: OrtSession
    private val inputs: Map<String, OnnxTensor>
    private var pending = 0
    private var samples = 0L
    private var lastSpeech = -1L
    private var closed = false
    private var probability = 0f
    val speechRecent: Boolean get() = lastSpeech >= 0 && samples - lastSpeech <= 8000
    init {
        try {
            val model = assets.open("openwakeword/silero_vad.onnx").use { it.readBytes() }
            check(MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) } ==
                "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28") { "Silero model integrity failed" }
            session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
                own(environment.createSession(model, options))
            }
            inputs = mapOf(
                "input" to own(OnnxTensor.createTensor(environment, audio, longArrayOf(1,480))),
                "h" to own(OnnxTensor.createTensor(environment, hidden, longArrayOf(2,1,64))),
                "c" to own(OnnxTensor.createTensor(environment, cell, longArrayOf(2,1,64))),
                "sr" to own(OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()))
            )
        } catch (error: Throwable) { owned.asReversed().forEach { runCatching { it.close() } }; throw error }
    }
    @Synchronized override fun accept(pcm: ByteArray): Float {
        if (closed) return 0f
        require(pcm.size % 2 == 0)
        for (i in pcm.indices step 2) {
            val sample = ((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort()
            audio.put(pending++, sample / 32768f); samples++
            if (pending == 480) {
                session.run(inputs).use { result ->
                    probability = (result[0] as OnnxTensor).floatBuffer.get(0)
                    if (probability.isFinite() && probability >= .5f) lastSpeech = samples
                    val h = (result[1] as OnnxTensor).floatBuffer
                    val c = (result[2] as OnnxTensor).floatBuffer
                    for (j in 0 until 128) { hidden.put(j,h.get(j)); cell.put(j,c.get(j)) }
                }
                pending = 0
            }
        }
        return probability
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        owned.asReversed().forEach { runCatching { it.close() } }
        // OrtEnvironment is process-shared; never close another consumer's environment.
    }
}
