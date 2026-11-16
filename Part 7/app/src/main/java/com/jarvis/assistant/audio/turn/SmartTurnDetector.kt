package com.jarvis.assistant.audio.turn

import ai.onnxruntime.*
import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

interface TurnEndModel : AutoCloseable { fun probability(pcm: ByteArray): Float }

/** Actual BSD-licensed Smart Turn v3.2 CPU model, not MAYA's unidentified JNI implementation. */
class SmartTurnDetector(assets: AssetManager) : TurnEndModel {
    private val features=WhisperTurnFeatures()
    private val environment=OrtEnvironment.getEnvironment()
    private val input=ByteBuffer.allocateDirect(80*800*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val session: OrtSession
    private val tensor: OnnxTensor
    private var closed=false
    init {
        val bytes=assets.open("turn/smart-turn-v3.2-cpu.onnx").use { it.readBytes() }
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
            "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f") { "Turn model integrity failed" }
        session=OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1);options.setInterOpNumThreads(1)
            environment.createSession(bytes,options)
        }
        try { tensor=OnnxTensor.createTensor(environment,input,longArrayOf(1,80,800)) }
        catch(error: Throwable) { session.close();throw error }
    }
    @Synchronized override fun probability(pcm: ByteArray): Float {
        check(!closed)
        input.rewind();input.put(features.extract(pcm));input.rewind()
        return session.run(mapOf("input_features" to tensor)).use { result ->
            val value=(result[0] as OnnxTensor).floatBuffer.get(0)
            check(value.isFinite() && value in 0f..1f) { "Invalid turn probability" }
            value
        }
    }
    @Synchronized override fun close() {
        if(closed) return
        closed=true
        try { tensor.close() } finally { session.close() }
    }
}
