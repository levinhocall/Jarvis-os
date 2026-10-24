package com.jarvis.assistant.audio

import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import com.jarvis.assistant.data.model.ConversationState
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ultra-Low-Latency Voice Interruption & Barge-in Coordinator.
 *
 * Provides thread-safe, atomic barge-in arbitration, loudspeaker echo cancellation,
 * late streaming audio chunk suppression, and audio resource lifecycle management.
 */
object VoiceInterruptionCoordinator {
    private const val TAG = "VoiceInterruptionCoord"
    private const val DEBOUNCE_WINDOW_MS = 350L

    interface TimeProvider {
        fun currentTimeMillis(): Long
    }

    object SystemTimeProvider : TimeProvider {
        override fun currentTimeMillis(): Long = System.currentTimeMillis()
    }

    var timeProvider: TimeProvider = SystemTimeProvider

    const val ECHO_TAIL_MS = 450L
    const val WATCHDOG_FORCE_UNMUTE_MS = 60_000L

    val playbackBufferCount = java.util.concurrent.atomic.AtomicInteger(0)
    val lastPlaybackFinishTime = AtomicLong(0L)
    val playbackStartTime = AtomicLong(0L)

    val activeEpoch = AtomicLong(0L)
    private val lastInterruptionTime = AtomicLong(0L)
    private val isInterruptionActive = AtomicBoolean(false)

    /**
     * Called when an audio playback chunk is submitted to the output player.
     */
    fun onPlaybackBufferEnqueued() {
        if (playbackBufferCount.getAndIncrement() == 0) {
            playbackStartTime.set(timeProvider.currentTimeMillis())
        }
    }

    /**
     * Called when an audio playback chunk finishes rendering.
     */
    fun onPlaybackBufferCompleted() {
        val remaining = playbackBufferCount.updateAndGet { if (it > 0) it - 1 else 0 }
        if (remaining == 0) {
            lastPlaybackFinishTime.set(timeProvider.currentTimeMillis())
        }
    }

    /**
     * Called on speech interruption or manual playback stop.
     */
    fun onPlaybackStopped() {
        playbackBufferCount.set(0)
        lastPlaybackFinishTime.set(timeProvider.currentTimeMillis())
    }

    /**
     * Returns true if microphone input should be suppressed to prevent loudspeaker self-echo.
     * Mutes during active playback buffer rendering, maintains a 450ms acoustic dissipation tail,
     * and employs a 60s watchdog to automatically force unmute if buffers stall.
     */
    fun isEchoGuardMuted(): Boolean {
        val now = timeProvider.currentTimeMillis()
        val count = playbackBufferCount.get()
        if (count > 0) {
            val elapsed = now - playbackStartTime.get()
            if (elapsed > WATCHDOG_FORCE_UNMUTE_MS) {
                Log.w(TAG, "Echo guard watchdog triggered: audio playback stalled for ${elapsed}ms. Force-unmuting.")
                playbackBufferCount.set(0)
                lastPlaybackFinishTime.set(now)
                return false
            }
            return true
        }
        val timeSinceFinish = now - lastPlaybackFinishTime.get()
        return timeSinceFinish in 0 until ECHO_TAIL_MS
    }

    // Hotwords for instantaneous (<50ms) single-token barge-in
    private val INSTANT_HOTWORDS = setOf(
        "stop", "ruko", "ruk", "bas", "wait", "suno", "chup",
        "cancel", "hold", "pause", "shutup", "quiet"
    )

    // Compound hotword phrases (Hindi, English, Hinglish)
    private val COMPOUND_HOTWORDS = listOf(
        "hold on", "bas karo", "ruk jao", "chup karo",
        "ek minute", "arre suno", "wait a minute", "shut up",
        "ek second", "bas bas", "chup ho jao", "nahi chahiye",
        "rehne do", "arey ruko", "wait wait", "ruk ruk",
        "suno na", "pause karo", "ruko ruko", "chup kar", "chup raho"
    )

    /**
     * Calculates the estimated external acoustic energy by subtracting
     * loudspeaker acoustic feedback coupling from raw microphone amplitude.
     */
    fun calculateEffectiveMicAmplitude(
        rawMicAmplitude: Float,
        speakerPlaybackAmplitude: Float,
        couplingFactor: Float = 0.55f
    ): Float {
        if (speakerPlaybackAmplitude <= 0.01f) return rawMicAmplitude
        val estimatedLeakage = speakerPlaybackAmplitude * couplingFactor
        return (rawMicAmplitude - estimatedLeakage).coerceAtLeast(0f)
    }

    /**
     * Evaluates incoming recognized speech against active playback text.
     * Distinguishes user intent to interrupt from assistant loudspeaker self-echo.
     */
    fun evaluateBargeIn(
        recognizedText: String,
        assistantSpokenText: String,
        currentState: ConversationState? = null,
        isLoudspeaker: Boolean = false
    ): Boolean {
        if (currentState != null && currentState != ConversationState.SPEAKING && currentState != ConversationState.THINKING) {
            return false
        }

        val clean = recognizedText.lowercase(Locale.ROOT).trim()
        if (clean.isBlank()) return false

        val tokens = clean.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        // 1. Explicit Instant Hotwords (Always trigger on headset and loudspeaker)
        if (tokens.any { INSTANT_HOTWORDS.contains(it) }) {
            Log.d(TAG, "Instant hotword detected: $clean")
            return true
        }

        // 2. Compound Hotword Phrases (Always trigger on headset and loudspeaker)
        if (COMPOUND_HOTWORDS.any { clean.contains(it) }) {
            Log.d(TAG, "Compound phrase detected: $clean")
            return true
        }

        // In loudspeaker mode (!hasHeadset), Vosk with an English acoustic model frequently hallucinates
        // short random English tokens (e.g. "tell", "what", "play") when picking up the assistant's own
        // Hindi/Hinglish speech from the phone speaker. To completely prevent mid-speech cutoffs,
        // loudspeaker barge-in requires explicit hotwords ("stop", "ruko", "bas", etc.) or 3+ words.
        if (isLoudspeaker) {
            if (tokens.size >= 3) {
                val assistantClean = assistantSpokenText.lowercase(Locale.ROOT)
                val isExactEcho = tokens.all { assistantClean.contains(it) }
                if (!isExactEcho) {
                    Log.d(TAG, "Natural speech barge-in on loudspeaker: $clean")
                    return true
                }
            }
            return false
        }

        // 3. Natural User Speech on Headset (>= 2 tokens or single token >= 4 letters)
        // Guard against assistant's own loudspeaker audio self-echo
        if (tokens.size >= 2 || (tokens.size == 1 && tokens[0].length >= 4 && tokens[0] != "[unk]")) {
            val assistantClean = assistantSpokenText.lowercase(Locale.ROOT)
            val isExactEcho = tokens.all { assistantClean.contains(it) }
            if (!isExactEcho) {
                Log.d(TAG, "Natural speech barge-in detected: $clean")
                return true
            } else {
                Log.d(TAG, "Suppressed assistant self-echo: $clean")
                return false
            }
        }

        return false
    }

    fun resetDebounce() {
        lastInterruptionTime.set(0L)
    }

    /**
     * Executes atomic interruption: stops active playback, invalidates current generation epoch,
     * prevents late audio chunks from rendering, and notifies listeners without race conditions.
     */
    fun requestBargeIn(
        source: String,
        force: Boolean = false,
        onExecuteInterruption: (newEpoch: Long) -> Unit
    ): Boolean {
        val now = System.currentTimeMillis()
        val last = lastInterruptionTime.get()

        // Debounce fast duplicate callbacks within DEBOUNCE_WINDOW_MS unless force=true
        if (!force && (now - last < DEBOUNCE_WINDOW_MS)) {
            Log.d(TAG, "Barge-in request from '$source' debounced (within window)")
            return false
        }

        lastInterruptionTime.set(now)
        val newEpoch = activeEpoch.incrementAndGet()
        isInterruptionActive.set(true)
        Log.i(TAG, "Barge-in triggered successfully from '$source' (epoch=$newEpoch)")

        try {
            onExecuteInterruption(newEpoch)
        } finally {
            isInterruptionActive.set(false)
        }
        return true
    }

    /**
     * Validates whether an incoming audio chunk belongs to the current valid generation.
     * If the generation has been invalidated by an interruption, the chunk is dropped.
     */
    fun isChunkValid(chunkEpoch: Long): Boolean {
        return chunkEpoch == activeEpoch.get()
    }

    /**
     * Configures AcousticEchoCanceler safely on the provided audio session.
     * Returns true if hardware AEC is supported and enabled, false otherwise.
     */
    fun configureAcousticEchoCanceler(audioSessionId: Int): Boolean {
        if (audioSessionId <= 0) return false
        return try {
            if (AcousticEchoCanceler.isAvailable()) {
                val aec = AcousticEchoCanceler.create(audioSessionId)
                if (aec != null) {
                    aec.enabled = true
                    Log.i(TAG, "Hardware AcousticEchoCanceler enabled on session $audioSessionId")
                    true
                } else {
                    Log.w(TAG, "AcousticEchoCanceler.create returned null for session $audioSessionId")
                    false
                }
            } else {
                Log.i(TAG, "Hardware AcousticEchoCanceler not available on this device")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error configuring AcousticEchoCanceler: ${e.message}")
            false
        }
    }
}
