package com.jarvis.assistant.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight, thread-safe diagnostic event recorder for the voice pipeline.
 *
 * Tracks monotonic stage durations, audio routing, focus transitions, underruns,
 * and barge-in events without storing raw audio, transcripts, or sensitive data.
 */
object VoiceFlightRecorder {
    private const val TAG = "VoiceFlightRecorder"
    private const val MAX_EVENTS = 120

    data class FlightEvent(
        val wallTimeMs: Long = System.currentTimeMillis(),
        val elapsedNano: Long = System.nanoTime(),
        val sessionId: String,
        val stage: String,
        val details: String = "",
        val durationMs: Long? = null
    )

    private val events = ConcurrentLinkedDeque<FlightEvent>()
    private val currentSessionId = AtomicReference("session_init")
    private val sessionStartNano = AtomicLong(System.nanoTime())
    private val wakeDetectNano = AtomicLong(0L)
    private val speechEndNano = AtomicLong(0L)
    private val aiRequestNano = AtomicLong(0L)
    private val firstTokenNano = AtomicLong(0L)
    private val firstAudioFrameNano = AtomicLong(0L)
    private val playbackStartNano = AtomicLong(0L)

    val underrunCount = AtomicInteger(0)
    val bargeInCount = AtomicInteger(0)
    val lastErrorCategory = AtomicReference<String>("NONE")

    fun startSession(source: String): String {
        val id = "sess_${System.currentTimeMillis() % 100000}"
        currentSessionId.set(id)
        val now = System.nanoTime()
        sessionStartNano.set(now)
        record(stage = "SESSION_START", details = "source=$source")
        return id
    }

    fun endSession(reason: String) {
        val start = sessionStartNano.get()
        val duration = if (start > 0) (System.nanoTime() - start) / 1_000_000L else null
        record(stage = "SESSION_END", details = "reason=$reason", durationMs = duration)
    }

    fun recordWakeDetected() {
        wakeDetectNano.set(System.nanoTime())
        record(stage = "WAKE_DETECTED", details = "hotword=Jarvis")
    }

    fun recordWakeAckSpoken() {
        val wake = wakeDetectNano.get()
        val duration = if (wake > 0) (System.nanoTime() - wake) / 1_000_000L else null
        record(stage = "WAKE_ACK_SPOKEN", details = "local_tts", durationMs = duration)
    }

    fun recordSpeechEnd() {
        speechEndNano.set(System.nanoTime())
        record(stage = "USER_SPEECH_END", details = "vad_turn_boundary")
        try {
            VoiceLatencyTracker.shared.markUserSpeechEnded()
        } catch (_: Throwable) {}
    }

    fun recordAiRequestSent() {
        aiRequestNano.set(System.nanoTime())
        record(stage = "AI_REQUEST_SENT", details = "gemini_live_stream")
    }

    fun recordFirstTokenReceived() {
        val now = System.nanoTime()
        firstTokenNano.set(now)
        val speechEnd = speechEndNano.get()
        val latency = if (speechEnd > 0) (now - speechEnd) / 1_000_000L else null
        record(stage = "AI_FIRST_TOKEN", details = "text_or_audio_meta", durationMs = latency)
        try {
            VoiceLatencyTracker.shared.markFirstTextReceived()
        } catch (_: Throwable) {}
    }

    fun recordFirstAudioFrame() {
        val now = System.nanoTime()
        firstAudioFrameNano.set(now)
        playbackStartNano.set(now)
        val req = aiRequestNano.get()
        val latency = if (req > 0) (now - req) / 1_000_000L else null
        record(stage = "FIRST_AUDIO_FRAME", details = "track_write_start", durationMs = latency)
        try {
            VoiceLatencyTracker.shared.markFirstAudioReceived()
        } catch (_: Throwable) {}
    }

    fun recordPlaybackComplete() {
        val start = playbackStartNano.get()
        val duration = if (start > 0) (System.nanoTime() - start) / 1_000_000L else null
        record(stage = "PLAYBACK_COMPLETE", details = "speaker_drain_done", durationMs = duration)
        try {
            VoiceLatencyTracker.shared.markTurnCompleted()
        } catch (_: Throwable) {}
    }

    fun recordBargeIn(source: String) {
        bargeInCount.incrementAndGet()
        record(stage = "BARGE_IN_TRIGGERED", details = "source=$source")
        try {
            VoiceLatencyTracker.shared.cancelCurrentTurn()
        } catch (_: Throwable) {}
    }

    fun recordAudioRoute(route: String, mode: String) {
        record(stage = "ROUTE_UPDATE", details = "route=$route mode=$mode")
    }

    fun recordFocusEvent(action: String, result: String) {
        record(stage = "AUDIO_FOCUS", details = "action=$action result=$result")
    }

    fun recordUnderrun() {
        underrunCount.incrementAndGet()
        record(stage = "AUDIO_UNDERRUN", details = "buffer_starvation_detected")
    }

    fun recordError(category: String, message: String) {
        lastErrorCategory.set(category)
        record(stage = "ERROR", details = "category=$category msg=${message.take(60)}")
    }

    fun record(stage: String, details: String = "", durationMs: Long? = null) {
        val event = FlightEvent(
            sessionId = currentSessionId.get(),
            stage = stage,
            details = details,
            durationMs = durationMs
        )
        events.offer(event)
        while (events.size > MAX_EVENTS) {
            events.poll()
        }
        Log.d(TAG, "[${event.sessionId}] ${event.stage} ${if (details.isNotBlank()) "($details)" else ""} ${if (durationMs != null) "${durationMs}ms" else ""}")
    }

    fun getRecentEvents(): List<FlightEvent> = events.toList()

    fun clearHistory() {
        events.clear()
        underrunCount.set(0)
        bargeInCount.set(0)
        lastErrorCategory.set("NONE")
    }

    fun getDiagnosticSummary(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val modeStr = when (am?.mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL (Loudspeaker/Media)"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION (VoIP/SCO)"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL (Telephony)"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            else -> "UNKNOWN (${am?.mode})"
        }

        val activeOutput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dev = am?.communicationDevice
            if (dev != null) "${dev.productName} (type=${dev.type})" else "Default Loudspeaker/Media Output"
        } else {
            @Suppress("DEPRECATION")
            if (am?.isSpeakerphoneOn == true) "Loudspeaker (Speakerphone ON)" else "Normal Audio Route"
        }

        val sb = StringBuilder()
        sb.appendLine("=== JARVIS VOICE FLIGHT RECORDER ===")
        sb.appendLine("Audio Mode     : $modeStr")
        sb.appendLine("Selected Route : $activeOutput")
        sb.appendLine("AudioTrack Out : 24,000 Hz 16-bit Mono (19,200 B buffer / 400ms)")
        sb.appendLine("AudioRecord In : 16,000 Hz 16-bit Mono (640 B chunk / 20ms)")
        sb.appendLine("TTS Engine     : VoiceSynthesisRouter (Pitch=0.92, Rate=1.08)")
        sb.appendLine("Total Underruns: ${underrunCount.get()}")
        sb.appendLine("Barge-ins      : ${bargeInCount.get()}")
        sb.appendLine("Last Error Cat : ${lastErrorCategory.get()}")
        sb.appendLine("--- Recent Pipeline Events (Last ${events.size}) ---")
        events.toList().takeLast(15).forEach { ev ->
            val dur = if (ev.durationMs != null) " [${ev.durationMs}ms]" else ""
            sb.appendLine("${ev.stage}: ${ev.details}$dur")
        }
        return sb.toString()
    }
}
