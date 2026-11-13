package com.jarvis.assistant.ui.orb

import com.jarvis.assistant.data.model.*

/** Separate from the legacy ordinal-based OrbState; old modes retain their contracts. */
enum class PlasmaVisualState { IDLE, LISTENING, USER_SPEAKING, PROCESSING, AI_SPEAKING, INTERRUPTED, ERROR, DISCONNECTED }

data class PlasmaPresentation(
    val state: PlasmaVisualState,
    val mic: Float,
    val output: Float,
    val sessionId: Long,
    val interruptionGeneration: Long,
    val timing: AudioTimingConfidence,
    val reason: String?
)

object OrbPresentationMapper {
    fun map(facts: VoicePresentationSnapshot, nowMs: Long): PlasmaPresentation {
        val microphoneReady = facts.active && facts.capturing && !facts.muted && facts.permissionReason == null
        val currentOutput = facts.output.generation == facts.interruptionGeneration
        val playing = facts.active && facts.playing && currentOutput && !facts.interrupted
        val state = when {
            facts.errorReason != null || facts.permissionReason != null -> PlasmaVisualState.ERROR
            !facts.active -> PlasmaVisualState.IDLE
            facts.interrupted -> PlasmaVisualState.INTERRUPTED
            playing -> PlasmaVisualState.AI_SPEAKING
            microphoneReady && facts.confirmedSpeech -> PlasmaVisualState.USER_SPEAKING
            facts.connection != VoiceConnection.LIVE -> PlasmaVisualState.DISCONNECTED
            facts.pendingResponse -> PlasmaVisualState.PROCESSING
            microphoneReady -> PlasmaVisualState.LISTENING
            else -> PlasmaVisualState.IDLE
        }
        return PlasmaPresentation(
            state,
            if (microphoneReady) facts.microphone.fresh(nowMs, facts.interruptionGeneration) else 0f,
            if (playing && facts.timing in setOf(AudioTimingConfidence.PLAYBACK_HEAD, AudioTimingConfidence.HARDWARE_TIMESTAMP))
                facts.output.fresh(nowMs, facts.interruptionGeneration) else 0f,
            facts.sessionId, facts.interruptionGeneration, facts.timing,
            facts.permissionReason ?: facts.errorReason ?: when {
                facts.muted -> "Microphone muted"
                facts.focus == VoiceAudioFocus.LOST -> "Audio focus lost"
                facts.connection == VoiceConnection.RECONNECTING -> "Reconnecting"
                facts.connection == VoiceConnection.CONNECTING -> "Connecting"
                facts.active && facts.connection == VoiceConnection.OFFLINE -> "Cloud connection unavailable"
                facts.active && !facts.capturing -> "Microphone starting"
                else -> null
            }
        )
    }
}
