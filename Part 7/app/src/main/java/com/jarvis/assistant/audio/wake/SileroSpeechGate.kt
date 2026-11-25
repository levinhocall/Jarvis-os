package com.jarvis.assistant.audio.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * Official Silero v4 model (`snakers4/silero-vad v4.0` MIT) aligned to canonical
 * 512-sample (32 ms @ 16 kHz) windows with explicit `(h, c)` recurrent state reset
 * and adaptive playback-aware hysteresis filtering.
 */
internal class SileroSpeechGate(assets: AssetManager) : com.jarvis.assistant.audio.PcmSpeechDetector {
    companion object {
        const val FRAME_SAMPLES = SileroVadStateMachine.FRAME_SAMPLES // 512 (32 ms @ 16 kHz)
        private const val STATE_SIZE = 2 * 1 * 64 // 128 floats for (2, 1, 64) h/c states
        const val MODEL_SHA256 = "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"
    }

    private val owned = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> own(value: T): T = value.also { owned.add(it) }
    private val environment = OrtEnvironment.getEnvironment()
    private fun floats(count: Int) = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val audio = floats(FRAME_SAMPLES)
    private val hidden = floats(STATE_SIZE)
    private val cell = floats(STATE_SIZE)
    private val session: OrtSession
    private val inputs: Map<String, OnnxTensor>
    private val stateMachine = SileroVadStateMachine()
    private var pending = 0
    private var samples = 0L
    private var closed = false
    @Volatile private var probability = 0f
    @Volatile private var lastDecision = SileroVadStateMachine.Decision(0f, 0f, false, false, false, 0.45f, 0.31f, 0.06f)

    val speechRecent: Boolean get() = stateMachine.isSpeechRecent(samples)

    init {
        try {
            val model = assets.open("openwakeword/silero_vad.onnx").use { it.readBytes() }
            check(MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) } == MODEL_SHA256) {
                "Silero model integrity failed"
            }
            session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                own(environment.createSession(model, options))
            }
            inputs = mapOf(
                "input" to own(OnnxTensor.createTensor(environment, audio, longArrayOf(1, FRAME_SAMPLES.toLong()))),
                "h" to own(OnnxTensor.createTensor(environment, hidden, longArrayOf(2, 1, 64))),
                "c" to own(OnnxTensor.createTensor(environment, cell, longArrayOf(2, 1, 64))),
                "sr" to own(OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()))
            )
        } catch (error: Throwable) {
            owned.asReversed().forEach { runCatching { it.close() } }
            throw error
        }
    }

    @Synchronized
    override fun accept(pcm: ByteArray): Float = accept(pcm, playbackActive = false)

    @Synchronized
    override fun accept(pcm: ByteArray, playbackActive: Boolean): Float {
        if (closed) return 0f
        require(pcm.size % 2 == 0)
        for (i in pcm.indices step 2) {
            val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort()
            audio.put(pending++, sample / 32768f)
            samples++
            if (pending == FRAME_SAMPLES) {
                session.run(inputs).use { result ->
                    val rawProb = (result[0] as OnnxTensor).floatBuffer.get(0)
                    val h = (result[1] as OnnxTensor).floatBuffer
                    val c = (result[2] as OnnxTensor).floatBuffer
                    for (j in 0 until STATE_SIZE) {
                        hidden.put(j, h.get(j))
                        cell.put(j, c.get(j))
                    }
                    val decision = stateMachine.recordFrameProbability(rawProb, samples, playbackActive)
                    lastDecision = decision
                    probability = decision.rawProbability
                }
                pending = 0
            }
        }
        return probability
    }

    override fun isSpeechActive(): Boolean = !closed && lastDecision.isSpeechActive

    @Synchronized
    override fun reset() {
        if (closed) return
        for (j in 0 until STATE_SIZE) {
            hidden.put(j, 0f)
            cell.put(j, 0f)
        }
        audio.clear()
        pending = 0
        samples = 0L
        probability = 0f
        stateMachine.reset()
        lastDecision = SileroVadStateMachine.Decision(0f, 0f, false, false, false, 0.45f, 0.31f, 0.06f)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        owned.asReversed().forEach { runCatching { it.close() } }
        // OrtEnvironment is process-shared; never close another consumer's environment.
    }
}
