package com.jarvis.assistant.audio.dtln

import com.jarvis.assistant.audio.AecMetrics
import com.jarvis.assistant.audio.DuplexEchoSession
import com.jarvis.assistant.audio.EchoBackend
import com.jarvis.assistant.audio.FramedDuplexProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class DtlnEchoSuppressorTest {

    private class FakeDtlnBackend : DtlnInferenceBackend {
        override val state1Elements = 512
        override val state2Elements = 512
        var stage1Calls = 0
        var stage2Calls = 0

        override fun runStage1(
            inMag: FloatArray,
            states1: FloatArray,
            lpbMag: FloatArray,
            outMask: FloatArray,
            outStates1: FloatArray
        ) {
            stage1Calls++
            // Idealized WiFi/Wiener-like residual suppression mask simulating Stage 1 LSTM mask output.
            for (k in outMask.indices) {
                val m = inMag[k]
                val l = lpbMag[k]
                outMask[k] = if (l > m * 0.4f && m > 0f) {
                    (m / (m + 4f * l)).coerceIn(0.02f, 1f)
                } else {
                    1f
                }
            }
            for (i in outStates1.indices) outStates1[i] = states1[i] + 0.001f
        }

        override fun runStage2(
            estimatedBlock: FloatArray,
            states2: FloatArray,
            lpbBlock: FloatArray,
            outBlock: FloatArray,
            outStates2: FloatArray
        ) {
            stage2Calls++
            // Stage 2 overlap-add window shaping (rectangular OLA averages 4 overlapping shifts = 0.25 gain).
            for (i in outBlock.indices) {
                outBlock[i] = (estimatedBlock[i] - 0.5f * lpbBlock[i]) * 0.25f
            }
            for (i in outStates2.indices) outStates2[i] = states2[i] + 0.001f
        }

        override fun close() {}
    }

    @Test
    fun realFft512RoundTripAndParsevalEnergyMatchExactMath() {
        val fft = RealFft512()
        val time = FloatArray(RealFft512.FFT_SIZE) { n ->
            0.5f * sin(2.0 * PI * 440.0 * n / 16000.0).toFloat() +
                0.25f * cos(2.0 * PI * 1875.0 * n / 16000.0).toFloat()
        }
        val re = FloatArray(RealFft512.SPECTRUM_BINS)
        val im = FloatArray(RealFft512.SPECTRUM_BINS)
        val mag = FloatArray(RealFft512.SPECTRUM_BINS)
        fft.rfft(time, re, im, mag)

        val timeEnergy = time.sumOf { (it * it).toDouble() }
        // Parseval theorem for real DFT: sum |x[n]|^2 == (1/N) * (|X[0]|^2 + |X[N/2]|^2 + 2*sum_{k=1}^{N/2-1} |X[k]|^2)
        var freqSum = (re[0] * re[0] + im[0] * im[0] + re[256] * re[256] + im[256] * im[256]).toDouble()
        for (k in 1 until 256) {
            freqSum += 2.0 * (re[k] * re[k] + im[k] * im[k])
        }
        val parsevalEnergy = freqSum / RealFft512.FFT_SIZE
        assertEquals(timeEnergy, parsevalEnergy, 1e-2)

        val reconstructed = FloatArray(RealFft512.FFT_SIZE)
        fft.irfft(re, im, reconstructed)
        var maxDiff = 0f
        for (i in time.indices) {
            maxDiff = maxOf(maxDiff, abs(time[i] - reconstructed[i]))
        }
        assertTrue("Expected IRFFT reconstruction error < 1e-4, got $maxDiff", maxDiff < 1e-4f)
    }

    @Test
    fun adaptiveSuppressionEngagesDuringPlaybackBypassWhenQuietAndResetsCleanly() {
        val backend = FakeDtlnBackend()
        val dtln = DtlnEchoSuppressor(backend, mode = DtlnActivationMode.ADAPTIVE_BEHIND_AEC3)

        val input160 = ShortArray(160) { i -> (8000 * sin(2.0 * PI * 500.0 * i / 16000.0)).roundToInt().toShort() }
        val output160 = ShortArray(160)

        // Quiet playback -> DTLN adapts/bypasses transparently without burning LSTM cycles.
        dtln.processCapture16k(input160, output160, null)
        assertArrayEquals(input160, output160)
        assertEquals(0, backend.stage1Calls)
        assertEquals(1L, dtln.diagnostics().bypassedFrames)

        // Active far-end render triggers Stage 1 & Stage 2 across 160 <-> 128 frame alignment.
        val far160 = ShortArray(160) { i -> (12000 * sin(2.0 * PI * 500.0 * i / 16000.0)).roundToInt().toShort() }
        repeat(6) {
            dtln.render16k(far160, delayMs = 20)
            dtln.processCapture16k(input160, output160, AecMetrics(echoReturnLossEnhancementDb = 8.0, activeProcessing = true, renderFrames = 200))
        }
        val diag = dtln.diagnostics()
        assertTrue(diag.active)
        assertTrue("Expected Stage 1 & 2 calls > 5, got s1=${backend.stage1Calls} s2=${backend.stage2Calls}",
            backend.stage1Calls >= 6 && backend.stage2Calls == backend.stage1Calls)
        assertEquals(diag.hopsProcessed, backend.stage1Calls.toLong())

        // Reset clears recurrent state cleanly.
        dtln.reset()
        assertEquals(1L, dtln.diagnostics().resets)
        dtln.close()
    }

    @Test
    fun bundledMitDtlnModelsExistWithVerifiedSha256Checksums() {
        val candidates = listOf(
            File("app/src/main/assets"),
            File("Part 7/app/src/main/assets"),
            File("src/main/assets")
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected assets directory in project", candidates)

        val model1 = File(candidates!!, LiteRtDtlnBackend.STAGE1_ASSET)
        val model2 = File(candidates, LiteRtDtlnBackend.STAGE2_ASSET)
        val license = File(candidates, "licenses/dtln-aec-MIT.txt")
        assertTrue("Missing dtln_aec_128_1.tflite", model1.exists() && model1.length() == 1_996_600L)
        assertTrue("Missing dtln_aec_128_2.tflite", model2.exists() && model2.length() == 5_277_012L)
        assertTrue("Missing dtln-aec MIT license", license.exists() && license.readText().contains("MIT"))

        fun sha256(f: File): String = MessageDigest.getInstance("SHA-256")
            .digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }

        assertEquals(LiteRtDtlnBackend.STAGE1_SHA256, sha256(model1))
        assertEquals(LiteRtDtlnBackend.STAGE2_SHA256, sha256(model2))
    }

    @Test
    fun duplexSessionFallbackAllowsAec3ToRunEvenIfDtlnBackendThrows() {
        val fakeAec = object : EchoBackend {
            override fun render24k(samples: ShortArray, delayMs: Int) {}
            override fun capture16k(input: ShortArray, output: ShortArray) {
                input.copyInto(output)
            }
            override fun reset() {}
            override fun close() {}
        }
        val session = DuplexEchoSession(
            enabled = true,
            factory = { fakeAec },
            allowAecFallback = true,
            dtlnFactory = { throw UnsatisfiedLinkError("liblitert_jni unavailable on JVM") }
        )
        assertTrue(session.initialize())
        assertTrue(session.isAecActive)
        assertFalse(session.isDtlnActive)
        assertNotNull(session.dtlnFallbackReason)
        assertEquals(320, session.capture(ByteArray(320)).size)
        session.close()
    }

    @Test
    fun framedProcessorFeeds24kRenderToDtlnWithConstantLengthAndResidualSuppression() {
        val dtlnBackend = FakeDtlnBackend()
        val dtln = DtlnEchoSuppressor(dtlnBackend, mode = DtlnActivationMode.ALWAYS_ACTIVE)
        val aecPass = object : EchoBackend {
            override fun render24k(samples: ShortArray, delayMs: Int) {}
            override fun capture16k(input: ShortArray, output: ShortArray) {
                input.copyInto(output)
            }
            override fun reset() {}
            override fun close() {}
        }
        val processor = FramedDuplexProcessor(aecPass, dtln)
        val render24k = ByteArray(480)
        val cap16k = ByteArray(320)
        // Generate 500 Hz tone at 24 kHz render and 16 kHz capture to measure residual suppression.
        for (i in 0 until 240) {
            val s = (10000 * sin(2.0 * PI * 500.0 * i / 24000.0)).roundToInt().toShort()
            render24k[i * 2] = s.toByte()
            render24k[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        for (i in 0 until 160) {
            val s = (10000 * sin(2.0 * PI * 500.0 * i / 16000.0)).roundToInt().toShort()
            cap16k[i * 2] = s.toByte()
            cap16k[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }

        // Prime 24k render + capture across 20 continuous 10ms frames -> assert exact 320B invariant every step.
        var inputRms = 0.0
        var lastOutputRms = 0.0
        for (step in 0 until 20) {
            processor.renderAccepted(render24k, 0, render24k.size, delayMs = 20)
            val cleaned = processor.capture(cap16k)
            assertEquals("Expected exact 320-byte frame on step $step", 320, cleaned.size)
            if (step >= 12) {
                var inSq = 0.0
                var outSq = 0.0
                for (i in 0 until 160) {
                    val inS = ((cap16k[i * 2].toInt() and 255) or (cap16k[i * 2 + 1].toInt() shl 8)).toShort().toDouble()
                    val outS = ((cleaned[i * 2].toInt() and 255) or (cleaned[i * 2 + 1].toInt() shl 8)).toShort().toDouble()
                    inSq += inS * inS
                    outSq += outS * outS
                }
                inputRms += sqrt(inSq / 160.0)
                lastOutputRms += sqrt(outSq / 160.0)
            }
        }
        assertTrue("Expected DTLN suppression to attenuate residual echo (in=$inputRms out=$lastOutputRms)",
            lastOutputRms < inputRms * 0.6)
        assertTrue(dtln.diagnostics().hopsProcessed >= 20)
        processor.close()
    }
}
