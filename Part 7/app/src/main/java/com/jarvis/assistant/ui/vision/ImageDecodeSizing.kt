package com.jarvis.assistant.ui.vision

/** Computes a power-of-two BitmapFactory sample size without decoding oversized images. */
internal object ImageDecodeSizing {
    fun sampleSize(width: Int, height: Int, maxDimension: Int = 2048): Int {
        require(maxDimension > 0) { "Maximum image dimension must be positive." }
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }
}
