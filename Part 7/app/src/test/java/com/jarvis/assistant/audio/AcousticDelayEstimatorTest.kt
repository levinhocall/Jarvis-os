package com.jarvis.assistant.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticDelayEstimatorTest {

    @Test
    fun deviceTypesMapToExpectedDelayRoutes() {
        assertEquals(AudioOutputRoute.WIRED_HEADSET, AudioOutputRoute.fromDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(AudioOutputRoute.WIRED_HEADSET, AudioOutputRoute.fromDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(AudioOutputRoute.USB_HEADSET, AudioOutputRoute.fromDeviceType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioOutputRoute.BLE_HEADSET, AudioOutputRoute.fromDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(AudioOutputRoute.BLUETOOTH_SCO, AudioOutputRoute.fromDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(AudioOutputRoute.BUILTIN_SPEAKER, AudioOutputRoute.fromDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(AudioOutputRoute.BUILTIN_SPEAKER, AudioOutputRoute.fromDeviceType(null))
    }

    @Test
    fun hardwareRenderAndCaptureTimestampsComputeExactQueueDelays() {
        val estimator = AcousticDelayEstimator(AudioOutputRoute.BUILTIN_SPEAKER)
        val baseNs = 10_000_000_000L

        // Written 2,400 frames (100 ms at 24 kHz); hardware reported pos=1,200 frames 20 ms ago -> +480 frames -> head=1,680.
        // Remaining render buffer = 720 frames = 30 ms.
        val tsNs = baseNs - 20_000_000L
        assertTrue(estimator.recordRenderTimestamp(1_200L, tsNs, baseNs, writtenFrames = 2_400L, sampleRateHz = 24_000))

        // Capture at 16 kHz: read 1,600 frames; hardware ADC at pos=1,600 15ms ago -> +240 frames = 15 ms queued.
        assertTrue(
            estimator.recordCaptureTimestamp(
                framePosition = 1_600L,
                nanoTime = baseNs - 15_000_000L,
                nowNs = baseNs,
                totalReadFrames = 1_600L,
                sampleRateHz = 16_000
            )
        )

        // Expected total: 30 ms (render) + 15 ms (capture) + 18 ms (speaker offset) = 63 ms.
        val delayMs = estimator.computeRenderDelayMs(
            nowNs = baseNs,
            writtenFrames = 2_400L,
            fallbackPlaybackHead = 0L, // ignored because fresh HW render timestamp is active
            sampleRateHz = 24_000
        )
        assertEquals(63, delayMs)
        val diag = estimator.diagnostics()
        assertTrue(diag.usingRenderHardwareTimestamp)
        assertTrue(diag.usingCaptureHardwareTimestamp)
        assertEquals(30, diag.renderQueueDelayMs)
        assertEquals(15, diag.captureQueueDelayMs)
        assertEquals(18, diag.routeOffsetMs)
    }

    @Test
    fun rejectsStaleOrOutOfBoundsTimestampsAndFallsBackCleanly() {
        val estimator = AcousticDelayEstimator(AudioOutputRoute.BUILTIN_SPEAKER)
        val nowNs = 20_000_000_000L

        assertFalse(estimator.recordRenderTimestamp(-1L, nowNs, nowNs, 1000L, 24_000))
        assertFalse(estimator.recordRenderTimestamp(100L, nowNs + 5_000_000L, nowNs, 1000L, 24_000)) // future
        assertFalse(estimator.recordRenderTimestamp(100L, nowNs - 400_000_000L, nowNs, 1000L, 24_000)) // >250ms old
        assertFalse(estimator.recordRenderTimestamp(2_000L, nowNs, nowNs, 1000L, 24_000)) // ahead of written

        // Fallback uses playbackHead (1440 frames out of 2400 -> 960 frames = 40ms) + 10ms default capture + 18ms speaker = 68ms.
        val delayMs = estimator.computeRenderDelayMs(nowNs, writtenFrames = 2_400L, fallbackPlaybackHead = 1_440L, sampleRateHz = 24_000)
        assertEquals(68, delayMs)
        assertFalse(estimator.diagnostics().usingRenderHardwareTimestamp)
        assertFalse(estimator.diagnostics().usingCaptureHardwareTimestamp)
    }

    @Test
    fun routeSwitchAdaptsImmediatelyAndOutlierSpikesRequirePersistence() {
        val estimator = AcousticDelayEstimator(AudioOutputRoute.BUILTIN_SPEAKER)
        val nowNs = 30_000_000_000L

        // Initial Speaker reading (~48ms): 20ms render + 10ms default capture + 18ms speaker.
        val initial = estimator.computeRenderDelayMs(nowNs, writtenFrames = 480L, fallbackPlaybackHead = 0L, sampleRateHz = 24_000)
        assertEquals(48, initial)

        // Single huge outlier spike (+140ms buffer jump) must be ignored without distorting smoothed estimate.
        val spiked = estimator.computeRenderDelayMs(nowNs + 10_000_000L, writtenFrames = 3_840L, fallbackPlaybackHead = 0L, sampleRateHz = 24_000)
        assertEquals(48, spiked)

        // After 3 consecutive shifted readings, estimator smoothly adapts to the persistent offset.
        estimator.computeRenderDelayMs(nowNs + 20_000_000L, writtenFrames = 3_840L, fallbackPlaybackHead = 0L, sampleRateHz = 24_000)
        val adapted = estimator.computeRenderDelayMs(nowNs + 30_000_000L, writtenFrames = 3_840L, fallbackPlaybackHead = 0L, sampleRateHz = 24_000)
        assertTrue("Expected persistent shift adaptation > 100ms, got $adapted", adapted > 100)

        // Switching route to Bluetooth SCO clears stale state immediately and uses BT SCO profile offset (95ms).
        assertTrue(estimator.onRouteChanged(AudioOutputRoute.BLUETOOTH_SCO))
        assertFalse(estimator.onRouteChanged(AudioOutputRoute.BLUETOOTH_SCO)) // idempotent
        val btDelay = estimator.computeRenderDelayMs(nowNs + 40_000_000L, writtenFrames = 480L, fallbackPlaybackHead = 0L, sampleRateHz = 24_000)
        assertEquals(20 + 10 + 95, btDelay)
        assertEquals(1, estimator.diagnostics().routeSwitches)

        // Switching to wired headset drops delay back to wired profile baseline (~34ms).
        assertTrue(estimator.onRouteChanged(AudioOutputRoute.WIRED_HEADSET))
        val wiredDelay = estimator.computeRenderDelayMs(nowNs + 50_000_000L, writtenFrames = 480L, fallbackPlaybackHead = 0L, sampleRateHz = 24_000)
        assertEquals(20 + 10 + 4, wiredDelay)
    }

    @Test
    fun audioPlayerAcceptsSharedDelayEstimatorSafely() {
        val estimator = AcousticDelayEstimator(AudioOutputRoute.USB_HEADSET)
        val player = AudioPlayer({}, {}, delayEstimator = estimator)
        try {
            player.flush(1L)
            assertEquals(AudioOutputRoute.USB_HEADSET, estimator.diagnostics().route)
        } finally {
            player.release()
        }
    }
}
