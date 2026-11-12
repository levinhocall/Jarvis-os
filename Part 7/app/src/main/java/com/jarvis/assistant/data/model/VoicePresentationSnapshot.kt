package com.jarvis.assistant.data.model

/** Display facts owned by the existing conversation service. No capture or session ownership. */
data class VoicePresentationSnapshot(
    val sessionId: Long = 0,
    val active: Boolean = false,
    val connection: VoiceConnection = VoiceConnection.CLOSED,
    val capturing: Boolean = false,
    val muted: Boolean = false,
    val confirmedSpeech: Boolean = false,
    val pendingResponse: Boolean = false,
    val playing: Boolean = false,
    val playbackSource: PlaybackSource = PlaybackSource.NONE,
    val timing: AudioTimingConfidence = AudioTimingConfidence.UNAVAILABLE,
    val microphone: VoiceEnvelope = VoiceEnvelope(),
    val output: VoiceEnvelope = VoiceEnvelope(),
    val interruptionGeneration: Long = 0,
    val interrupted: Boolean = false,
    val focus: VoiceAudioFocus = VoiceAudioFocus.UNKNOWN,
    val permissionReason: String? = null,
    val errorReason: String? = null
)

enum class VoiceConnection { CLOSED, CONNECTING, LIVE, RECONNECTING, OFFLINE }
enum class PlaybackSource { NONE, GEMINI_PCM, ANDROID_TTS, EXTERNAL_TTS }
enum class AudioTimingConfidence { UNAVAILABLE, STATE_ONLY, PLAYBACK_HEAD, HARDWARE_TIMESTAMP }
enum class VoiceAudioFocus { UNKNOWN, GRANTED, DUCKED, LOST }

data class VoiceEnvelope(val level: Float = 0f, val sampledAtMs: Long = 0, val generation: Long = 0) {
    fun fresh(nowMs: Long, expectedGeneration: Long): Float {
        if (generation != expectedGeneration || nowMs < sampledAtMs || !level.isFinite()) return 0f
        val age = nowMs - sampledAtMs
        return level.coerceIn(0f, 1f) * ((300 - age).coerceIn(0, 200) / 200f)
    }
}
