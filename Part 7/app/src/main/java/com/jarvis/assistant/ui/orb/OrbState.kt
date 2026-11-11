package com.jarvis.assistant.ui.orb

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class OrbState { IDLE, WAKE, LISTENING, THINKING, SPEAKING, ERROR, MUTED, OFFLINE }

@Immutable
data class OrbAudioLevels(val rms: Float = 0f, val bass: Float = 0f, val mid: Float = 0f, val treble: Float = 0f) {
    fun sanitized() = OrbAudioLevels(clean(rms), clean(bass), clean(mid), clean(treble))
    companion object {
        val Zero = OrbAudioLevels()
        internal fun clean(v: Float) = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
    }
}

@Immutable
data class OrbUiState(val state: OrbState = OrbState.IDLE, val levels: OrbAudioLevels = OrbAudioLevels.Zero)

@Immutable
data class OrbPalette(val core: Color, val rim: Color, val accent: Color, val warn: Color) {
    companion object {
        val Cyan = OrbPalette(Color(0xFF84F5FF), Color(0xFF12BAFF), Color(0xFF8268F5), Color(0xFFFF654F))
        val Amber = OrbPalette(Color(0xFFFFE1A1), Color(0xFFFFAA35), Color(0xFFFF7147), Color(0xFFFF524F))
        val Violet = OrbPalette(Color(0xFFE1C8FF), Color(0xFFAE72FF), Color(0xFF41CBFF), Color(0xFFFF705F))
        val Emerald = OrbPalette(Color(0xFFBEFFE0), Color(0xFF2DE4A4), Color(0xFF42AEE7), Color(0xFFFF704E))
    }
}
