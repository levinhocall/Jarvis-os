package com.jarvis.assistant.audio

import com.jarvis.assistant.audio.dtln.DtlnActivationMode
import com.jarvis.assistant.audio.dtln.DtlnEchoSuppressor
import com.jarvis.assistant.audio.dtln.DtlnInferenceBackend
import com.jarvis.assistant.audio.wake.SileroVadStateMachine
import com.jarvis.assistant.background.VoicePresentationStore
import com.jarvis.assistant.data.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class Phase1AudioStressAndSoakTest {

    @Test
    fun prolongedDuplexFrameDspAndVadSoakMaintainsInvariantsAndLowLatency() {
        val fakeAec = object : EchoBackend {
            override fun render24k(samples: ShortArray, delayMs: Int) {}
            override fun capture16k(input: ShortArray, output: ShortArray) {
                input.copyInto(output)
            }
            override fun reset() {}
            override fun metrics(): AecMetrics = AecMetrics(
                echoReturnLossDb = 8.0,
                echoReturnLossEnhancementDb = 15.0,
                delayMs = 45,
                activeProcessing = true,
                renderFrames = 100L,
                captureFrames = 100L,
                saturatedCaptureFrames = 0L
            )
            override fun close() {}
        }
        val fakeDtln = object : DtlnInferenceBackend {
            override val state1Elements = 512
            override val state2Elements = 512
            override fun runStage1(
                inMag: FloatArray,
                states1: FloatArray,
                lpbMag: FloatArray,
                outMask: FloatArray,
                outStates1: FloatArray
            ) {
                outMask.fill(0.5f)
                states1.copyInto(outStates1)
            }

            override fun runStage2(
                estimatedBlock: FloatArray,
                states2: FloatArray,
                lpbBlock: FloatArray,
                outBlock: FloatArray,
                outStates2: FloatArray
            ) {
                for (i in outBlock.indices) outBlock[i] = estimatedBlock[i] * 0.25f
                states2.copyInto(outStates2)
            }

            override fun close() {}
        }

        val dtln = DtlnEchoSuppressor(fakeDtln, mode = DtlnActivationMode.ALWAYS_ACTIVE)
        val processor = FramedDuplexProcessor(fakeAec, dtln)
        val vad = SileroVadStateMachine()
        val bargeIn = FullDuplexBargeInController()
        val delayEstimator = AcousticDelayEstimator(AudioOutputRoute.BUILTIN_SPEAKER)

        val renderFrame = ByteArray(480)
        val captureFrame = ByteArray(320)
        for (i in 0 until 240) {
            val s = (7000 * sin(2.0 * PI * 440.0 * i / 24000.0)).roundToInt().toShort()
            renderFrame[i * 2] = s.toByte()
            renderFrame[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        for (i in 0 until 160) {
            val s = (7000 * sin(2.0 * PI * 440.0 * i / 16000.0)).roundToInt().toShort()
            captureFrame[i * 2] = s.toByte()
            captureFrame[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }

        val totalFrames = 2000 // 20 seconds of simulated 10ms full-duplex audio @ 16/24 kHz
        val startNs = System.nanoTime()
        val routes = AudioOutputRoute.values()
        for (idx in 0 until totalFrames) {
            if (idx > 0 && idx % 400 == 0) {
                delayEstimator.onRouteChanged(routes[(idx / 400) % routes.size])
                processor.requestReset()
            }
            val delayMs = delayEstimator.computeRenderDelayMs(
                nowNs = startNs + idx * 10_000_000L,
                writtenFrames = (idx + 2).toLong() * 240L,
                fallbackPlaybackHead = idx.toLong() * 240L,
                sampleRateHz = 24_000
            )
            assertTrue("Delay out of bounds: $delayMs", delayMs in 0..500)
            processor.renderAccepted(renderFrame, 0, renderFrame.size, delayMs)
            val cleaned = processor.capture(captureFrame)
            assertEquals("Soak invariant violated at frame $idx", 320, cleaned.size)

            if (idx % 3 == 0) {
                val decision = vad.recordFrameProbability(
                    rawProbability = if (idx % 100 < 30) 0.72f else 0.12f,
                    totalSamplesProcessed = idx.toLong() * 160L,
                    playbackActive = true
                )
                bargeIn.evaluateDuringPlayback(
                    FullDuplexBargeInController.FrameInput(
                        nowMs = 1000L + idx * 10L,
                        lastPlaybackEndTimeMs = 0L,
                        isPlaybackActive = true,
                        hasPcmReference = true,
                        playbackSource = PlaybackSource.GEMINI_PCM,
                        isAecActive = true,
                        isDtlnActive = true,
                        hasSpeechDetector = true,
                        speechProbability = decision.effectiveProbability,
                        isSpeechConfirmed = decision.isSpeechActive,
                        aecMetrics = processor.metrics()
                    )
                )
            }
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val avgUsPerFrame = (elapsedMs * 1000.0) / totalFrames
        assertTrue("Expected < 2000 us avg frame time for pure DSP wrapper soak, got $avgUsPerFrame us", avgUsPerFrame < 2000.0)
        assertTrue(dtln.diagnostics().resets >= 4)
        processor.close()
    }

    @Test
    fun rapidBargeInFlushesNeverLeakStaleFramesOrPresentationState() {
        val player = AudioPlayer({}, {})
        val store = VoicePresentationStore()
        val sessionId = store.begin(muted = false)
        try {
            val chunk = ByteArray(4800) { 0x15 }
            repeat(40) { cycle ->
                val epoch = (cycle + 1).toLong()
                assertTrue(player.enqueuePcm(chunk, sampleRateHz = 24_000, epoch = player.activeEpoch.get(), source = PlaybackSource.ANDROID_TTS))
                assertTrue(player.isPlaying())
                player.flush(epoch)
                store.interrupt(sessionId)
                assertFalse(player.isPlaying())
                assertFalse(store.snapshots.value.playing)
                assertFalse(store.snapshots.value.hasPcmReference)
            }
        } finally {
            store.stop(sessionId)
            player.release()
        }
    }

    @Test
    fun pcmResamplingSoakHandlesDiverseRatesWithoutDrift() {
        val rates = intArrayOf(8000, 16000, 22050, 44100, 48000)
        repeat(50) { i ->
            val sr = rates[i % rates.size]
            val samples20ms = sr / 50
            val mono = ByteArray(samples20ms * 2) { (it * 3).toByte() }
            val out24k = PcmAudioConverter.to24kMonoPcm16(mono, sr, channels = 1)
            val back16k = PcmAudioConverter.resample24kTo16k(out24k)
            assertTrue("Expected non-empty 24k output for $sr Hz", out24k.size in 900..1000)
            assertTrue("Expected non-empty 16k output for $sr Hz", back16k.size in 600..680)
        }
    }
}
