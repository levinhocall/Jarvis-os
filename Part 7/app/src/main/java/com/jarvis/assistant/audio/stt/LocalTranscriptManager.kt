package com.jarvis.assistant.audio.stt

import com.jarvis.assistant.audio.tts.SpeechLanguageRoute
import com.jarvis.assistant.audio.tts.TtsLanguageRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class LocalTranscriptState(
    val partialText: String = "",
    val finalText: String = "",
    val languageRoute: SpeechLanguageRoute = SpeechLanguageRoute.ENGLISH_LATIN,
    val lastUpdatedMs: Long = 0L,
    val activeBackend: String = "UNINITIALIZED"
)

enum class OfflineCommandKind {
    NONE,
    STOP_SPEAKING,
    MUTE_MIC,
    UNMUTE_MIC,
    FLASHLIGHT_ON,
    FLASHLIGHT_OFF,
    CHECK_TIME,
    CANCEL_MISSION
}

data class OfflineCommandMatch(
    val kind: OfflineCommandKind,
    val rawTranscript: String
)

/** Deterministic zero-cloud reflex matcher across English and Hinglish voice commands. */
object OfflineCommandRecognizer {
    private val STOP_PHRASES = setOf("stop", "ruko", "bas", "chup", "pause", "wait", "stop speaking")
    private val MUTE_PHRASES = setOf("mute", "mute mic", "mute microphone", "mic band karo", "mic mute karo")
    private val UNMUTE_PHRASES = setOf("unmute", "unmute mic", "unmute microphone", "mic on karo")
    private val FLASH_ON_PHRASES = setOf("flashlight on", "turn on flashlight", "torch on", "torch jalao")
    private val FLASH_OFF_PHRASES = setOf("flashlight off", "turn off flashlight", "torch off", "torch band karo")
    private val CANCEL_PHRASES = setOf("cancel mission", "cancel task", "stop mission", "mission band karo")

    fun match(transcript: String): OfflineCommandMatch {
        val norm = transcript.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (norm.isEmpty()) return OfflineCommandMatch(OfflineCommandKind.NONE, transcript)

        val kind = when {
            norm in STOP_PHRASES -> OfflineCommandKind.STOP_SPEAKING
            norm in MUTE_PHRASES -> OfflineCommandKind.MUTE_MIC
            norm in UNMUTE_PHRASES -> OfflineCommandKind.UNMUTE_MIC
            norm in FLASH_ON_PHRASES -> OfflineCommandKind.FLASHLIGHT_ON
            norm in FLASH_OFF_PHRASES -> OfflineCommandKind.FLASHLIGHT_OFF
            norm in CANCEL_PHRASES -> OfflineCommandKind.CANCEL_MISSION
            norm.contains("what time") || norm.contains("kitne baje") || norm == "time please" -> OfflineCommandKind.CHECK_TIME
            else -> OfflineCommandKind.NONE
        }
        return OfflineCommandMatch(kind, transcript)
    }
}

/** Coordinates live partial/final transcript state, language routing hints, and offline reflex commands. */
class LocalTranscriptManager {
    private val _state = MutableStateFlow(LocalTranscriptState())
    val state: StateFlow<LocalTranscriptState> = _state.asStateFlow()

    fun onTranscript(event: TranscriptEvent, backendName: String): OfflineCommandMatch {
        val clean = event.text.trim()
        val route = TtsLanguageRouter.classifyText(clean)
        val prev = _state.value
        _state.value = if (event.isFinal) {
            prev.copy(
                partialText = "",
                finalText = clean,
                languageRoute = route,
                lastUpdatedMs = event.timestampMs,
                activeBackend = backendName
            )
        } else {
            prev.copy(
                partialText = clean,
                languageRoute = route,
                lastUpdatedMs = event.timestampMs,
                activeBackend = backendName
            )
        }
        return if (clean.isNotEmpty()) OfflineCommandRecognizer.match(clean) else OfflineCommandMatch(OfflineCommandKind.NONE, "")
    }

    fun reset() {
        _state.value = _state.value.copy(partialText = "", finalText = "", lastUpdatedMs = System.currentTimeMillis())
    }
}
