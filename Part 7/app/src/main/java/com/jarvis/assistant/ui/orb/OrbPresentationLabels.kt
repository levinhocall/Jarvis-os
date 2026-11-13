package com.jarvis.assistant.ui.orb

import android.content.Context
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.VoicePresentationSnapshot

object OrbPresentationLabels {
    fun status(context: Context, facts: VoicePresentationSnapshot): String {
        if (!facts.active && facts.errorReason == null && facts.permissionReason == null) return context.getString(R.string.plasma_stopped)
        if (facts.muted && !facts.playing && facts.errorReason == null && facts.permissionReason == null) return context.getString(R.string.plasma_mic_muted)
        val state = OrbPresentationMapper.map(facts, android.os.SystemClock.elapsedRealtime()).state
        return context.getString(when (state) {
            PlasmaVisualState.IDLE -> R.string.plasma_idle
            PlasmaVisualState.LISTENING -> R.string.plasma_listening
            PlasmaVisualState.USER_SPEAKING -> R.string.plasma_user_speaking
            PlasmaVisualState.PROCESSING -> R.string.plasma_processing
            PlasmaVisualState.AI_SPEAKING -> R.string.plasma_ai_speaking
            PlasmaVisualState.INTERRUPTED -> R.string.plasma_interrupted
            PlasmaVisualState.ERROR -> R.string.plasma_error
            PlasmaVisualState.DISCONNECTED -> R.string.plasma_disconnected
        })
    }
}
