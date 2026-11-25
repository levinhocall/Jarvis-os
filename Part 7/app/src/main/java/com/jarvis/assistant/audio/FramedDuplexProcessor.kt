package com.jarvis.assistant.audio

import com.jarvis.assistant.audio.dtln.DtlnEchoSuppressor
import java.util.concurrent.atomic.AtomicBoolean

data class AecMetrics(
    val echoReturnLossDb: Double = 0.0,
    val echoReturnLossEnhancementDb: Double = 0.0,
    val delayMs: Int = 0,
    val activeProcessing: Boolean = false,
    val renderFrames: Long = 0L,
    val captureFrames: Long = 0L,
    val saturatedCaptureFrames: Long = 0L
)

interface EchoBackend : AutoCloseable {
    fun render24k(samples: ShortArray, delayMs: Int)
    fun capture16k(input: ShortArray, output: ShortArray)
    fun reset()
    fun metrics(): AecMetrics? = null
}

/** Synchronized 10 ms frame adapter between arbitrary AudioTrack/AudioRecord chunk sizes and fixed 16/24 kHz AEC3/DTLN. */
class FramedDuplexProcessor(
    private val backend: EchoBackend,
    private val dtln: DtlnEchoSuppressor? = null
) {
    private val render = ShortArray(240)
    private val render16k = ShortArray(160)
    private val capture = ShortArray(160)
    private val aecOut = ShortArray(160)
    private val cleaned = ShortArray(160)
    private var renderCount = 0
    private var captureCount = 0
    private var renderByte = -1
    private var captureByte = -1
    private var closed = false
    private var captureCounter = 0
    private var cachedMetrics: AecMetrics? = null
    private val resetPending = AtomicBoolean(false)

    private fun applyReset() {
        if (resetPending.getAndSet(false)) {
            renderCount = 0
            captureCount = 0
            renderByte = -1
            captureByte = -1
            cachedMetrics = null
            backend.reset()
            dtln?.reset()
        }
    }

    @Synchronized
    fun renderAccepted(pcm: ByteArray, offset: Int, length: Int, delayMs: Int) {
        if (closed) return
        require(offset >= 0 && length >= 0 && offset <= pcm.size - length)
        applyReset()
        val clampedDelay = delayMs.coerceIn(0, 500)
        for (i in offset until offset + length) {
            if (renderByte < 0) renderByte = pcm[i].toInt() and 255
            else {
                render[renderCount++] = (renderByte or (pcm[i].toInt() shl 8)).toShort()
                renderByte = -1
                if (renderCount == 240) {
                    backend.render24k(render, clampedDelay)
                    if (dtln != null) {
                        downsample240To160(render, render16k)
                        dtln.render16k(render16k, clampedDelay)
                    }
                    renderCount = 0
                }
            }
        }
    }

    @Synchronized
    fun capture(pcm: ByteArray): ByteArray {
        if (closed) return byteArrayOf()
        require(pcm.size <= 4096)
        applyReset()
        val samples = (pcm.size + if (captureByte >= 0) 1 else 0) / 2
        val output = ByteArray(((captureCount + samples) / 160) * 320)
        var written = 0
        for (b in pcm) {
            if (captureByte < 0) captureByte = b.toInt() and 255
            else {
                capture[captureCount++] = (captureByte or (b.toInt() shl 8)).toShort()
                captureByte = -1
                if (captureCount == 160) {
                    backend.capture16k(capture, aecOut)
                    if (dtln != null) {
                        if ((captureCounter++ and 3) == 0) {
                            cachedMetrics = backend.metrics()
                        }
                        dtln.processCapture16k(aecOut, cleaned, cachedMetrics)
                    } else {
                        aecOut.copyInto(cleaned)
                    }
                    for (v in cleaned) {
                        output[written++] = v.toByte()
                        output[written++] = (v.toInt() shr 8).toByte()
                    }
                    captureCount = 0
                }
            }
        }
        check(written == output.size)
        return output
    }

    private fun downsample240To160(src240: ShortArray, dst160: ShortArray) {
        // Exact 3:2 linear-filtered decimation: 3 samples @ 24 kHz -> 2 samples @ 16 kHz.
        for (i in 0 until 160) {
            val pos = i * 1.5
            val idx = pos.toInt()
            if (pos == idx.toDouble() || idx + 1 >= 240) {
                dst160[i] = src240[idx]
            } else {
                dst160[i] = ((src240[idx].toInt() + src240[idx + 1].toInt()) / 2).toShort()
            }
        }
    }

    @Synchronized
    fun metrics(): AecMetrics? = if (closed) null else backend.metrics()

    /** Invalidate immediately, rebuild native state on the next audio worker call, not the UI thread. */
    fun requestReset() {
        resetPending.set(true)
    }

    @Synchronized
    fun close() {
        if (!closed) {
            closed = true
            runCatching { dtln?.close() }
            backend.close()
        }
    }
}
