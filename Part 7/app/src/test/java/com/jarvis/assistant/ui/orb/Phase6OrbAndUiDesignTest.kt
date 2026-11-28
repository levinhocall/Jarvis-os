package com.jarvis.assistant.ui.orb

import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.model.VoiceConnection
import com.jarvis.assistant.data.model.VoicePresentationSnapshot
import com.jarvis.assistant.ui.theme.resolveJarvisColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6OrbAndUiDesignTest {

    @Test
    fun allTwelvePresetsMapDistinctShaderIndicesAndPreserveLegacyKeys() {
        assertEquals(12, JarvisOrbPreset.entries.size)
        val shaderIndices = JarvisOrbPreset.entries.map { it.shaderIndex }.toSet()
        assertEquals(12, shaderIndices.size)
        assertEquals((0..11).toSet(), shaderIndices)

        // Legacy key backward compatibility
        assertEquals(JarvisOrbPreset.NEURAL_CORE, JarvisOrbPreset.fromPreference("classic"))
        assertEquals(JarvisOrbPreset.JARVIS_ARC_3D, JarvisOrbPreset.fromPreference("aura"))
        assertEquals(JarvisOrbPreset.HUD_RED, JarvisOrbPreset.fromPreference("crimson"))
        assertEquals(JarvisOrbPreset.ULTRON_3D, JarvisOrbPreset.fromPreference("midnight"))
        assertEquals(JarvisOrbPreset.SIGNATURE_2047, JarvisOrbPreset.fromPreference("unknown_value"))
    }

    @Test
    fun eightColorThemesProduceNormalizedRgbStopVectors() {
        assertEquals(8, JarvisOrbColorTheme.entries.size)
        for (theme in JarvisOrbColorTheme.entries) {
            for (rgb in listOf(theme.primaryRgb(), theme.secondaryRgb(), theme.coreRgb())) {
                assertEquals(3, rgb.size)
                assertTrue(rgb.all { it in 0f..1f })
            }
        }
        assertEquals(JarvisOrbColorTheme.SOLAR_GOLD, JarvisOrbColorTheme.fromPreference("solar_gold"))
    }

    @Test
    fun orbStateSmootherInterpolatesAndProduces18FrameBlinkWindow() {
        val smoother = OrbStateSmoother(stepAlpha = 0.04f)
        repeat(245) {
            smoother.step(
                targetAudio = 0.5f,
                targetBass = 0.4f,
                targetMid = 0.3f,
                targetTreble = 0.2f,
                motionState = JarvisOrbMotionState.LISTENING,
                blinkEnabled = true
            )
        }
        assertTrue("Smoothed audio should approach target", smoother.smoothedAudio > 0.45f)
        assertTrue("Frame 245 falls inside 18-frame blink pulse [240..257]", smoother.blinkAmount > 0.1f)

        // Thinking never blinks
        smoother.step(0f, 0f, 0f, 0f, JarvisOrbMotionState.THINKING, blinkEnabled = true)
        assertEquals(0f, smoother.blinkAmount, 0f)
    }

    @Test
    fun resolveOrbMotionStateMatchesSessionAndThemeTokensContrast() {
        val live = VoicePresentationSnapshot(
            active = true,
            capturing = true,
            connection = VoiceConnection.LIVE
        )
        assertEquals(
            JarvisOrbMotionState.LISTENING,
            JarvisOrbMotionState.resolve(true, false, false, ConversationState.LISTENING, live)
        )
        assertEquals(
            JarvisOrbMotionState.SPEAKING,
            JarvisOrbMotionState.resolve(true, false, false, ConversationState.SPEAKING, live.copy(playing = true))
        )
        assertEquals(
            JarvisOrbMotionState.IDLE,
            JarvisOrbMotionState.resolve(false, false, false, ConversationState.IDLE, VoicePresentationSnapshot())
        )

        val darkTokens = resolveJarvisColors(themeKey = "dark")
        val lightTokens = resolveJarvisColors(themeKey = "light")
        assertTrue(darkTokens.isDark)
        assertFalse(lightTokens.isDark)
        assertNotEquals(darkTokens.background, lightTokens.background)
    }
}
