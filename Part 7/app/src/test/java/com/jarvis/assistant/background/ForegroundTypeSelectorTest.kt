package com.jarvis.assistant.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTypeSelectorTest {

    @Test
    fun testLegacySdk_returnsNone() {
        val types = ForegroundTypeSelector.selectTypes(
            sdkInt = 28,
            hasMicPermission = true,
            hasActiveMediaProjection = true
        )
        assertEquals(ForegroundTypeSelector.TYPE_NONE, types)
    }

    @Test
    fun testSdk29_noPermissions_returnsMediaPlaybackOnly() {
        val types = ForegroundTypeSelector.selectTypes(
            sdkInt = 29,
            hasMicPermission = false,
            hasActiveMediaProjection = false
        )
        assertEquals(ForegroundTypeSelector.TYPE_MEDIA_PLAYBACK, types)
    }

    @Test
    fun testSdk34_withMicPermissionOnly_includesMicAndPlayback() {
        val types = ForegroundTypeSelector.selectTypes(
            sdkInt = 34,
            hasMicPermission = true,
            hasActiveMediaProjection = false
        )
        val expected = ForegroundTypeSelector.TYPE_MEDIA_PLAYBACK or ForegroundTypeSelector.TYPE_MICROPHONE
        assertEquals(expected, types)
        assertTrue(types and ForegroundTypeSelector.TYPE_MICROPHONE != 0)
        assertFalse(types and ForegroundTypeSelector.TYPE_MEDIA_PROJECTION != 0)
    }

    @Test
    fun testSdk34_withMicAndProjection_includesAll() {
        val types = ForegroundTypeSelector.selectTypes(
            sdkInt = 34,
            hasMicPermission = true,
            hasActiveMediaProjection = true
        )
        val expected = ForegroundTypeSelector.TYPE_MEDIA_PLAYBACK or
                ForegroundTypeSelector.TYPE_MICROPHONE or
                ForegroundTypeSelector.TYPE_MEDIA_PROJECTION
        assertEquals(expected, types)
        assertTrue(types and ForegroundTypeSelector.TYPE_MEDIA_PROJECTION != 0)
    }

    @Test
    fun testSdk34_noMicPermission_neverIncludesMicrophone() {
        val types = ForegroundTypeSelector.selectTypes(
            sdkInt = 34,
            hasMicPermission = false,
            hasActiveMediaProjection = true
        )
        assertEquals(0, types and ForegroundTypeSelector.TYPE_MICROPHONE)
        assertEquals(
            ForegroundTypeSelector.TYPE_MEDIA_PLAYBACK or ForegroundTypeSelector.TYPE_MEDIA_PROJECTION,
            types
        )
    }

    @Test
    fun testSafeFallback_returnsMediaPlaybackOnModernSdk() {
        assertEquals(ForegroundTypeSelector.TYPE_MEDIA_PLAYBACK, ForegroundTypeSelector.getSafeFallbackType(34))
        assertEquals(ForegroundTypeSelector.TYPE_NONE, ForegroundTypeSelector.getSafeFallbackType(26))
    }
}
