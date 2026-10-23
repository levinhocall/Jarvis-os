package com.jarvis.assistant.background

import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import com.jarvis.assistant.vision.ScreenCaptureManager
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages MediaProjection setup, frame rate-limiting, and image downsampling
 * for sending real-time screen frames to Gemini Live Multimodal Protocol.
 */
class ScreenCaptureBridge(
    private val captureManager: ScreenCaptureManager = ScreenCaptureManager.getInstance()
) {
    companion object {
        private const val TAG = "ScreenCaptureBridge"
        private const val MIN_FRAME_INTERVAL_MS = 800L
        private const val DEFAULT_JPEG_QUALITY = 65
    }

    val isSharing: StateFlow<Boolean> = captureManager.isSharing
    val hasReceivedFrame: StateFlow<Boolean> = captureManager.hasReceivedFrame

    private val lastCaptureTimestamp = AtomicLong(0L)

    /**
     * Starts screen capture with specified MediaProjection token and display metrics.
     */
    fun startCapture(projection: MediaProjection, metrics: DisplayMetrics): Boolean {
        return try {
            val started = captureManager.startCapture(projection, metrics)
            if (started) {
                Log.i(TAG, "Screen capture bridge activated.")
            } else {
                Log.w(TAG, "Screen capture failed to start.")
            }
            started
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen capture bridge", e)
            false
        }
    }

    /**
     * Stops screen capture and releases projection resources.
     */
    fun stopCapture() {
        try {
            captureManager.stopCapture()
            lastCaptureTimestamp.set(0L)
            Log.i(TAG, "Screen capture bridge stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capture bridge", e)
        }
    }

    /**
     * Captures a rate-limited JPEG snapshot suitable for LLM multimodal perception.
     * Returns null if interval since last frame is below threshold or if sharing is inactive.
     */
    fun captureRateLimitedJpeg(quality: Int = DEFAULT_JPEG_QUALITY, force: Boolean = false): ByteArray? {
        if (!captureManager.isSharing.value) {
            return null
        }

        val now = System.currentTimeMillis()
        val last = lastCaptureTimestamp.get()

        if (!force && (now - last) < MIN_FRAME_INTERVAL_MS) {
            return null
        }

        return try {
            val bytes = captureManager.captureSnapshotJpeg(quality.coerceIn(30, 90))
            if (bytes != null) {
                lastCaptureTimestamp.set(now)
            }
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing downsampled frame", e)
            null
        }
    }

    /**
     * Checks if screen capture is active and ready for streaming.
     */
    fun isReady(): Boolean {
        return captureManager.isSharing.value && captureManager.hasReceivedFrame.value
    }
}
