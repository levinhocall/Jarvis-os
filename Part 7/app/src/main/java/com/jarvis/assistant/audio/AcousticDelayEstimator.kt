package com.jarvis.assistant.audio

import android.media.AudioDeviceInfo
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AudioOutputRoute(
    val label: String,
    val defaultRouteOffsetMs: Int,
    val minDelayMs: Int,
    val maxDelayMs: Int
) {
    BUILTIN_SPEAKER("speaker", 18, 4, 220),
    WIRED_HEADSET("wired_headset", 4, 0, 120),
    USB_HEADSET("usb_headset", 8, 0, 140),
    BLE_HEADSET("ble_headset", 42, 15, 250),
    BLUETOOTH_SCO("bluetooth_sco", 95, 35, 360);

    companion object {
        fun fromDeviceType(deviceType: Int?): AudioOutputRoute = when (deviceType) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> WIRED_HEADSET
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> USB_HEADSET
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> BLE_HEADSET
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> BLUETOOTH_SCO
            else -> BUILTIN_SPEAKER
        }
    }
}

data class DelayDiagnostics(
    val route: AudioOutputRoute,
    val renderQueueDelayMs: Int,
    val captureQueueDelayMs: Int,
    val routeOffsetMs: Int,
    val estimatedTotalDelayMs: Int,
    val usingRenderHardwareTimestamp: Boolean,
    val usingCaptureHardwareTimestamp: Boolean,
    val routeSwitches: Int
)

/**
 * Combines hardware [android.media.AudioTrack] & [android.media.AudioRecord] monotonic timestamps
 * with audio route acoustic/codec latency profiles to produce a bounded, glitch-resistant
 * stream delay hint (`0..500 ms`) for WebRTC AEC3 (`SetStreamDelayMs`).
 */
class AcousticDelayEstimator(
    initialRoute: AudioOutputRoute = AudioOutputRoute.BUILTIN_SPEAKER
) {
    companion object {
        const val MAX_AEC3_DELAY_MS = 500
        private const val MAX_TIMESTAMP_AGE_NS = 250_000_000L
        private const val MAX_CAPTURE_FRESH_NS = 2_000_000_000L
        private const val DEFAULT_CAPTURE_BUFFER_MS = 10
        private const val OUTLIER_JUMP_MS = 65.0
        private const val OUTLIER_CONFIRM_SAMPLES = 3
        private const val EMA_ALPHA = 0.2
    }

    private val lock = Any()
    private var route: AudioOutputRoute = initialRoute
    private var routeSwitches: Int = 0

    private var renderFramePos: Long = -1L
    private var renderTsNanoTime: Long = 0L
    private var renderObservedNowNs: Long = 0L

    private var captureQueueDelayMs: Int? = null
    private var captureObservedNowNs: Long = 0L

    private var smoothedDelayMs: Double = -1.0
    private var pendingShiftSamples: Int = 0
    private var pendingShiftSum: Double = 0.0

    private var lastRenderQueueDelayMs: Int = 0
    private var lastUsingRenderHwTs: Boolean = false
    private var lastUsingCaptureHwTs: Boolean = false
    private var lastEstimatedTotalMs: Int = initialRoute.defaultRouteOffsetMs

    fun onRouteChanged(newRoute: AudioOutputRoute): Boolean = synchronized(lock) {
        if (newRoute == route) return@synchronized false
        route = newRoute
        routeSwitches++
        // Route change alters hardware pipeline & codec latency immediately; clear stale smoothing.
        smoothedDelayMs = -1.0
        pendingShiftSamples = 0
        pendingShiftSum = 0.0
        renderFramePos = -1L
        renderTsNanoTime = 0L
        renderObservedNowNs = 0L
        captureQueueDelayMs = null
        captureObservedNowNs = 0L
        lastEstimatedTotalMs = newRoute.defaultRouteOffsetMs
        true
    }

    fun resetPlayback() = synchronized(lock) {
        renderFramePos = -1L
        renderTsNanoTime = 0L
        renderObservedNowNs = 0L
        smoothedDelayMs = -1.0
        pendingShiftSamples = 0
        pendingShiftSum = 0.0
        lastRenderQueueDelayMs = 0
        lastUsingRenderHwTs = false
    }

    fun resetCapture() = synchronized(lock) {
        captureQueueDelayMs = null
        captureObservedNowNs = 0L
        lastUsingCaptureHwTs = false
    }

    fun recordRenderTimestamp(
        framePosition: Long,
        nanoTime: Long,
        nowNs: Long,
        writtenFrames: Long,
        sampleRateHz: Int
    ): Boolean = synchronized(lock) {
        if (framePosition < 0L || sampleRateHz <= 0) return@synchronized false
        val ageNs = nowNs - nanoTime
        if (ageNs !in 0L..MAX_TIMESTAMP_AGE_NS) return@synchronized false
        val extrapolated = framePosition + ((ageNs * sampleRateHz.toDouble()) / 1_000_000_000.0).toLong()
        if (extrapolated < 0L || extrapolated > writtenFrames) return@synchronized false
        renderFramePos = framePosition
        renderTsNanoTime = nanoTime
        renderObservedNowNs = nowNs
        true
    }

    fun recordCaptureTimestamp(
        framePosition: Long,
        nanoTime: Long,
        nowNs: Long,
        totalReadFrames: Long,
        sampleRateHz: Int
    ): Boolean = synchronized(lock) {
        if (framePosition < 0L || sampleRateHz <= 0) return@synchronized false
        val ageNs = nowNs - nanoTime
        if (ageNs !in 0L..MAX_TIMESTAMP_AGE_NS) return@synchronized false
        val capturedByHw = framePosition + ((ageNs * sampleRateHz.toDouble()) / 1_000_000_000.0).toLong()
        val queuedFrames = (capturedByHw - totalReadFrames).coerceIn(0L, sampleRateHz.toLong())
        val delayMs = ((queuedFrames * 1000L) / sampleRateHz).toInt()
        if (delayMs > 180) return@synchronized false
        val prev = captureQueueDelayMs
        captureQueueDelayMs = if (prev == null) delayMs else ((prev * 3 + delayMs) / 4)
        captureObservedNowNs = nowNs
        true
    }

    fun computeRenderDelayMs(
        nowNs: Long,
        writtenFrames: Long,
        fallbackPlaybackHead: Long,
        sampleRateHz: Int
    ): Int = synchronized(lock) {
        val safeRate = sampleRateHz.coerceAtLeast(8000)
        var usedHwRender = false
        val hwHead: Long? = if (renderFramePos >= 0L && (nowNs - renderObservedNowNs) in 0L..MAX_TIMESTAMP_AGE_NS) {
            val ageSinceTs = nowNs - renderTsNanoTime
            if (ageSinceTs in 0L..MAX_TIMESTAMP_AGE_NS) {
                val projected = renderFramePos + ((ageSinceTs * safeRate.toDouble()) / 1_000_000_000.0).toLong()
                if (projected in 0L..writtenFrames) {
                    usedHwRender = true
                    projected
                } else null
            } else null
        } else null

        val effectiveHead = (hwHead ?: fallbackPlaybackHead).coerceIn(0L, writtenFrames)
        val renderQueueMs = (((writtenFrames - effectiveHead).coerceIn(0L, 12_000L)) * 1000L / safeRate).toInt()

        val captureFresh = captureQueueDelayMs != null && (nowNs - captureObservedNowNs) in 0L..MAX_CAPTURE_FRESH_NS
        val captureMs = if (captureFresh) captureQueueDelayMs!! else DEFAULT_CAPTURE_BUFFER_MS

        val rawDelayMs = (renderQueueMs + captureMs + route.defaultRouteOffsetMs)
            .coerceIn(route.minDelayMs, route.maxDelayMs)
            .coerceIn(0, MAX_AEC3_DELAY_MS)

        if (smoothedDelayMs < 0.0) {
            smoothedDelayMs = rawDelayMs.toDouble()
            pendingShiftSamples = 0
            pendingShiftSum = 0.0
        } else if (abs(rawDelayMs - smoothedDelayMs) > OUTLIER_JUMP_MS) {
            pendingShiftSamples++
            pendingShiftSum += rawDelayMs
            if (pendingShiftSamples >= OUTLIER_CONFIRM_SAMPLES) {
                smoothedDelayMs = pendingShiftSum / pendingShiftSamples
                pendingShiftSamples = 0
                pendingShiftSum = 0.0
            }
        } else {
            pendingShiftSamples = 0
            pendingShiftSum = 0.0
            smoothedDelayMs = smoothedDelayMs * (1.0 - EMA_ALPHA) + rawDelayMs * EMA_ALPHA
        }

        val finalDelayMs = smoothedDelayMs.roundToInt().coerceIn(0, MAX_AEC3_DELAY_MS)
        lastRenderQueueDelayMs = renderQueueMs
        lastUsingRenderHwTs = usedHwRender
        lastUsingCaptureHwTs = captureFresh
        lastEstimatedTotalMs = finalDelayMs
        finalDelayMs
    }

    fun diagnostics(): DelayDiagnostics = synchronized(lock) {
        DelayDiagnostics(
            route = route,
            renderQueueDelayMs = lastRenderQueueDelayMs,
            captureQueueDelayMs = captureQueueDelayMs ?: DEFAULT_CAPTURE_BUFFER_MS,
            routeOffsetMs = route.defaultRouteOffsetMs,
            estimatedTotalDelayMs = lastEstimatedTotalMs,
            usingRenderHardwareTimestamp = lastUsingRenderHwTs,
            usingCaptureHardwareTimestamp = lastUsingCaptureHwTs,
            routeSwitches = routeSwitches
        )
    }
}
