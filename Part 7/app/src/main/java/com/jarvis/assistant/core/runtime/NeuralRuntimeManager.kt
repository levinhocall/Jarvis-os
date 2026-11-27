package com.jarvis.assistant.core.runtime

import com.jarvis.assistant.audio.model.ModelLifecycleState
import com.jarvis.assistant.audio.model.NeuralModelRegistry
import com.jarvis.assistant.audio.model.NeuralRuntimeKind
import com.jarvis.assistant.core.reliability.DiagnosticEvent
import com.jarvis.assistant.core.reliability.ErrorCategory
import com.jarvis.assistant.core.reliability.ReliabilityCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class InferencePriority(val weight: Int) {
    REALTIME_AUDIO(0),
    CONVERSATION_SPEECH(1),
    BACKGROUND_VISION_ENV(2),
    MEMORY_INDEXING(3)
}

data class ManagedModelSession(
    val modelId: String,
    val runtime: NeuralRuntimeKind,
    val estimatedMemoryMb: Int,
    val priority: InferencePriority,
    val state: ModelLifecycleState,
    val loadedAtMs: Long,
    val lastUsedAtMs: Long,
    val avgInferenceMicros: Long = 0L,
    val errorReason: String? = null
)

data class NeuralRuntimeSnapshot(
    val sessions: List<ManagedModelSession> = emptyList(),
    val activeMemoryMb: Int = 0,
    val budgetMb: Int = NeuralModelRegistry.PHASE_1_ACTIVE_MEMORY_CAP_MB
)

/**
 * Unified lifecycle, memory-budget, and priority coordinator across LiteRT, ONNX Runtime,
 * Sherpa-ONNX, WebRTC C++, and Vosk sessions on 6 GB RAM ARM64 hardware.
 */
object NeuralRuntimeManager {
    private data class RegisteredHandle(
        var session: ManagedModelSession,
        val onUnload: () -> Unit
    )

    private val registry = ConcurrentHashMap<String, RegisteredHandle>()
    private val _snapshot = MutableStateFlow(NeuralRuntimeSnapshot())
    val snapshot: StateFlow<NeuralRuntimeSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun registerLoaded(
        modelId: String,
        runtime: NeuralRuntimeKind,
        estimatedMemoryMb: Int,
        priority: InferencePriority,
        onUnload: () -> Unit = {}
    ) {
        val now = System.currentTimeMillis()
        val session = ManagedModelSession(
            modelId = modelId,
            runtime = runtime,
            estimatedMemoryMb = estimatedMemoryMb.coerceAtLeast(0),
            priority = priority,
            state = ModelLifecycleState.READY,
            loadedAtMs = now,
            lastUsedAtMs = now
        )
        registry[modelId] = RegisteredHandle(session, onUnload)
        publishSnapshot()
        JarvisEventBus.tryPublish(
            JarvisEvent.ModelLoaded(
                modelId = modelId,
                estimatedMemoryMb = estimatedMemoryMb,
                eventId = JarvisEventBus.nextId(),
                timestampMs = now,
                sessionId = 0L
            )
        )
    }

    @Synchronized
    fun recordInference(
        modelId: String,
        durationMicros: Long,
        active: Boolean = false
    ) {
        val entry = registry[modelId] ?: return
        val prev = entry.session
        val smoothed = if (prev.avgInferenceMicros == 0L) {
            durationMicros
        } else {
            ((prev.avgInferenceMicros * 3L) + durationMicros) / 4L
        }
        entry.session = prev.copy(
            state = if (active) ModelLifecycleState.RUNNING else ModelLifecycleState.IDLE,
            lastUsedAtMs = System.currentTimeMillis(),
            avgInferenceMicros = smoothed
        )
        publishSnapshot()
    }

    @Synchronized
    fun markFailed(modelId: String, runtime: NeuralRuntimeKind, reason: String) {
        val now = System.currentTimeMillis()
        val prev = registry[modelId]
        val failedSession = ManagedModelSession(
            modelId = modelId,
            runtime = runtime,
            estimatedMemoryMb = 0,
            priority = prev?.session?.priority ?: InferencePriority.BACKGROUND_VISION_ENV,
            state = ModelLifecycleState.FAILED,
            loadedAtMs = prev?.session?.loadedAtMs ?: now,
            lastUsedAtMs = now,
            errorReason = reason.take(180)
        )
        registry[modelId] = RegisteredHandle(failedSession, prev?.onUnload ?: {})
        publishSnapshot()
        ReliabilityCoordinator.reportDiagnostic(
            DiagnosticEvent(
                category = ErrorCategory.HARDWARE_UNAVAILABLE,
                component = "NeuralRuntime:$modelId",
                rawMessage = reason,
                isRecoverable = true
            )
        )
    }

    @Synchronized
    fun unloadModel(modelId: String, reason: String = "manual") {
        val handle = registry.remove(modelId) ?: return
        runCatching { handle.onUnload() }
        publishSnapshot()
        JarvisEventBus.tryPublish(
            JarvisEvent.ModelUnloaded(
                modelId = modelId,
                reason = reason,
                eventId = JarvisEventBus.nextId(),
                timestampMs = System.currentTimeMillis(),
                sessionId = 0L
            )
        )
    }

    /**
     * Evicts lowest-priority idle models (memory indexing -> vision/env -> cold TTS/STT -> realtime)
     * until active memory fits inside [targetBudgetMb].
     */
    @Synchronized
    fun trimToBudget(targetBudgetMb: Int, reason: String): Int {
        val candidates = registry.values
            .filter { it.session.state != ModelLifecycleState.FAILED }
            .sortedWith(
                compareByDescending<RegisteredHandle> { it.session.priority.weight }
                    .thenBy { it.session.lastUsedAtMs }
            )
        var currentMb = totalActiveMemoryMb()
        var evicted = 0
        for (candidate in candidates) {
            if (currentMb <= targetBudgetMb) break
            // Never evict REALTIME_AUDIO unless explicitly over critical threshold and idle.
            if (candidate.session.priority == InferencePriority.REALTIME_AUDIO &&
                targetBudgetMb > 96
            ) {
                continue
            }
            val modelId = candidate.session.modelId
            currentMb = (currentMb - candidate.session.estimatedMemoryMb).coerceAtLeast(0)
            unloadModel(modelId, reason)
            evicted++
        }
        return evicted
    }

    @Synchronized
    fun unloadIdleOlderThan(maxIdleMs: Long, minPriorityWeight: Int = InferencePriority.BACKGROUND_VISION_ENV.weight): Int {
        val now = System.currentTimeMillis()
        val staleIds = registry.values
            .filter {
                it.session.priority.weight >= minPriorityWeight &&
                    it.session.state != ModelLifecycleState.RUNNING &&
                    (now - it.session.lastUsedAtMs) >= maxIdleMs
            }
            .map { it.session.modelId }
        staleIds.forEach { unloadModel(it, "idle_timeout") }
        return staleIds.size
    }

    fun canSchedule(priority: InferencePriority): Boolean {
        val policy = ResourceGovernor.policy.value
        return when (priority) {
            InferencePriority.REALTIME_AUDIO -> true
            InferencePriority.CONVERSATION_SPEECH -> true
            InferencePriority.BACKGROUND_VISION_ENV -> policy.allowBackgroundYamnet
            InferencePriority.MEMORY_INDEXING -> policy.allowDenseEmbedder
        }
    }

    fun getSession(modelId: String): ManagedModelSession? = registry[modelId]?.session

    fun totalActiveMemoryMb(): Int =
        registry.values
            .filter { it.session.state != ModelLifecycleState.FAILED && it.session.state != ModelLifecycleState.UNLOADED }
            .sumOf { it.session.estimatedMemoryMb }

    @Synchronized
    fun clearForTest() {
        registry.clear()
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val sessions = registry.values.map { it.session }.sortedBy { it.priority.weight }
        _snapshot.value = NeuralRuntimeSnapshot(
            sessions = sessions,
            activeMemoryMb = totalActiveMemoryMb(),
            budgetMb = ResourceGovernor.policy.value.activeModelBudgetMb
        )
    }
}
