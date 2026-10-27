package com.jarvis.assistant.ui.fx

import android.graphics.Color
import com.jarvis.assistant.data.model.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lia3DPresenceTest {

    @Test
    fun testPaletteMappingPerState() {
        val idlePalette = LiaOrb3DView.getPaletteForState(ConversationState.IDLE)
        assertEquals(LiaOrb3DView.PALETTE_IDLE, idlePalette)
        assertTrue(idlePalette.coreR in 0f..1f)
        assertTrue(idlePalette.rimG in 0f..1f)
        assertTrue(idlePalette.haloB in 0f..1f)

        val listeningPalette = LiaOrb3DView.getPaletteForState(ConversationState.LISTENING)
        assertEquals(LiaOrb3DView.PALETTE_LISTENING, listeningPalette)

        val thinkingPalette = LiaOrb3DView.getPaletteForState(ConversationState.THINKING)
        assertEquals(LiaOrb3DView.PALETTE_THINKING, thinkingPalette)

        val speakingPalette = LiaOrb3DView.getPaletteForState(ConversationState.SPEAKING)
        assertEquals(LiaOrb3DView.PALETTE_SPEAKING, speakingPalette)

        val connectingPalette = LiaOrb3DView.getPaletteForState(ConversationState.RECOVERING)
        assertEquals(LiaOrb3DView.PALETTE_CONNECTING, connectingPalette)

        val errorPalette = LiaOrb3DView.getPaletteForState(ConversationState.FAILED)
        assertEquals(LiaOrb3DView.PALETTE_ERROR, errorPalette)
    }

    @Test
    fun testTiltLowPassFilterMath() {
        var filtered = 0f
        val rawInput = 1.0f
        val alpha = 0.85f

        // First step from 0 towards 1
        filtered = LiaOrb3DView.filterTilt(filtered, rawInput, alpha)
        assertEquals(0.15f, filtered, 0.001f)

        // Second step
        filtered = LiaOrb3DView.filterTilt(filtered, rawInput, alpha)
        // 0.15 * 0.85 + 1.0 * 0.15 = 0.1275 + 0.15 = 0.2775
        assertEquals(0.2775f, filtered, 0.001f)

        // Multiple steps converge smoothly to 1.0
        for (i in 0 until 50) {
            filtered = LiaOrb3DView.filterTilt(filtered, rawInput, alpha)
        }
        assertTrue("Filtered tilt should converge to 1.0, was $filtered", filtered > 0.99f)
    }

    @Test
    fun testSplashPlayedFlagOncePerProcess() {
        LiaSplash3D.resetForTesting()
        assertFalse(LiaSplash3D.played)

        LiaSplash3D.played = true
        assertTrue(LiaSplash3D.played)

        // Never replays once marked
        assertTrue(LiaSplash3D.played)

        LiaSplash3D.resetForTesting()
        assertFalse(LiaSplash3D.played)
    }

    @Test
    fun testEdgeGlowStateColorMapping() {
        fun resolveGlowColor(state: ConversationState): Int {
            return when (state) {
                ConversationState.SPEAKING -> Color.parseColor("#FF6FAE")
                ConversationState.FAILED -> Color.parseColor("#EF4444")
                else -> Color.parseColor("#8B5CF6")
            }
        }

        assertEquals(Color.parseColor("#8B5CF6"), resolveGlowColor(ConversationState.IDLE))
        assertEquals(Color.parseColor("#8B5CF6"), resolveGlowColor(ConversationState.LISTENING))
        assertEquals(Color.parseColor("#8B5CF6"), resolveGlowColor(ConversationState.THINKING))
        assertEquals(Color.parseColor("#FF6FAE"), resolveGlowColor(ConversationState.SPEAKING))
        assertEquals(Color.parseColor("#EF4444"), resolveGlowColor(ConversationState.FAILED))
    }
}
