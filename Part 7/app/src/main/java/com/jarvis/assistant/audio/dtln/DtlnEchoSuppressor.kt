package com.jarvis.assistant.audio.dtln

import android.content.res.AssetManager
import com.jarvis.assistant.audio.AecMetrics
import com.jarvis.assistant.neural.LiteRtMultiSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dual-stage inference backend contract isolating LiteRT from pure-Kotlin spectral/OLA tests.
 */
interface DtlnInferenceBackend : AutoCloseable {
    val state1Elements: Int
    val state2Elements: Int

    fun runStage1(
        inMag: FloatArray,
        states1: FloatArray,
        lpbMag: FloatArray,
        outMask: FloatArray,
        outStates1: FloatArray
    )

    fun runStage2(
        estimatedBlock: FloatArray,
        states2: FloatArray,
        lpbBlock: FloatArray,
        outBlock: FloatArray,
        outStates2: FloatArray
    )
}

/**
 * Production Android LiteRT backend executing official MIT `breizhn/DTLN-aec`
 * (`dtln_aec_128_1.tflite` + `dtln_aec_128_2.tflite`) with SHA-256 verification and
 * preallocated direct ByteBuffers (zero per-frame heap allocation).
 */
class LiteRtDtlnBackend(
    assets: AssetManager,
    threads: Int = 1
) : DtlnInferenceBackend {

    companion object {
        const val STAGE1_ASSET = "dtln/dtln_aec_128_1.tflite"
        const val STAGE2_ASSET = "dtln/dtln_aec_128_2.tflite"
        const val STAGE1_SHA256 = "8d241b3a732af8ca140b2e30043e56a6c3c7800c46e22c26f4eea2f70974ad1e"
        const val STAGE2_SHA256 = "350bb01a1152ae3cabe09fe5e868ef2f7d8b988a9f22aae44f140195f6493126"

        private fun directFloatBuffer(floatCount: Int): ByteBuffer =
            ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder())

        private fun putFloats(buf: ByteBuffer, src: FloatArray, count: Int) {
            buf.rewind()
            for (i in 0 until count) buf.putFloat(src[i])
            buf.rewind()
        }

        private fun getFloats(buf: ByteBuffer, dst: FloatArray, count: Int) {
            buf.rewind()
            for (i in 0 until count) dst[i] = buf.getFloat()
        }

        private fun loadVerifiedModel(assets: AssetManager, path: String, expectedSha256: String): ByteBuffer {
            val bytes = assets.open(path).use { it.readBytes() }
            val actualSha = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            check(actualSha == expectedSha256) {
                "DTLN model integrity failed for $path (expected=$expectedSha256 actual=$actualSha)"
            }
            return ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .apply {
                    put(bytes)
                    rewind()
                }
        }
    }

    private val stage1: LiteRtMultiSession
    private val stage2: LiteRtMultiSession

    override val state1Elements: Int
    override val state2Elements: Int

    private val s1InMagBuf = directFloatBuffer(RealFft512.SPECTRUM_BINS)
    private val s1StateInBuf: ByteBuffer
    private val s1LpbMagBuf = directFloatBuffer(RealFft512.SPECTRUM_BINS)
    private val s1MaskOutBuf = directFloatBuffer(RealFft512.SPECTRUM_BINS)
    private val s1StateOutBuf: ByteBuffer

    private val s2EstInBuf = directFloatBuffer(RealFft512.FFT_SIZE)
    private val s2StateInBuf: ByteBuffer
    private val s2LpbInBuf = directFloatBuffer(RealFft512.FFT_SIZE)
    private val s2OutBlockBuf = directFloatBuffer(RealFft512.FFT_SIZE)
    private val s2StateOutBuf: ByteBuffer

    private val s1Inputs: Array<ByteBuffer>
    private val s1Outputs: Array<ByteBuffer>
    private val s2Inputs: Array<ByteBuffer>
    private val s2Outputs: Array<ByteBuffer>

    @Volatile
    private var closed = false

    init {
        var s1: LiteRtMultiSession? = null
        var s2: LiteRtMultiSession? = null
        try {
            s1 = LiteRtMultiSession(loadVerifiedModel(assets, STAGE1_ASSET, STAGE1_SHA256), threads)
            s2 = LiteRtMultiSession(loadVerifiedModel(assets, STAGE2_ASSET, STAGE2_SHA256), threads)

            check(s1.inputCount() == 3 && s1.outputCount() == 2) {
                "Unexpected Stage1 tensor counts: in=${s1.inputCount()} out=${s1.outputCount()}"
            }
            check(s1.inputElements(0) == RealFft512.SPECTRUM_BINS && s1.inputElements(2) == RealFft512.SPECTRUM_BINS) {
                "Unexpected Stage1 FFT bin count"
            }
            check(s2.inputCount() == 3 && s2.outputCount() == 2) {
                "Unexpected Stage2 tensor counts: in=${s2.inputCount()} out=${s2.outputCount()}"
            }
            check(s2.inputElements(0) == RealFft512.FFT_SIZE && s2.inputElements(2) == RealFft512.FFT_SIZE) {
                "Unexpected Stage2 block length"
            }

            state1Elements = s1.inputElements(1)
            state2Elements = s2.inputElements(1)
            check(state1Elements == s1.outputElements(1) && state2Elements == s2.outputElements(1)) {
                "Mismatched DTLN recurrent state tensor size"
            }

            s1StateInBuf = directFloatBuffer(state1Elements)
            s1StateOutBuf = directFloatBuffer(state1Elements)
            s2StateInBuf = directFloatBuffer(state2Elements)
            s2StateOutBuf = directFloatBuffer(state2Elements)

            s1Inputs = arrayOf(s1InMagBuf, s1StateInBuf, s1LpbMagBuf)
            s1Outputs = arrayOf(s1MaskOutBuf, s1StateOutBuf)
            s2Inputs = arrayOf(s2EstInBuf, s2StateInBuf, s2LpbInBuf)
            s2Outputs = arrayOf(s2OutBlockBuf, s2StateOutBuf)

            stage1 = s1
            stage2 = s2
        } catch (error: Throwable) {
            runCatching { s1?.close() }
            runCatching { s2?.close() }
            throw error
        }
    }

    override fun runStage1(
        inMag: FloatArray,
        states1: FloatArray,
        lpbMag: FloatArray,
        outMask: FloatArray,
        outStates1: FloatArray
    ) {
        if (closed) return
        putFloats(s1InMagBuf, inMag, RealFft512.SPECTRUM_BINS)
        putFloats(s1StateInBuf, states1, state1Elements)
        putFloats(s1LpbMagBuf, lpbMag, RealFft512.SPECTRUM_BINS)
        stage1.runMultiple(s1Inputs, s1Outputs)
        getFloats(s1MaskOutBuf, outMask, RealFft512.SPECTRUM_BINS)
        getFloats(s1StateOutBuf, outStates1, state1Elements)
    }

    override fun runStage2(
        estimatedBlock: FloatArray,
        states2: FloatArray,
        lpbBlock: FloatArray,
        outBlock: FloatArray,
        outStates2: FloatArray
    ) {
        if (closed) return
        putFloats(s2EstInBuf, estimatedBlock, RealFft512.FFT_SIZE)
        putFloats(s2StateInBuf, states2, state2Elements)
        putFloats(s2LpbInBuf, lpbBlock, RealFft512.FFT_SIZE)
        stage2.runMultiple(s2Inputs, s2Outputs)
        getFloats(s2OutBlockBuf, outBlock, RealFft512.FFT_SIZE)
        getFloats(s2StateOutBuf, outStates2, state2Elements)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { stage1.close() }
        runCatching { stage2.close() }
    }
}

enum class DtlnActivationMode {
    ADAPTIVE_BEHIND_AEC3,
    ALWAYS_ACTIVE
}

data class DtlnDiagnostics(
    val active: Boolean,
    val hopsProcessed: Long,
    val bypassedFrames: Long,
    val resets: Long,
    val lastAverageMask: Float,
    val delayedRenderSamples: Int
)

/**
 * Dual-Stage DTLN-aec Neural Residual Echo Suppressor (`block_len = 512`, `block_shift = 128` @ 16 kHz).
 *
 * Bridges 10 ms (160-sample) AEC3 frames into 8 ms (128-sample) DTLN overlap-add hops with constant
 * 8 ms algorithmic delay invariant (`micPending + outputAvailable == 128`), manages Stage 1 & Stage 2
 * LSTM states cleanly across resets/route switches, and adaptively bypasses neural passes when
 * playback is silent so background CPU and speech naturalness stay optimal on 6 GB RAM devices.
 */
class DtlnEchoSuppressor(
    private val backend: DtlnInferenceBackend,
    private val mode: DtlnActivationMode = DtlnActivationMode.ADAPTIVE_BEHIND_AEC3
) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val BLOCK_LEN = RealFft512.FFT_SIZE     // 512 samples (32 ms)
        const val BLOCK_SHIFT = 128                    // 128 samples (8 ms)
        const val AEC_FRAME_SAMPLES = 160              // 160 samples (10 ms)
        private const val SPECTRUM_BINS = RealFft512.SPECTRUM_BINS // 257
        private const val FAR_RING_CAPACITY = 24_000   // 1.5s circular loopback buffer @ 16 kHz
        private const val MAX_SYNC_DRIFT_SAMPLES = 480 // 30 ms drift bound before cursor resync
        private const val PLAYBACK_TAIL_FRAMES = 20    // 200 ms tail hold after playback stops
        private const val ERLE_BYPASS_THRESHOLD_DB = 24.0
    }

    private val fft = RealFft512()

    // Stage-1 & Stage-2 LSTM recurrent state buffers
    private val states1 = FloatArray(backend.state1Elements)
    private val nextStates1 = FloatArray(backend.state1Elements)
    private val states2 = FloatArray(backend.state2Elements)
    private val nextStates2 = FloatArray(backend.state2Elements)

    // Sliding 512-sample DTLN analysis/synthesis windows
    private val inBufferMic = FloatArray(BLOCK_LEN)
    private val inBufferLpb = FloatArray(BLOCK_LEN)
    private val olaBuffer = FloatArray(BLOCK_LEN)

    // Scratch arrays reused on every 128-sample hop (0 heap allocations)
    private val fftRe = FloatArray(SPECTRUM_BINS)
    private val fftIm = FloatArray(SPECTRUM_BINS)
    private val inMag = FloatArray(SPECTRUM_BINS)
    private val lpbRe = FloatArray(SPECTRUM_BINS)
    private val lpbIm = FloatArray(SPECTRUM_BINS)
    private val lpbMag = FloatArray(SPECTRUM_BINS)
    private val outMask = FloatArray(SPECTRUM_BINS)
    private val maskedRe = FloatArray(SPECTRUM_BINS)
    private val maskedIm = FloatArray(SPECTRUM_BINS)
    private val estBlock = FloatArray(BLOCK_LEN)
    private val outBlock = FloatArray(BLOCK_LEN)

    // Streaming 160 <-> 128 sample adapter keeping exact constant 128-sample delay
    private val pendingMic = FloatArray(AEC_FRAME_SAMPLES + BLOCK_SHIFT)
    private val pendingLpb = FloatArray(AEC_FRAME_SAMPLES + BLOCK_SHIFT)
    private var pendingCount = 0

    private val outRing = FloatArray(BLOCK_LEN)
    private var outHead = 0
    private var outTail = 0
    private var outCount = 0

    // Delay-compensated circular far-end playback reference
    private val farRing = FloatArray(FAR_RING_CAPACITY)
    private var farWriteAbs = 0L
    private var farReadAbs = 0L
    private var latestDelaySamples = 0
    private val alignedLpbFrame = FloatArray(AEC_FRAME_SAMPLES)

    // Adaptive gating & smooth crossfade
    private var tailFramesRemaining = 0
    private var blendAlpha = 0f
    private var lastAvgMask = 1f
    private var hopsProcessed = 0L
    private var bypassedFrames = 0L
    private var resetCount = 0L
    @Volatile
    private var closed = false

    init {
        resetStateInternal(countReset = false)
    }

    @Synchronized
    fun reset() {
        if (closed) return
        resetStateInternal(countReset = true)
    }

    private fun resetStateInternal(countReset: Boolean) {
        states1.fill(0f)
        nextStates1.fill(0f)
        states2.fill(0f)
        nextStates2.fill(0f)
        inBufferMic.fill(0f)
        inBufferLpb.fill(0f)
        olaBuffer.fill(0f)
        pendingMic.fill(0f)
        pendingLpb.fill(0f)
        pendingCount = 0

        farRing.fill(0f)
        farWriteAbs = 0L
        farReadAbs = 0L
        latestDelaySamples = 0

        // Seed exact 128-sample (8 ms) OLA latency so pendingCount + outCount == 128 invariant holds.
        outRing.fill(0f)
        outHead = 0
        outTail = BLOCK_SHIFT
        outCount = BLOCK_SHIFT

        tailFramesRemaining = 0
        blendAlpha = if (mode == DtlnActivationMode.ALWAYS_ACTIVE) 1f else 0f
        lastAvgMask = 1f
        if (countReset) resetCount++
    }

    /** Records a 10 ms (160-sample @ 16 kHz) far-end speaker reference frame with dynamic hardware delay. */
    @Synchronized
    fun render16k(samples16k: ShortArray, delayMs: Int) {
        if (closed || samples16k.size < AEC_FRAME_SAMPLES) return
        latestDelaySamples = ((delayMs.coerceIn(0, 500) * SAMPLE_RATE_HZ) / 1000)
            .coerceIn(0, FAR_RING_CAPACITY / 2)
        for (i in 0 until AEC_FRAME_SAMPLES) {
            val pos = ((farWriteAbs + i) % FAR_RING_CAPACITY).toInt()
            farRing[pos] = samples16k[i] / 32768f
        }
        farWriteAbs += AEC_FRAME_SAMPLES
        tailFramesRemaining = PLAYBACK_TAIL_FRAMES
    }

    /**
     * Suppresses residual echo on a 10 ms (160-sample @ 16 kHz) PCM frame following AEC3.
     * Writes the enhanced PCM16 samples in-place into [output160].
     */
    @Synchronized
    fun processCapture16k(
        input160: ShortArray,
        output160: ShortArray,
        metrics: AecMetrics? = null
    ) {
        require(input160.size >= AEC_FRAME_SAMPLES && output160.size >= AEC_FRAME_SAMPLES)
        if (closed) {
            for (i in 0 until AEC_FRAME_SAMPLES) output160[i] = input160[i]
            return
        }

        val playbackActive = pullAlignedFarFrame() || tailFramesRemaining > 0
        if (tailFramesRemaining > 0) tailFramesRemaining--

        val strongLinearAec = metrics != null &&
            metrics.activeProcessing &&
            metrics.renderFrames > 80 &&
            metrics.echoReturnLossEnhancementDb >= ERLE_BYPASS_THRESHOLD_DB

        val shouldRunNeural = when (mode) {
            DtlnActivationMode.ALWAYS_ACTIVE -> true
            DtlnActivationMode.ADAPTIVE_BEHIND_AEC3 -> playbackActive && !strongLinearAec
        }

        val targetAlpha = if (shouldRunNeural) 1f else 0f
        val step = 0.25f
        blendAlpha = if (blendAlpha < targetAlpha) {
            min(targetAlpha, blendAlpha + step)
        } else if (blendAlpha > targetAlpha) {
            max(targetAlpha, blendAlpha - step)
        } else {
            blendAlpha
        }

        if (blendAlpha <= 0.001f) {
            bypassedFrames++
            for (i in 0 until AEC_FRAME_SAMPLES) output160[i] = input160[i]
            return
        }

        // Feed 160 samples into sliding adapter and execute all ready 128-sample DTLN hops.
        for (i in 0 until AEC_FRAME_SAMPLES) {
            pendingMic[pendingCount + i] = input160[i] / 32768f
            pendingLpb[pendingCount + i] = alignedLpbFrame[i]
        }
        pendingCount += AEC_FRAME_SAMPLES

        var consumed = 0
        while (pendingCount - consumed >= BLOCK_SHIFT) {
            processHop(pendingMic, pendingLpb, consumed)
            consumed += BLOCK_SHIFT
        }

        val remaining = pendingCount - consumed
        if (remaining > 0 && consumed > 0) {
            System.arraycopy(pendingMic, consumed, pendingMic, 0, remaining)
            System.arraycopy(pendingLpb, consumed, pendingLpb, 0, remaining)
        }
        pendingCount = remaining

        val dryAlpha = 1f - blendAlpha
        for (i in 0 until AEC_FRAME_SAMPLES) {
            val neuralSample = popOutSample()
            val drySample = input160[i] / 32768f
            val mixed = drySample * dryAlpha + neuralSample * blendAlpha
            output160[i] = (mixed * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun pullAlignedFarFrame(): Boolean {
        val targetRead = farWriteAbs - latestDelaySamples
        if (targetRead <= 0L) {
            alignedLpbFrame.fill(0f)
            return false
        }
        if (targetRead - farReadAbs > MAX_SYNC_DRIFT_SAMPLES) {
            farReadAbs = targetRead - AEC_FRAME_SAMPLES
        }
        var hasEnergy = false
        for (i in 0 until AEC_FRAME_SAMPLES) {
            val sample = if (farReadAbs < targetRead && farReadAbs >= 0L) {
                val idx = (farReadAbs % FAR_RING_CAPACITY).toInt()
                farReadAbs++
                farRing[idx]
            } else {
                0f
            }
            if (sample != 0f) hasEnergy = true
            alignedLpbFrame[i] = sample
        }
        return hasEnergy
    }

    private fun processHop(micSrc: FloatArray, lpbSrc: FloatArray, srcOffset: Int) {
        val keep = BLOCK_LEN - BLOCK_SHIFT // 384
        System.arraycopy(inBufferMic, BLOCK_SHIFT, inBufferMic, 0, keep)
        System.arraycopy(micSrc, srcOffset, inBufferMic, keep, BLOCK_SHIFT)

        System.arraycopy(inBufferLpb, BLOCK_SHIFT, inBufferLpb, 0, keep)
        System.arraycopy(lpbSrc, srcOffset, inBufferLpb, keep, BLOCK_SHIFT)

        fft.rfft(inBufferMic, fftRe, fftIm, inMag)
        fft.rfft(inBufferLpb, lpbRe, lpbIm, lpbMag)

        backend.runStage1(inMag, states1, lpbMag, outMask, nextStates1)
        System.arraycopy(nextStates1, 0, states1, 0, states1.size)

        var maskSum = 0f
        for (k in 0 until SPECTRUM_BINS) {
            val m = outMask[k].takeIf { it.isFinite() }?.coerceIn(0f, 1.5f) ?: 0f
            maskedRe[k] = fftRe[k] * m
            maskedIm[k] = fftIm[k] * m
            maskSum += m
        }
        lastAvgMask = maskSum / SPECTRUM_BINS

        fft.irfft(maskedRe, maskedIm, estBlock)

        backend.runStage2(estBlock, states2, inBufferLpb, outBlock, nextStates2)
        System.arraycopy(nextStates2, 0, states2, 0, states2.size)

        System.arraycopy(olaBuffer, BLOCK_SHIFT, olaBuffer, 0, keep)
        olaBuffer.fill(0f, keep, BLOCK_LEN)
        for (i in 0 until BLOCK_LEN) {
            val v = outBlock[i]
            if (v.isFinite()) olaBuffer[i] += v
        }

        for (i in 0 until BLOCK_SHIFT) {
            pushOutSample(olaBuffer[i].coerceIn(-1f, 1f))
        }
        hopsProcessed++
    }

    private fun pushOutSample(value: Float) {
        if (outCount >= outRing.size) return
        outRing[outTail] = value
        outTail = (outTail + 1) % outRing.size
        outCount++
    }

    private fun popOutSample(): Float {
        if (outCount <= 0) return 0f
        val v = outRing[outHead]
        outHead = (outHead + 1) % outRing.size
        outCount--
        return v
    }

    @Synchronized
    fun diagnostics(): DtlnDiagnostics = DtlnDiagnostics(
        active = !closed && blendAlpha > 0.001f,
        hopsProcessed = hopsProcessed,
        bypassedFrames = bypassedFrames,
        resets = resetCount,
        lastAverageMask = lastAvgMask,
        delayedRenderSamples = latestDelaySamples
    )

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { backend.close() }
    }
}
