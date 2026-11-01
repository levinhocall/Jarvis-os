package com.jarvis.assistant.ui.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDecodeSizingTest {
    @Test
    fun smallImageUsesFullResolution() {
        assertEquals(1, ImageDecodeSizing.sampleSize(1920, 1080))
    }

    @Test
    fun largeLandscapeImageIsBounded() {
        assertEquals(2, ImageDecodeSizing.sampleSize(4096, 2160))
    }

    @Test
    fun veryLargePortraitImageIsBounded() {
        assertEquals(4, ImageDecodeSizing.sampleSize(3000, 8000))
    }

    @Test
    fun invalidDimensionsUseSafeDefault() {
        assertEquals(1, ImageDecodeSizing.sampleSize(0, 1000))
        assertEquals(1, ImageDecodeSizing.sampleSize(-1, 1000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveMaximumDimensionIsRejected() {
        ImageDecodeSizing.sampleSize(100, 100, 0)
    }
}
