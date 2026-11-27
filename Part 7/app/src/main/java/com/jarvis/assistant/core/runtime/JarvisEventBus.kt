package com.jarvis.assistant.core.runtime

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import java.util.concurrent.atomic.AtomicLong

/**
 * Typed immutable application events connecting perception, audio intelligence,
 * cognitive routing, autonomous missions, and presentation without passing high-rate PCM frames.
 */
sealed interface JarvisEvent {
    val eventId: Long
    val timestampMs: Long
    val sessionId: Long
    val sourceEngine: String

    data class WakeWordDetected(
        val phrase: String,
        val confidence: Float,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "OpenWakeWord"
    ) : JarvisEvent

    data class SpeechStarted(
        val probability: Float,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "SileroVAD"
    ) : JarvisEvent

    data class SpeechEnded(
        val durationMs: Long,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "SileroVAD"
    ) : JarvisEvent

    data class TranscriptUpdated(
        val text: String,
        val isFinal: Boolean,
        val languageTag: String,
        val confidence: Float,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "StreamingSTT"
    ) : JarvisEvent

    data class TurnCompleted(
        val combinedScore: Float,
        val reason: String,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "TurnDecisionEngine"
    ) : JarvisEvent

    data class AssistantResponseStarted(
        val generation: Long,
        val backend: String,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "VoiceSynthesisRouter"
    ) : JarvisEvent

    data class AssistantResponseCompleted(
        val generation: Long,
        val backend: String,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "VoiceSynthesisRouter"
    ) : JarvisEvent

    data class BargeInDetected(
        val interruptionGeneration: Long,
        val reason: String,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "BargeInController"
    ) : JarvisEvent

    data class ModelLoaded(
        val modelId: String,
        val estimatedMemoryMb: Int,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "NeuralRuntimeManager"
    ) : JarvisEvent

    data class ModelUnloaded(
        val modelId: String,
        val reason: String,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "NeuralRuntimeManager"
    ) : JarvisEvent

    data class EnvironmentChanged(
        val label: String,
        val confidence: Float,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "EnvironmentContextManager"
    ) : JarvisEvent

    data class ThermalStateChanged(
        val thermalStatus: Int,
        val pressureLevel: String,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "ResourceGovernor"
    ) : JarvisEvent

    data class ResourcePressureDetected(
        val trimLevel: Int,
        val availableHeapMb: Long,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = "ResourceGovernor"
    ) : JarvisEvent

    data class EngineFailure(
        val engineName: String,
        val reason: String,
        val recoverable: Boolean,
        override val eventId: Long,
        override val timestampMs: Long,
        override val sessionId: Long,
        override val sourceEngine: String = engineName
    ) : JarvisEvent
}

/** Non-blocking bounded event bus separating control signals from real-time audio buffers. */
object JarvisEventBus {
    private val sequence = AtomicLong(0L)

    private val _events = MutableSharedFlow<JarvisEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<JarvisEvent> = _events.asSharedFlow()

    fun nextId(): Long = sequence.incrementAndGet()

    fun tryPublish(event: JarvisEvent): Boolean = _events.tryEmit(event)

    inline fun <reified T : JarvisEvent> observe(): kotlinx.coroutines.flow.Flow<T> =
        events.filterIsInstance<T>()
}
