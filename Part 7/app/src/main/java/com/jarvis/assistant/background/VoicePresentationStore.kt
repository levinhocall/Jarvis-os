package com.jarvis.assistant.background

import com.jarvis.assistant.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serialized facts, not a second voice engine. Every asynchronous producer carries a session token. */
class VoicePresentationStore {
    private val mutable = MutableStateFlow(VoicePresentationSnapshot())
    val snapshots = mutable.asStateFlow()

    @Synchronized fun begin(muted: Boolean): Long {
        val previous = mutable.value
        mutable.value = VoicePresentationSnapshot(sessionId = previous.sessionId + 1, active = true,
            connection = VoiceConnection.CONNECTING, muted = muted,
            interruptionGeneration = previous.interruptionGeneration + 1)
        return mutable.value.sessionId
    }

    @Synchronized fun update(sessionId: Long, change: (VoicePresentationSnapshot) -> VoicePresentationSnapshot) {
        if (mutable.value.sessionId == sessionId) mutable.value = change(mutable.value)
    }

    @Synchronized fun interrupt(sessionId: Long) = update(sessionId) {
        it.copy(interruptionGeneration = it.interruptionGeneration + 1, interrupted = true,
            playing = false, output = VoiceEnvelope(), confirmedSpeech = false, pendingResponse = false)
    }

    @Synchronized fun stop(sessionId: Long) = update(sessionId) {
        VoicePresentationSnapshot(sessionId = it.sessionId + 1,
            interruptionGeneration = it.interruptionGeneration + 1,
            errorReason = it.errorReason, permissionReason = it.permissionReason)
    }
}
