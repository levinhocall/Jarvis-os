package com.jarvis.assistant.ui.orb

import com.jarvis.assistant.background.VoicePresentationStore
import com.jarvis.assistant.data.model.*
import org.junit.Assert.*
import org.junit.Test

class PlasmaPresentationTest {
    private val live = VoicePresentationSnapshot(active = true, capturing = true, connection = VoiceConnection.LIVE)
    private fun state(f: VoicePresentationSnapshot) = OrbPresentationMapper.map(f, 1000).state

    @Test fun sessionSequenceAndConflictingSignals() {
        assertEquals(PlasmaVisualState.IDLE, state(VoicePresentationSnapshot()))
        assertEquals(PlasmaVisualState.LISTENING, state(live))
        assertEquals(PlasmaVisualState.USER_SPEAKING, state(live.copy(confirmedSpeech = true)))
        assertEquals(PlasmaVisualState.PROCESSING, state(live.copy(pendingResponse = true)))
        assertEquals(PlasmaVisualState.AI_SPEAKING, state(live.copy(playing = true, pendingResponse = true)))
        assertEquals(PlasmaVisualState.INTERRUPTED, state(live.copy(playing = true, interrupted = true)))
        assertEquals(PlasmaVisualState.ERROR, state(live.copy(errorReason = "capture failed")))
        assertEquals(PlasmaVisualState.DISCONNECTED, state(live.copy(connection = VoiceConnection.OFFLINE)))
    }

    @Test fun energyIsNotSpeechAndQueuedResponseIsNotPlayback() {
        assertEquals(PlasmaVisualState.LISTENING, state(live.copy(microphone = VoiceEnvelope(1f, 1000))))
        assertEquals(PlasmaVisualState.PROCESSING, state(live.copy(pendingResponse = true, output = VoiceEnvelope(1f, 1000))))
        assertEquals(PlasmaVisualState.IDLE, state(live.copy(muted = true, confirmedSpeech = true)))
        assertEquals(PlasmaVisualState.ERROR, state(live.copy(permissionReason = "Denied", confirmedSpeech = true)))
    }

    @Test fun staleAudioAndUnknownTtsTimingCannotAnimateSpeech() {
        val audible = live.copy(playing = true, timing = AudioTimingConfidence.PLAYBACK_HEAD,
            output = VoiceEnvelope(1f, 1000))
        assertEquals(1f, OrbPresentationMapper.map(audible, 1000).output, 0f)
        assertEquals(0f, OrbPresentationMapper.map(audible, 1400).output, 0f)
        assertEquals(0f, OrbPresentationMapper.map(audible.copy(timing = AudioTimingConfidence.STATE_ONLY), 1000).output, 0f)
        assertNotEquals(PlasmaVisualState.AI_SPEAKING, state(audible.copy(interruptionGeneration = 1)))
        assertEquals(0f, VoiceEnvelope(Float.NaN, 1000).fresh(1000, 0), 0f)
    }

    @Test fun conflationRetainsInterruptionAndOldSessionCannotOverwriteNew() {
        val store = VoicePresentationStore()
        val old = store.begin(false)
        val before = store.snapshots.value.interruptionGeneration
        store.interrupt(old)
        store.update(old) { it.copy(interrupted = false) }
        assertEquals(before + 1, store.snapshots.value.interruptionGeneration)
        store.stop(old)
        val current = store.begin(true)
        store.update(old) { it.copy(playing = true) }
        assertEquals(current, store.snapshots.value.sessionId)
        assertFalse(store.snapshots.value.playing)
    }

    @Test fun disconnectDuringAudiblePlaybackRetainsPlaybackWithConnectionReason() {
        assertEquals(PlasmaVisualState.AI_SPEAKING, state(live.copy(playing = true, connection = VoiceConnection.OFFLINE)))
        assertEquals(PlasmaVisualState.USER_SPEAKING, state(live.copy(confirmedSpeech = true, connection = VoiceConnection.OFFLINE)))
    }
}
